package com.pnas.agent;

import com.pnas.agent.config.AgentConfig;
import com.pnas.agent.plan.Planner;
import com.pnas.agent.remote.AgentException;
import com.pnas.agent.remote.HttpJson;
import com.pnas.agent.remote.ManifestClient;
import com.pnas.agent.remote.NodeClient;
import com.pnas.agent.remote.RunClient;
import com.pnas.agent.remote.UploadClient;
import com.pnas.agent.restore.Restorer;
import com.pnas.agent.restore.Verifier;
import com.pnas.agent.run.RunMode;
import com.pnas.agent.run.Runner;
import com.pnas.agent.scan.Scanner;
import com.pnas.agent.sched.Scheduler;
import com.pnas.agent.watch.Debouncer;
import com.pnas.agent.watch.Watcher;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** pnas-agent 入口:退出码 0=成功,1=部分失败,2=配置/鉴权/致命错误。 */
public final class Main {

    /** 实时事件的防抖窗口:最后一条事件落定 5 秒后执行一轮备份。 */
    private static final long REALTIME_DEBOUNCE_MS = 5_000L;

    private Main() {}

    public static void main(String[] args) {
        try {
            int code = run(args);
            System.exit(code);
        } catch (IllegalArgumentException e) {
            System.err.println("参数错误: " + e.getMessage());
            System.exit(2);
        } catch (Exception e) {
            System.err.println("失败: " + e.getMessage());
            System.exit(2);
        }
    }

    static int run(String[] args) {
        if (args.length == 0) {
            System.err.println("用法: init|run|backup|restore|verify|status ...");
            return 2;
        }
        return switch (args[0]) {
            case "init" -> 0;
            case "status" -> 0;
            case "run" -> daemon(args);
            case "backup" -> backup(args);
            case "restore" -> restore(args);
            case "verify" -> verify(args);
            default -> throw new IllegalArgumentException("未知子命令: " + args[0]);
        };
    }

    /** {@code run [--no-watch] [--config <file>]}:常驻,定时备份并按需实时监视。 */
    private static int daemon(String[] args) {
        boolean noWatch = false;
        Path configFile = defaultConfigFile();
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--no-watch" -> noWatch = true;
                case "--config" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--config 需要配置文件路径");
                    }
                    configFile = Path.of(args[++i]);
                }
                default -> throw new IllegalArgumentException("未知参数: " + args[i]);
            }
        }

        AgentConfig config = loadConfig(configFile);
        if (config == null) {
            return 2;
        }
        // 监视需配置开启且未被 --no-watch 显式关闭。
        boolean watch = !noWatch && config.realtime();

        Runner runner = runner(config);
        Scheduler scheduler = new Scheduler(
            () -> runOnce(runner, RunMode.SCHEDULED, "定时备份"), config.intervalMinutes());
        Debouncer debouncer = null;
        List<Watcher> watchers = new ArrayList<>();
        if (watch) {
            debouncer = new Debouncer(REALTIME_DEBOUNCE_MS,
                () -> runOnce(runner, RunMode.REALTIME, "实时备份"));
            for (AgentConfig.Root root : config.roots()) {
                Watcher watcher = new Watcher(Path.of(root.path()), debouncer);
                watcher.start();
                watchers.add(watcher);
            }
        }
        scheduler.start();

        List<Watcher> started = watchers;
        Debouncer realtime = debouncer;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (Watcher watcher : started) {
                watcher.close();
            }
            if (realtime != null) {
                realtime.close();
            }
            scheduler.close();
        }, "pnas-agent-shutdown"));

        System.out.printf("pnas-agent 运行中: intervalMinutes=%d, watch=%s, roots=%d%n",
            config.intervalMinutes(), watch, config.roots().size());
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }

    /** 执行一轮备份并把统计/错误写到标准输出/标准错误;供定时与实时任务共用。 */
    private static void runOnce(Runner runner, RunMode mode, String label) {
        try {
            Runner.Stats stats = runner.run(mode);
            System.out.printf("%s完成: seen=%d uploaded=%d skipped=%d failed=%d bytes=%d%n",
                label, stats.seen(), stats.uploaded(), stats.skipped(), stats.failed(),
                stats.bytes());
        } catch (Exception e) {
            System.err.println(label + "失败: " + e.getMessage());
        }
    }

    /** {@code backup --once [--config <file>]}:执行一轮手动备份并映射退出码。 */
    private static int backup(String[] args) {
        boolean once = false;
        Path configFile = defaultConfigFile();
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--once" -> once = true;
                case "--config" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--config 需要配置文件路径");
                    }
                    configFile = Path.of(args[++i]);
                }
                default -> throw new IllegalArgumentException("未知参数: " + args[i]);
            }
        }
        if (!once) {
            throw new IllegalArgumentException("当前仅支持 backup --once");
        }

        AgentConfig config = loadConfig(configFile);
        if (config == null) {
            return 2;
        }

        Runner runner = runner(config);
        try {
            Runner.Stats stats = runner.run(RunMode.MANUAL);
            System.out.printf("备份完成: seen=%d uploaded=%d skipped=%d failed=%d bytes=%d%n",
                stats.seen(), stats.uploaded(), stats.skipped(), stats.failed(), stats.bytes());
            return stats.failed() > 0 ? 1 : 0;
        } catch (AgentException e) {
            System.err.println("远程调用失败(" + e.status() + "): " + e.getMessage());
            return 2;
        } catch (Exception e) {
            System.err.println("备份失败: " + e.getMessage());
            return 2;
        }
    }

    /** {@code restore --config <file> --path <relPath> --dest <dir> [--overwrite]}. */
    private static int restore(String[] args) {
        Path configFile = defaultConfigFile();
        String relPath = null;
        Path dest = null;
        boolean overwrite = false;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--overwrite" -> overwrite = true;
                case "--config" -> configFile = Path.of(requireValue(args, ++i, "--config"));
                case "--path" -> relPath = requireValue(args, ++i, "--path");
                case "--dest" -> dest = Path.of(requireValue(args, ++i, "--dest"));
                default -> throw new IllegalArgumentException("未知参数: " + args[i]);
            }
        }
        if (relPath == null || relPath.isBlank()) {
            throw new IllegalArgumentException("--path 不能为空");
        }
        if (dest == null) {
            throw new IllegalArgumentException("--dest 不能为空");
        }

        AgentConfig config = loadConfig(configFile);
        if (config == null) {
            return 2;
        }

        try {
            int count = restorer(config).restore(config.deviceId(), relPath, dest, overwrite);
            System.out.printf("恢复完成: files=%d dest=%s%n", count, dest);
            return 0;
        } catch (AgentException e) {
            System.err.println("远程调用失败(" + e.status() + "): " + e.getMessage());
            return 2;
        } catch (Exception e) {
            System.err.println("恢复失败: " + e.getMessage());
            return 1;
        }
    }

    /** {@code verify --config <file> [--all | --limit N]}:默认全量,失败时退出码 1。 */
    private static int verify(String[] args) {
        Path configFile = defaultConfigFile();
        int limit = 0;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--all" -> limit = 0;
                case "--config" -> configFile = Path.of(requireValue(args, ++i, "--config"));
                case "--limit" -> {
                    int value;
                    try {
                        value = Integer.parseInt(requireValue(args, ++i, "--limit"));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("--limit 需要正整数");
                    }
                    if (value <= 0) {
                        throw new IllegalArgumentException("--limit 必须为正数: " + value);
                    }
                    limit = value;
                }
                default -> throw new IllegalArgumentException("未知参数: " + args[i]);
            }
        }

        AgentConfig config = loadConfig(configFile);
        if (config == null) {
            return 2;
        }

        try {
            Verifier.VerifyResult result = verifier(config).verify(config.deviceId(), limit);
            System.out.printf("校验完成: checked=%d passed=%d failed=%d%n",
                result.checked(), result.passed(), result.failed().size());
            if (!result.failed().isEmpty()) {
                for (String failed : result.failed()) {
                    System.err.println("校验失败: " + failed);
                }
                return 1;
            }
            return 0;
        } catch (AgentException e) {
            System.err.println("远程调用失败(" + e.status() + "): " + e.getMessage());
            return 2;
        } catch (Exception e) {
            System.err.println("校验失败: " + e.getMessage());
            return 2;
        }
    }

    private static String requireValue(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException(flag + " 需要参数值");
        }
        return args[index];
    }

    private static AgentConfig loadConfig(Path configFile) {
        try {
            AgentConfig config = AgentConfig.load(configFile);
            config.validate();
            return config;
        } catch (RuntimeException e) {
            System.err.println("配置错误: " + e.getMessage());
            return null;
        }
    }

    /** 按配置装配 HTTP 客户端(backup/run/restore/verify 共用同一接线)。 */
    private static HttpJson http(AgentConfig config) {
        return new HttpJson(config.serverUrl(), config.token());
    }

    /** 按配置装配 Runner。 */
    private static Runner runner(AgentConfig config) {
        HttpJson http = http(config);
        return new Runner(config, new Scanner(), new Planner(),
            new ManifestClient(http), new UploadClient(http), new NodeClient(http),
            new RunClient(http), Scanner.CHUNK_SIZE);
    }

    private static Restorer restorer(AgentConfig config) {
        HttpJson http = http(config);
        return new Restorer(new ManifestClient(http), new NodeClient(http), Scanner.CHUNK_SIZE);
    }

    private static Verifier verifier(AgentConfig config) {
        HttpJson http = http(config);
        return new Verifier(new ManifestClient(http), new NodeClient(http), Scanner.CHUNK_SIZE);
    }

    private static Path defaultConfigFile() {
        return Path.of(System.getProperty("user.home"), ".pnas-agent", "config.json");
    }
}

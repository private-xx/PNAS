package com.pnas.agent;

import com.pnas.agent.config.AgentConfig;
import com.pnas.agent.plan.Planner;
import com.pnas.agent.remote.AgentException;
import com.pnas.agent.remote.HttpJson;
import com.pnas.agent.remote.ManifestClient;
import com.pnas.agent.remote.NodeClient;
import com.pnas.agent.remote.RunClient;
import com.pnas.agent.remote.UploadClient;
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
            default -> throw new IllegalArgumentException("未知子命令: " + args[0]);
        };
    }

    /** {@code run [--no-watch] [--config <file>]}:常驻,定时备份并按需实时监视。 */
    private static int daemon(String[] args) {
        boolean watch = true;
        Path configFile = defaultConfigFile();
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--no-watch" -> watch = false;
                case "--config" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--config 需要配置文件路径");
                    }
                    configFile = Path.of(args[++i]);
                }
                default -> throw new IllegalArgumentException("未知参数: " + args[i]);
            }
        }

        AgentConfig config;
        try {
            config = AgentConfig.load(configFile);
            config.validate();
        } catch (RuntimeException e) {
            System.err.println("配置错误: " + e.getMessage());
            return 2;
        }

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

        AgentConfig config;
        try {
            config = AgentConfig.load(configFile);
            config.validate();
        } catch (RuntimeException e) {
            System.err.println("配置错误: " + e.getMessage());
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

    /** 按配置装配 HTTP 客户端与 Runner(backup 与 run 共用同一接线)。 */
    private static Runner runner(AgentConfig config) {
        HttpJson http = new HttpJson(config.serverUrl(), config.token());
        return new Runner(config, new Scanner(), new Planner(),
            new ManifestClient(http), new UploadClient(http), new NodeClient(http),
            new RunClient(http), Scanner.CHUNK_SIZE);
    }

    private static Path defaultConfigFile() {
        return Path.of(System.getProperty("user.home"), ".pnas-agent", "config.json");
    }
}

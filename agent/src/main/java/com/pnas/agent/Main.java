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

import java.nio.file.Path;

/** pnas-agent 入口:退出码 0=成功,1=部分失败,2=配置/鉴权/致命错误。 */
public final class Main {

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
            case "backup" -> backup(args);
            default -> throw new IllegalArgumentException("未知子命令: " + args[0]);
        };
    }

    /** {@code backup --once [--config <file>]}:执行一轮手动备份并映射退出码。 */
    private static int backup(String[] args) {
        boolean once = false;
        Path configFile = Path.of(System.getProperty("user.home"),
            ".pnas-agent", "config.json");
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

        HttpJson http = new HttpJson(config.serverUrl(), config.token());
        Runner runner = new Runner(config, new Scanner(), new Planner(),
            new ManifestClient(http), new UploadClient(http), new NodeClient(http),
            new RunClient(http), Scanner.CHUNK_SIZE);
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
}

package com.pnas.agent;

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
            default -> throw new IllegalArgumentException("未知子命令: " + args[0]);
        };
    }
}

package com.pnas.agent.sched;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 定时调度器:按固定间隔执行任务,任务异常记录到 stderr 后继续后续周期。
 * {@link #start()} 只启动一次,{@link #close()} 停止调度并关闭执行器。
 */
public final class Scheduler implements AutoCloseable {

    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
    private final Runnable task;
    private final long intervalMinutes;
    private ScheduledFuture<?> future;

    public Scheduler(Runnable task, long intervalMinutes) {
        if (task == null) {
            throw new IllegalArgumentException("task 不能为空");
        }
        if (intervalMinutes <= 0) {
            throw new IllegalArgumentException("intervalMinutes 必须为正数");
        }
        this.task = task;
        this.intervalMinutes = intervalMinutes;
    }

    /** 启动固定频率调度:首个周期在 {@code intervalMinutes} 后执行。 */
    public synchronized void start() {
        if (future != null) {
            throw new IllegalStateException("Scheduler 已启动");
        }
        future = exec.scheduleAtFixedRate(() -> {
            try {
                task.run();
            } catch (Exception e) {
                System.err.println("定时任务失败: " + e.getMessage());
            }
        }, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
    }

    @Override
    public void close() {
        if (future != null) {
            future.cancel(false);
        }
        exec.shutdownNow();
    }
}

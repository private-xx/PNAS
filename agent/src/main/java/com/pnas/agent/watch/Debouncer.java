package com.pnas.agent.watch;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 事件防抖器:窗口期内的连续 {@link #trigger()} 只执行一次动作。
 * 每次触发都取消待执行的定时任务并重新计时,因此突发事件最后落定 {@code windowMs} 后执行。
 * 线程安全;{@link #close()} 会立即关闭内部执行器。
 */
public final class Debouncer implements AutoCloseable {

    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
    private final long windowMs;
    private final Runnable action;
    private ScheduledFuture<?> pending;

    public Debouncer(long windowMs, Runnable action) {
        if (windowMs < 0) {
            throw new IllegalArgumentException("windowMs 不能为负");
        }
        if (action == null) {
            throw new IllegalArgumentException("action 不能为空");
        }
        this.windowMs = windowMs;
        this.action = action;
    }

    /** 记录一次事件:重置防抖窗口,窗口结束后执行动作。 */
    public synchronized void trigger() {
        if (pending != null) {
            pending.cancel(false);
        }
        pending = exec.schedule(() -> {
            synchronized (this) {
                pending = null;
            }
            action.run();
        }, windowMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        exec.shutdownNow();
    }
}

package com.pnas.agent.watch;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 事件防抖器:窗口期内的连续 {@link #trigger()} 只执行一次动作。
 * 每次触发都取消待执行的定时任务并重新计时,因此突发事件最后落定 {@code windowMs} 后执行。
 * 线程安全;{@link #close()} 优雅关闭(等待在途动作结束),关闭后的 {@link #trigger()} 为空操作。
 */
public final class Debouncer implements AutoCloseable {

    /** 关闭时等待在途动作结束的宽限期。 */
    private static final long CLOSE_WAIT_SECONDS = 5L;

    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
    private final long windowMs;
    private final Runnable action;
    private ScheduledFuture<?> pending;
    private boolean closed;

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

    /** 记录一次事件:重置防抖窗口,窗口结束后执行动作;已关闭时忽略。 */
    public synchronized void trigger() {
        if (closed) {
            return;
        }
        if (pending != null) {
            pending.cancel(false);
        }
        pending = exec.schedule(() -> {
            synchronized (this) {
                if (closed) {
                    return;
                }
                pending = null;
            }
            action.run();
        }, windowMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 停止接受新事件:取消尚未执行的窗口,等待在途动作自然结束(最多
     * {@value #CLOSE_WAIT_SECONDS} 秒,超时才中断)。
     */
    @Override
    public void close() {
        ScheduledFuture<?> toCancel;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            toCancel = pending;
            pending = null;
        }
        if (toCancel != null) {
            toCancel.cancel(false);
        }
        exec.shutdown();
        try {
            if (!exec.awaitTermination(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS)) {
                exec.shutdownNow();
            }
        } catch (InterruptedException e) {
            exec.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

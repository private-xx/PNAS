package com.pnas.agent;

import com.pnas.agent.watch.Debouncer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebouncerTest {

    @Test
    void coalescesBurstIntoOneRun() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(1);
        try (Debouncer d = new Debouncer(100, () -> {
            runs.incrementAndGet();
            latch.countDown();
        })) {
            for (int i = 0; i < 5; i++) {
                d.trigger();
                Thread.sleep(20);
            }
            assertTrue(latch.await(1, TimeUnit.SECONDS), "防抖窗口结束后应执行一次动作");
            Thread.sleep(200);
            assertEquals(1, runs.get(), "连续触发只应合并为一次执行");
        }
    }
}

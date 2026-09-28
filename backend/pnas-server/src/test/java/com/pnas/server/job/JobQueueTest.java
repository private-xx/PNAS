package com.pnas.server.job;

import com.pnas.server.AbstractIntegrationTest;
import com.pnas.server.job.domain.Job;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** DB 任务队列:成功执行、失败重试至死信(ADR-09 / FR-SYS-01)。 */
class JobQueueTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class Handlers {
        @Bean
        JobHandler echoHandler() {
            return new JobHandler() {
                @Override public String type() { return "echo"; }
                @Override public Map<String, Object> run(Job job) { return job.getPayload(); }
            };
        }

        @Bean
        JobHandler boomHandler() {
            return new JobHandler() {
                @Override public String type() { return "boom"; }
                @Override public Map<String, Object> run(Job job) {
                    throw new IllegalStateException("boom");
                }
            };
        }
    }

    @Autowired JobService jobs;

    @Test
    void echoJobRunsAndWritesResult() {
        UUID id = jobs.enqueue("echo", Map.of("msg", "hi"));

        int executed = jobs.claimAndRunAllNow();

        assertThat(executed).isGreaterThanOrEqualTo(1);
        Job job = jobs.get(id).orElseThrow();
        assertThat(job.getState()).isEqualTo(Job.SUCCESS);
        assertThat(job.getResult()).containsEntry("msg", "hi");
        assertThat(job.getAttempt()).isEqualTo(1);
    }

    @Test
    void failingJobRetriesThenGoesDead() {
        UUID id = jobs.enqueue("boom", Map.of());

        jobs.forceRun(id); // 第 1 次失败 → FAILED 并退避
        Job afterFirst = jobs.get(id).orElseThrow();
        assertThat(afterFirst.getState()).isEqualTo(Job.FAILED);
        assertThat(afterFirst.getAttempt()).isEqualTo(1);
        assertThat(afterFirst.getError()).contains("boom");
        assertThat(afterFirst.getNextRunAt()).isAfter(java.time.Instant.now());

        jobs.forceRun(id); // 第 2 次
        jobs.forceRun(id); // 第 3 次 → 达到 maxAttempts,入 DEAD

        Job dead = jobs.get(id).orElseThrow();
        assertThat(dead.getState()).isEqualTo(Job.DEAD);
        assertThat(dead.getAttempt()).isEqualTo(3);
        assertThat(dead.getError()).contains("boom");
    }

    @Test
    void priorityAndDelayAreHonoured() {
        // 延迟任务:到期前不应被领取
        UUID delayed = jobs.enqueue("echo", Map.of("msg", "later"), 0, 3600);
        jobs.claimAndRunAllNow();
        assertThat(jobs.get(delayed).orElseThrow().getState()).isEqualTo(Job.QUEUED);

        // 优先级:同一轮内高优先级先执行(此处只断言两者都被执行且优先级写入正确)
        UUID low = jobs.enqueue("echo", Map.of("msg", "low"), 1, 0);
        UUID high = jobs.enqueue("echo", Map.of("msg", "high"), 9, 0);
        jobs.claimAndRunAllNow();
        assertThat(jobs.get(low).orElseThrow().getState()).isEqualTo(Job.SUCCESS);
        assertThat(jobs.get(high).orElseThrow().getState()).isEqualTo(Job.SUCCESS);
        assertThat(jobs.get(high).orElseThrow().getPriority()).isEqualTo(9);
    }

    @Test
    void retryRejectsNonTerminalStates() {
        UUID id = jobs.enqueue("echo", Map.of("msg", "x"));
        jobs.claimAndRunAllNow();
        assertThat(jobs.get(id).orElseThrow().getState()).isEqualTo(Job.SUCCESS);

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
            com.pnas.server.common.error.BusinessException.class, () -> jobs.retry(id)).getMessage())
            .contains("只有 FAILED/DEAD");
    }

    @Test
    void unregisteredTypeIsRejectedAtEnqueue() {
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
            com.pnas.server.common.error.BusinessException.class,
            () -> jobs.enqueue("no-such-handler", Map.of())).getMessage())
            .contains("未注册的任务类型");
    }
}

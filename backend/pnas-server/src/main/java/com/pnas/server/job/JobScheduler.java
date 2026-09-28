package com.pnas.server.job;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 周期调度:把到期任务交给 worker 执行(测试环境用 pnas.jobs.scheduler-enabled=false 关闭)。 */
@Component
@ConditionalOnProperty(name = "pnas.jobs.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class JobScheduler {

    private final JobService jobs;

    public JobScheduler(JobService jobs) {
        this.jobs = jobs;
    }

    @Scheduled(fixedDelayString = "${pnas.jobs.interval-ms:5000}")
    public void tick() {
        jobs.runDue();
    }
}

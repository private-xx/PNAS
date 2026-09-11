package com.pnas.server.job;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 启用 @Scheduled(架构 ADR-09 的任务调度)。 */
@Configuration
@EnableScheduling
public class JobSchedulingConfig {
}

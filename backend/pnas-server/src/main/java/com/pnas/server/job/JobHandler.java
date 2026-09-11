package com.pnas.server.job;

import com.pnas.server.job.domain.Job;

import java.util.Map;

/** 任务执行器:实现类按 {@link #type()} 注册到 {@link JobService};执行必须幂等。 */
public interface JobHandler {

    String type();

    /** 执行任务;返回值写入 job.result(可为 null)。抛异常表示失败,进入重试/死信。 */
    Map<String, Object> run(Job job) throws Exception;
}

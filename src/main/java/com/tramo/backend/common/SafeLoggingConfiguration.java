package com.tramo.backend.common;

import org.slf4j.LoggerFactory;
import org.springframework.boot.task.SimpleAsyncTaskSchedulerCustomizer;
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SafeLoggingConfiguration {
    @Bean
    public ThreadPoolTaskSchedulerCustomizer safeScheduledFailures() {
        return scheduler -> scheduler.setErrorHandler(failure -> SafeLog.failure(
                LoggerFactory.getLogger(SafeLoggingConfiguration.class), "scheduled_task_failed", "INTERNAL_ERROR", failure));
    }

    @Bean
    public SimpleAsyncTaskSchedulerCustomizer safeAsyncScheduledFailures() {
        return scheduler -> scheduler.setErrorHandler(failure -> SafeLog.failure(
                LoggerFactory.getLogger(SafeLoggingConfiguration.class), "scheduled_task_failed", "INTERNAL_ERROR", failure));
    }
}

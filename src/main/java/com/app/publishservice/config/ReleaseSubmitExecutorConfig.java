package com.app.publishservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class ReleaseSubmitExecutorConfig {

    /**
     * 处理发布提交 Executor相关逻辑。
     */
    @Bean("releaseSubmitExecutor")
    public Executor releaseSubmitExecutor() {
        int corePoolSize = Math.max(2, Math.min(Runtime.getRuntime().availableProcessors(), 8));
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(corePoolSize * 2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("release-submit-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}

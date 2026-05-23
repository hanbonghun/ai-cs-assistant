package com.aicsassistant.common.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * {@code @Async} 실행기 설정.
 *
 * <p>기본 {@code SimpleAsyncTaskExecutor}는 새 스레드를 무한정 생성하고 종료 시 대기하지 않아
 * 운영 환경에서 위험하다. 백그라운드 agent 호출이 한 번에 몰려도 폭주하지 않도록 bounded pool로
 * 교체하고, 앱 종료 시 진행 중인 작업이 끝날 때까지 기다리도록 설정한다.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("agent-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}

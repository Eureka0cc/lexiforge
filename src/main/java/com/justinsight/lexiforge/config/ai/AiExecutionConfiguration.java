package com.justinsight.lexiforge.config.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class AiExecutionConfiguration {
    @Bean(name = "translationExecutor", destroyMethod = "shutdownNow")
    ExecutorService translationExecutor(@Value("${app.ai.execution.concurrency:20}") int concurrency,
                                        @Value("${app.ai.execution.queue-capacity:40}") int capacity) {
        if (concurrency < 1 || capacity < 1) throw new IllegalArgumentException("AI 并发与队列必须为正数");
        AtomicInteger number = new AtomicInteger();
        return new ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(capacity), task -> {
                    Thread thread = new Thread(task, "translation-" + number.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }
}

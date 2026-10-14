package com.justinsight.lexiforge.config.ai;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiExecutionConfigurationTest {
    @Test
    void boundsRunningAndQueuedRequests() throws Exception {
        var executor = new AiExecutionConfiguration().translationExecutor(1, 1);
        var running = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            executor.submit(() -> {
                running.countDown();
                try { release.await(); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            });
            assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
            executor.submit(() -> {});
            assertThatThrownBy(() -> executor.submit(() -> {})).isInstanceOf(RejectedExecutionException.class);
        } finally { release.countDown(); executor.shutdownNow(); }
    }
}

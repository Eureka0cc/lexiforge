package com.justinsight.lexiforge.service.ai;

import com.justinsight.lexiforge.common.ApiException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;

/** 同步 AI 操作也使用与翻译流共用的有限执行资源。 */
@Component
public class AiTaskRunner {
    private final ExecutorService executor;
    private final long timeoutSeconds;
    public AiTaskRunner(@Qualifier("translationExecutor") ExecutorService executor,
                        @Value("${app.ai.execution.timeout-seconds:90}") long timeoutSeconds) {
        this.executor = executor;
        this.timeoutSeconds = timeoutSeconds;
    }
    public <T> T run(Callable<T> operation) {
        Future<T> future;
        try { future = executor.submit(operation); }
        catch (RejectedExecutionException exception) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_BUSY", "AI 请求较多，请稍后重试");
        }
        try { return future.get(timeoutSeconds, TimeUnit.SECONDS); }
        catch (TimeoutException exception) {
            future.cancel(true);
            throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "AI_TIMEOUT", "AI 处理超时，请稍后重试");
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_CANCELLED", "AI 请求已取消");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_FAILED", "AI 处理失败，请重试");
        }
    }
}

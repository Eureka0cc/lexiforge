package com.justinsight.lexiforge.common.id;

import com.justinsight.lexiforge.common.ApiException;
import com.justinsight.lexiforge.config.SnowflakeProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class SnowflakeIdGenerator {
    static final long EPOCH = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
    private static final long MAX_CLOCK_ROLLBACK_MS = 5;
    private final long nodeBits;
    private long lastTimestamp = -1;
    private long sequence;

    public SnowflakeIdGenerator(SnowflakeProperties properties) {
        this.nodeBits = (properties.datacenterId() << 5) | properties.workerId();
    }

    public synchronized long nextId() {
        long now = System.currentTimeMillis();
        if (now < lastTimestamp) {
            long rollback = lastTimestamp - now;
            if (rollback > MAX_CLOCK_ROLLBACK_MS) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ID_GENERATION_UNAVAILABLE", "系统时钟异常，请稍后重试");
            }
            while ((now = System.currentTimeMillis()) < lastTimestamp) {
                Thread.onSpinWait();
            }
        }
        if (now == lastTimestamp) {
            sequence = (sequence + 1) & 0xFFF;
            if (sequence == 0) {
                do { now = System.currentTimeMillis(); } while (now <= lastTimestamp);
            }
        } else {
            sequence = 0;
        }
        lastTimestamp = now;
        return ((now - EPOCH) << 22) | (nodeBits << 12) | sequence;
    }

    public static String external(long id) { return Long.toString(id); }
}

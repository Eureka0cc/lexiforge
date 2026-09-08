package com.justinsight.lexiforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.snowflake")
public record SnowflakeProperties(long datacenterId, long workerId) {
    public SnowflakeProperties {
        if (datacenterId < 0 || datacenterId > 31 || workerId < 0 || workerId > 31) {
            throw new IllegalArgumentException("Snowflake datacenter-id and worker-id must be between 0 and 31");
        }
    }
}

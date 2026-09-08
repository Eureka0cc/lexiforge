package com.justinsight.lexiforge.common.id;

import com.justinsight.lexiforge.config.SnowflakeProperties;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class SnowflakeIdGeneratorTest {
    @Test void generatesUniquePositiveIdsConcurrently() throws Exception {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(new SnowflakeProperties(2, 7));
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 20_000; i++) executor.submit(() -> ids.add(generator.nextId()));
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(ids).hasSize(20_000).allMatch(id -> id > 0);
    }

    @Test void rejectsInvalidNodeConfiguration() {
        assertThatThrownBy(() -> new SnowflakeProperties(32, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SnowflakeProperties(0, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void externalFormIsAStringWithoutPrecisionLoss() {
        long id = new SnowflakeIdGenerator(new SnowflakeProperties(0, 0)).nextId();
        assertThat(Long.parseLong(SnowflakeIdGenerator.external(id))).isEqualTo(id);
    }
}

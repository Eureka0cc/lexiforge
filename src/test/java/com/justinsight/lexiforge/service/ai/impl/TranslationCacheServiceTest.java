package com.justinsight.lexiforge.service.ai.impl;

import com.justinsight.lexiforge.config.ai.TranslationCacheConfiguration.Settings;
import com.justinsight.lexiforge.model.dto.TranslationDto;
import com.justinsight.lexiforge.model.entity.DomainEntity;
import com.justinsight.lexiforge.service.ai.provider.AiGateway;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TranslationCacheServiceTest {
    private final DomainEntity domain = domain();

    @Test
    void fingerprintSeparatesUsersContextsDomainsModelsAndGlossaries() {
        var cache = cache("model-a", "1");
        var request = request("address", null);
        String key = cache.fingerprint(1, request, domain, List.of());
        assertThat(key).hasSize(64);
        assertThat(cache.fingerprint(2, request, domain, List.of())).isNotEqualTo(key);
        assertThat(cache.fingerprint(1, request("address", "memory location"), domain, List.of())).isNotEqualTo(key);
        assertThat(cache.fingerprint(1, request("Address", null), domain, List.of())).isNotEqualTo(key);
        assertThat(cache("model-b", "1").fingerprint(1, request, domain, List.of())).isNotEqualTo(key);
        assertThat(cache("model-a", "2").fingerprint(1, request, domain, List.of())).isNotEqualTo(key);
        assertThat(cache.fingerprint(1, request, domain,
                List.of(new AiGateway.GlossaryTerm("address", "地址")))).isNotEqualTo(key);
        domain.description = "different domain description";
        assertThat(cache.fingerprint(1, request, domain, List.of())).isNotEqualTo(key);
    }

    @Test
    void forceRefreshDoesNotChangeContentIdentity() {
        var cache = cache("model", "1");
        var ordinary = request("word", null);
        var refresh = new TranslationDto.Request("word", "EN", "ZH", "10", null, false, true);
        assertThat(cache.fingerprint(1, ordinary, domain, List.of()))
                .isEqualTo(cache.fingerprint(1, refresh, domain, List.of()));
    }

    @Test
    void cacheExpiresEvictsLeastRecentlyUsedAndRemovesDeletedIds() {
        Clock clock = mock(Clock.class);
        Instant now = Instant.parse("2026-09-08T00:00:00Z");
        when(clock.instant()).thenReturn(now);
        var cache = new TranslationCacheServiceImpl(new ObjectMapper(),
                new Settings(2, Duration.ofMinutes(5), "1"), new MockEnvironment(), clock);
        cache.remember("a", 1);
        cache.remember("b", 2);
        assertThat(cache.find("a")).isEqualTo(1L);
        cache.remember("c", 3);
        assertThat(cache.find("b")).isNull();
        cache.evict(1);
        assertThat(cache.find("a")).isNull();
        when(clock.instant()).thenReturn(now.plusSeconds(300));
        assertThat(cache.find("c")).isNull();
    }

    private TranslationCacheServiceImpl cache(String model, String revision) {
        return new TranslationCacheServiceImpl(new ObjectMapper(), new Settings(500, Duration.ofMinutes(5), revision),
                new MockEnvironment().withProperty("spring.ai.openai.chat.options.model", model),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    }

    private TranslationDto.Request request(String text, String context) {
        return new TranslationDto.Request(text, "EN", "ZH", "10", context, false);
    }

    private static DomainEntity domain() {
        DomainEntity domain = new DomainEntity();
        domain.id = 10L;
        domain.name = "IT";
        domain.description = "Software";
        return domain;
    }
}

package com.justinsight.lexiforge.service;

import com.justinsight.lexiforge.common.id.SnowflakeIdGenerator;
import com.justinsight.lexiforge.config.ai.TranslationCacheConfiguration.Settings;
import com.justinsight.lexiforge.model.dto.TranslationDto;
import com.justinsight.lexiforge.model.entity.DomainEntity;
import com.justinsight.lexiforge.model.entity.TranslationEntity;
import com.justinsight.lexiforge.repository.DomainRepository;
import com.justinsight.lexiforge.repository.DomainTermRepository;
import com.justinsight.lexiforge.repository.TranslationRepository;
import com.justinsight.lexiforge.repository.TranslationTermRepository;
import com.justinsight.lexiforge.service.ai.TranslationCacheService;
import com.justinsight.lexiforge.service.ai.WordDictionaryService;
import com.justinsight.lexiforge.service.ai.impl.TranslationCacheServiceImpl;
import com.justinsight.lexiforge.service.ai.provider.AiGateway;
import com.justinsight.lexiforge.service.impl.TranslationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TranslationReuseTest {
    private final TranslationRepository translations = mock(TranslationRepository.class);
    private final TranslationTermRepository terms = mock(TranslationTermRepository.class);
    private final DomainRepository domains = mock(DomainRepository.class);
    private final DomainTermRepository domainTerms = mock(DomainTermRepository.class);
    private final AiGateway ai = mock(AiGateway.class);
    private final SnowflakeIdGenerator ids = mock(SnowflakeIdGenerator.class);
    private final EntitlementService entitlements = mock(EntitlementService.class);
    private final EventService events = mock(EventService.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final WordDictionaryService dictionary = mock(WordDictionaryService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final DomainEntity domain = new DomainEntity();
    private final TranslationDto.Request request = new TranslationDto.Request("address", "EN", "ZH", "10", null, false);
    private TranslationCacheService cache;
    private TranslationService service;
    private String fingerprint;

    @BeforeEach
    void setup() {
        domain.id = 10L;
        domain.name = "IT";
        domain.description = "Software";
        domain.builtIn = true;
        when(domains.findById(10L)).thenReturn(Optional.of(domain));
        when(domainTerms.findByDomainIdOrderBySourceTerm(10L)).thenReturn(List.of());
        when(terms.findByTranslationId(anyLong())).thenReturn(List.of());
        when(ai.available()).thenReturn(true);
        when(ai.translateStream(any(), any())).thenReturn(new AiGateway.TranslationResult("en", "地址", List.of(), List.of()));
        when(ids.nextId()).thenReturn(100L);
        when(manager.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());
        cache = newCache();
        fingerprint = cache.fingerprint(1, request, domain, List.of());
        service = newService(cache);
    }

    @Test
    void databaseHitWorksAfterRestartWithoutAiOrQuotaOrDuplicateHistory() {
        var saved = saved(20);
        when(translations.findFirstByUserIdAndRequestFingerprintOrderByCreatedAtDescIdDesc(1L, fingerprint))
                .thenReturn(Optional.of(saved));
        when(ai.available()).thenReturn(false);
        assertThat(service.translate(1, request).id()).isEqualTo("20");
        assertThat(newService(newCache()).translate(1, request).id()).isEqualTo("20");
        verify(ai, never()).translateStream(any(), any());
        verify(entitlements, never()).consume(anyLong(), any(), any());
        verify(translations, never()).save(any());
    }

    @Test
    void memoryHitChecksOwnershipAndDoesNotQueryByFingerprint() {
        cache.remember(fingerprint, 20);
        when(translations.findByIdAndUserId(20L, 1L)).thenReturn(Optional.of(saved(20)));
        assertThat(service.translate(1, request).id()).isEqualTo("20");
        verify(translations, never()).findFirstByUserIdAndRequestFingerprintOrderByCreatedAtDescIdDesc(anyLong(), any());
        verify(ai, never()).translateStream(any(), any());
    }

    @Test
    void forceRefreshGeneratesAndReplacesCacheOnlyAfterCommit() {
        cache.remember(fingerprint, 20);
        var refresh = new TranslationDto.Request("address", "EN", "ZH", "10", null, false, true);
        assertThat(service.translate(1, refresh).id()).isEqualTo("100");
        verify(ai).translateStream(any(), any());
        verify(entitlements).consume(eq(1L), eq(EntitlementService.UsageType.TRANSLATION), any());
        verify(manager).commit(any());
        assertThat(cache.find(fingerprint)).isEqualTo(100L);
    }

    @Test
    void modelFailureRefundsAndPreservesPreviousCache() {
        cache.remember(fingerprint, 20);
        when(ai.translateStream(any(), any())).thenThrow(new IllegalStateException("upstream failed"));
        var refresh = new TranslationDto.Request("address", "EN", "ZH", "10", null, false, true);
        assertThatThrownBy(() -> service.translate(1, refresh)).hasMessage("upstream failed");
        verify(entitlements).release(eq(1L), eq(EntitlementService.UsageType.TRANSLATION), any());
        verify(translations, never()).save(any());
        assertThat(cache.find(fingerprint)).isEqualTo(20L);
    }

    @Test
    void persistenceFailureDoesNotCacheAndRefunds() {
        when(translations.save(any())).thenThrow(new IllegalStateException("database failed"));
        assertThatThrownBy(() -> service.translate(1, request)).hasMessage("database failed");
        verify(manager).rollback(any());
        verify(entitlements).release(eq(1L), eq(EntitlementService.UsageType.TRANSLATION), any());
        assertThat(cache.find(fingerprint)).isNull();
    }

    @Test
    void deletedRecordIsNotReusedAndUnauthorizedDomainCannotHitCache() {
        cache.remember(fingerprint, 20);
        assertThat(service.translate(1, request).id()).isEqualTo("100");
        domain.builtIn = false;
        domain.ownerId = 2L;
        assertThatThrownBy(() -> service.translate(1, request)).hasMessage("领域不存在");
        verify(ai, times(1)).translateStream(any(), any());
    }

    @Test
    void simultaneousIdenticalRequestsReuseFirstSuccessfulResult() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(ai.translateStream(any(), any())).thenAnswer(ignored -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
            return new AiGateway.TranslationResult("en", "地址", List.of(), List.of());
        });
        when(translations.findByIdAndUserId(100L, 1L)).thenReturn(Optional.of(saved(100)));
        var first = CompletableFuture.supplyAsync(() -> service.translate(1, request));
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = CompletableFuture.supplyAsync(() -> service.translate(1, request));
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).id()).isEqualTo(second.get(5, TimeUnit.SECONDS).id());
            verify(ai, times(1)).translateStream(any(), any());
        } finally {
            release.countDown();
        }
    }

    @Test
    void sharedDictionaryCannotReplaceContextualModelTranslation() {
        when(dictionary.lookup(any(), any(), any())).thenReturn(Optional.of(dictHit()));
        TranslationDto.View view = service.translate(1, request);
        assertThat(view.translatedText()).isEqualTo("地址");
        assertThat(view.id()).isEqualTo("100");
        verify(ai).translateStream(any(), any());
        verify(entitlements).consume(anyLong(), any(), any());
        verify(translations).save(any());
        verify(events).track(eq(1L), eq("TRANSLATION_COMPLETED"), any());
        verify(dictionary, never()).lookup(any(), any(), any());
        verify(dictionary, never()).registerHit(anyLong());
        verify(dictionary, never()).admit(any(), any(), any(), any());
        assertThat(cache.find(fingerprint)).isEqualTo(100L);
    }

    @Test
    void dictionaryHitIsThenServedByFingerprintOnRepeat() {
        when(dictionary.lookup(any(), any(), any())).thenReturn(Optional.of(dictHit()));
        when(translations.findByIdAndUserId(100L, 1L)).thenReturn(Optional.of(saved(100)));
        assertThat(service.translate(1, request).id()).isEqualTo("100");
        assertThat(service.translate(1, request).id()).isEqualTo("100");
        verify(dictionary, never()).lookup(any(), any(), any());
        verify(translations, times(1)).save(any());
    }

    @Test
    void registerHitFailureDoesNotBreakDictionaryHit() {
        when(dictionary.lookup(any(), any(), any())).thenReturn(Optional.of(dictHit()));
        doThrow(new IllegalStateException("counter failed")).when(dictionary).registerHit(5L);
        assertThat(service.translate(1, request).translatedText()).isEqualTo("地址");
    }

    @Test
    void forceRefreshDoesNotShareUserGeneratedTranslation() {
        when(dictionary.lookup(any(), any(), any())).thenReturn(Optional.of(dictHit()));
        var refresh = new TranslationDto.Request("address", "EN", "ZH", "10", null, false, true);
        assertThat(service.translate(1, refresh).id()).isEqualTo("100");
        verify(ai).translateStream(any(), any());
        verify(dictionary, never()).registerHit(anyLong());
        verify(dictionary, never()).admit(any(), any(), any(), any());
    }

    @Test
    void admissionFailureDoesNotFailTranslation() {
        doThrow(new IllegalStateException("admission failed")).when(dictionary).admit(any(), any(), any(), any());
        assertThat(service.translate(1, request).translatedText()).isEqualTo("地址");
        verify(entitlements, never()).release(anyLong(), any(), any());
    }

    @Test
    void defaultTranslationDoesNotReadOrInjectHardcodedGlossary() {
        service.translate(1, request);
        verify(domainTerms, never()).findByDomainIdOrderBySourceTerm(anyLong());
        verify(ai).translateStream(org.mockito.ArgumentMatchers.argThat(prompt -> prompt.glossary().isEmpty()), any());
    }

    private WordDictionaryService.DictionaryHit dictHit() {
        return new WordDictionaryService.DictionaryHit(5L, "address", "EN", "地址", "en", List.of(), "test-model");
    }

    private TranslationEntity saved(long id) {
        TranslationEntity saved = new TranslationEntity();
        saved.id = id;
        saved.userId = 1L;
        saved.domainId = 10L;
        saved.sourceText = "address";
        saved.translatedText = "地址";
        saved.requestFingerprint = fingerprint;
        return saved;
    }

    private TranslationCacheService newCache() {
        return new TranslationCacheServiceImpl(mapper, new Settings(500, Duration.ofMinutes(5), "1"), new MockEnvironment());
    }

    private TranslationService newService(TranslationCacheService value) {
        return new TranslationServiceImpl(translations, terms, domains, domainTerms, ai, ids,
                entitlements, events, mapper, value, dictionary, manager);
    }
}

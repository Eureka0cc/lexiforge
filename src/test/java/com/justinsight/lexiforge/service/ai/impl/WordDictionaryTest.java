package com.justinsight.lexiforge.service.ai.impl;

import java.util.List;
import java.util.Optional;

import com.justinsight.lexiforge.common.id.SnowflakeIdGenerator;
import com.justinsight.lexiforge.config.ai.WordDictionaryConfiguration.Settings;
import com.justinsight.lexiforge.model.dto.TranslationDto;
import com.justinsight.lexiforge.model.entity.DomainEntity;
import com.justinsight.lexiforge.model.entity.TranslationEntity;
import com.justinsight.lexiforge.model.entity.WordDictionaryEntity;
import com.justinsight.lexiforge.repository.WordDictionaryRepository;
import com.justinsight.lexiforge.service.ai.WordDictionaryService;
import com.justinsight.lexiforge.service.ai.provider.AiGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WordDictionaryTest {
    private final WordDictionaryRepository repository = mock(WordDictionaryRepository.class);
    private final SnowflakeIdGenerator ids = mock(SnowflakeIdGenerator.class);
    private final DomainEntity domain = new DomainEntity();
    private final List<AiGateway.GlossaryTerm> noGlossary = List.of();

    private WordDictionaryServiceImpl service;

    @BeforeEach
    void setup() {
        domain.id = 10L;
        domain.name = "IT";
        domain.builtIn = true;
        when(ids.nextId()).thenReturn(555L);
        service = new WordDictionaryServiceImpl(repository, new Settings(true, 2), ids);
    }

    @Test
    void normalizationTrimsLowercasesAndCollapsesWhitespace() {
        assertThat(WordDictionaryServiceImpl.normalize("  Hello   World ")).isEqualTo("hello world");
        assertThat(WordDictionaryServiceImpl.normalize("Address")).isEqualTo("address");
    }

    @Test
    void scriptHeuristicResolvesEnZhAndRejectsMixed() {
        assertThat(WordDictionaryServiceImpl.resolveScriptLanguage("address")).isEqualTo("EN");
        assertThat(WordDictionaryServiceImpl.resolveScriptLanguage("依赖注入")).isEqualTo("ZH");
        assertThat(WordDictionaryServiceImpl.resolveScriptLanguage("AI驱动")).isNull();
        assertThat(WordDictionaryServiceImpl.resolveScriptLanguage("404")).isNull();
        assertThat(WordDictionaryServiceImpl.resolveScriptLanguage("oauth2")).isEqualTo("EN");
    }

    @Test
    void lookupRejectsIneligibleRequests() {
        var customDomain = new DomainEntity();
        customDomain.id = 10L;
        customDomain.builtIn = false;
        assertThat(service.lookup(request("address", "EN", "ZH", false, false), customDomain, noGlossary))
                .isEmpty();
        assertThat(service.lookup(request("address", "EN", "ZH", true, false), domain, noGlossary))
                .isEmpty();
        assertThat(service.lookup(request("address", "EN", "ZH", false, true), domain, noGlossary))
                .isEmpty();
        assertThat(service.lookup(request("address", "EN", "ZH", false, false), domain,
                List.of(new AiGateway.GlossaryTerm("address", "寻址")))).isEmpty();
        assertThat(service.lookup(request("this is a much longer balanced sentence", "EN", "ZH", false, false),
                domain, noGlossary)).isEmpty();
        assertThat(service.lookup(new TranslationDto.Request("address", "EN", "ZH", "10", "in a letter", false, false),
                domain, noGlossary)).isEmpty();
        var disabled = new WordDictionaryServiceImpl(repository, new Settings(false, 2), ids);
        assertThat(disabled.lookup(request("address", "EN", "ZH", false, false), domain, noGlossary))
                .isEmpty();
        verify(repository, never()).findByWordAndSourceLanguageAndTargetLanguageAndDomainId(
                anyString(), anyString(), anyString(), any());
    }

    @Test
    void lookupResolvesAutoToScriptLanguage() {
        when(repository.findByWordAndSourceLanguageAndTargetLanguageAndDomainId(
                eq("address"), eq("EN"), eq("ZH"), eq(10L))).thenReturn(Optional.of(entry("地址")));
        var hit = service.lookup(request("address", "AUTO", "ZH", false, false), domain, noGlossary);
        assertThat(hit).isPresent();
        assertThat(hit.orElseThrow().translatedText()).isEqualTo("地址");
        assertThat(hit.orElseThrow().alternatives()).isEqualTo(List.of());
    }

    @Test
    void lookupSkipsWhenResolvedLanguageEqualsTarget() {
        assertThat(service.lookup(request("address", "AUTO", "EN", false, false), domain, noGlossary))
                .isEmpty();
        verify(repository, never()).findByWordAndSourceLanguageAndTargetLanguageAndDomainId(
                anyString(), anyString(), anyString(), any());
    }

    @Test
    void admitInsertsWhenDistinctUsersReachThreshold() {
        when(repository.countDistinctRequesters(10L, "EN", "ZH", "address")).thenReturn(2L);
        when(repository.findByWordAndSourceLanguageAndTargetLanguageAndDomainId(
                eq("address"), eq("EN"), eq("ZH"), eq(10L))).thenReturn(Optional.empty());
        service.admit(request("address", "EN", "ZH", false, false), domain, noGlossary, savedTranslation());
        ArgumentCaptor<WordDictionaryEntity> captor = ArgumentCaptor.forClass(WordDictionaryEntity.class);
        verify(repository).saveAndFlush(captor.capture());
        WordDictionaryEntity admitted = captor.getValue();
        assertThat(admitted.id).isEqualTo(555L);
        assertThat(admitted.word).isEqualTo("address");
        assertThat(admitted.sourceLanguage).isEqualTo("EN");
        assertThat(admitted.targetLanguage).isEqualTo("ZH");
        assertThat(admitted.domainId).isEqualTo(10L);
        assertThat(admitted.translatedText).isEqualTo("地址");
        assertThat(admitted.detectedLanguage).isEqualTo("EN");
        assertThat(admitted.contributorUserId).isEqualTo(1L);
    }

    @Test
    void admitBelowThresholdDoesNotInsert() {
        when(repository.countDistinctRequesters(10L, "EN", "ZH", "address")).thenReturn(1L);
        service.admit(request("address", "EN", "ZH", false, false), domain, noGlossary, savedTranslation());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void admitDoesNotOverwriteExistingEntry() {
        when(repository.countDistinctRequesters(10L, "EN", "ZH", "address")).thenReturn(5L);
        when(repository.findByWordAndSourceLanguageAndTargetLanguageAndDomainId(
                eq("address"), eq("EN"), eq("ZH"), eq(10L))).thenReturn(Optional.of(entry("旧译")));
        service.admit(request("address", "EN", "ZH", false, false), domain, noGlossary, savedTranslation());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void admitUniqueViolationMeansConcurrentWinner() {
        when(repository.countDistinctRequesters(10L, "EN", "ZH", "address")).thenReturn(2L);
        when(repository.findByWordAndSourceLanguageAndTargetLanguageAndDomainId(
                eq("address"), eq("EN"), eq("ZH"), eq(10L))).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));
        service.admit(request("address", "EN", "ZH", false, false), domain, noGlossary, savedTranslation());
        // No exception propagated: the concurrent winner owns the entry.
    }

    @Test
    void admitSwallowsRepositoryFailure() {
        when(repository.countDistinctRequesters(anyLong(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("database down"));
        service.admit(request("address", "EN", "ZH", false, false), domain, noGlossary, savedTranslation());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void admitSkipsContextOrGlossaryOrCustomDomainTranslations() {
        TranslationEntity withContext = savedTranslation();
        withContext.context = "in a network config";
        service.admit(request("address", "EN", "ZH", false, false), domain, noGlossary, withContext);
        service.admit(request("address", "EN", "ZH", false, false), domain,
                List.of(new AiGateway.GlossaryTerm("address", "寻址")), savedTranslation());
        DomainEntity custom = new DomainEntity();
        custom.id = 10L;
        custom.builtIn = false;
        service.admit(request("address", "EN", "ZH", false, false), custom, noGlossary, savedTranslation());
        verify(repository, never()).countDistinctRequesters(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void registerHitSwallowsFailure() {
        doThrow(new IllegalStateException("database down")).when(repository)
                .incrementHitCount(anyLong(), any());
        service.registerHit(5L);
        // No exception propagated.
    }

    private TranslationDto.Request request(String text, String source, String target,
                                           boolean includeTerms, boolean forceRefresh) {
        return new TranslationDto.Request(text, source, target, "10", null, includeTerms, forceRefresh);
    }

    private TranslationEntity savedTranslation() {
        TranslationEntity saved = new TranslationEntity();
        saved.userId = 1L;
        saved.domainId = 10L;
        saved.sourceText = "address";
        saved.targetLanguage = "ZH";
        saved.translatedText = "地址";
        saved.detectedLanguage = "EN";
        saved.alternativesJson = "[]";
        return saved;
    }

    private WordDictionaryEntity entry(String translatedText) {
        WordDictionaryEntity entry = new WordDictionaryEntity();
        entry.id = 5L;
        entry.word = "address";
        entry.sourceLanguage = "EN";
        entry.targetLanguage = "ZH";
        entry.domainId = 10L;
        entry.translatedText = translatedText;
        entry.detectedLanguage = "EN";
        entry.alternativesJson = "[]";
        entry.modelVersion = "test-model";
        return entry;
    }
}

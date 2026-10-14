package com.justinsight.lexiforge.service.ai.provider;

import com.justinsight.lexiforge.service.ai.TranslationRequestStrategy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpringAiGatewayJsonTest {

    @Test
    void keepsSingleWordTranslationsSmallAndFast() {
        var strategy = TranslationRequestStrategy.select("address");
        assertThat(strategy.kind()).isEqualTo(TranslationRequestStrategy.Kind.FAST);
        assertThat(strategy.maxOutputTokens()).isEqualTo(896);
        assertThat(strategy.termLimit()).isEqualTo(1);
    }

    @Test
    void allowsMoreOutputForDocuments() {
        var strategy = TranslationRequestStrategy.select("a".repeat(801));
        assertThat(strategy.kind()).isEqualTo(TranslationRequestStrategy.Kind.DOCUMENT);
        assertThat(strategy.maxOutputTokens()).isGreaterThan(1536);
        assertThat(strategy.termLimit()).isEqualTo(8);
    }

    @Test
    void translationOnlyDoesNotBudgetForLearningContent() {
        var strategy = TranslationRequestStrategy.select("an unfamiliar word", false);
        assertThat(strategy.termLimit()).isZero();
        assertThat(strategy.maxOutputTokens()).isEqualTo(640);
    }

    @Test
    void maximumLengthChineseInputHasRoomForCompleteTranslation() {
        var strategy = TranslationRequestStrategy.select("文".repeat(5000), false);
        assertThat(strategy.maxOutputTokens()).isGreaterThanOrEqualTo(10000);
        assertThat(strategy.termLimit()).isZero();
    }
}

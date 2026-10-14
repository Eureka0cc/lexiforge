package com.justinsight.lexiforge.service.ai.provider;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * AI 翻译回复的 JSON 线格式。模型可能漂移字段名（如 translation/text），容错集中在这里；
 * 解析后经 {@link #toResult()} 映射为内部领域模型 {@link AiGateway.TranslationResult}。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TranslationJson(
        @JsonPropertyDescription("检测到的源语言，如 zh、en")
        @JsonAlias({"sourceLanguage", "language"}) String detectedLanguage,
        @JsonPropertyDescription("自然流畅的译文；只给当前语境下最合适的一种表达")
        @JsonAlias({"translation", "text", "result"}) String translatedText,
        @JsonPropertyDescription("仅当输入确实缺少语境且存在歧义时给出，最多两项")
        List<AiGateway.TranslationAlternative> alternatives,
        @JsonPropertyDescription("原文中实际出现、值得学习的重点术语列表")
        List<AiGateway.TranslationTerm> terms) {

    public AiGateway.TranslationResult toResult() {
        return new AiGateway.TranslationResult(detectedLanguage, translatedText,
                alternatives == null ? List.of() : alternatives.stream().limit(2).toList(),
                List.of());
    }
}

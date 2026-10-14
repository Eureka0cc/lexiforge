package com.justinsight.lexiforge.service.ai.provider;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;
import java.util.function.Consumer;

public interface AiGateway {
    default boolean available() {
        return true;
    }

    TranslationResult translate(TranslationPrompt prompt);

    default TranslationResult translateStream(TranslationPrompt prompt, Consumer<String> deltaConsumer) {
        TranslationResult result = translate(prompt);
        deltaConsumer.accept(result.translatedText());
        return result;
    }

    LookupResult lookup(LookupPrompt prompt);

    LookupDetailsResult lookupDetails(LookupDetailsPrompt prompt);

    GradeResult grade(GradePrompt prompt);

    MnemonicResult mnemonic(MnemonicPrompt prompt);

    default com.justinsight.lexiforge.model.dto.SceneDto.Generated generateScene(String description) {
        throw new UnsupportedOperationException("当前 AI 服务未实现场景生成");
    }

    record GlossaryTerm(String sourceTerm, String targetTerm) {
    }

    record TranslationPrompt(String sourceText, String sourceLanguage, String targetLanguage, String domainName,
                             String domainDescription, List<GlossaryTerm> glossary,
                             String context, boolean includeTerms,
                             com.justinsight.lexiforge.model.dto.SceneDto.Config sceneConfiguration) {
        public TranslationPrompt(String sourceText, String sourceLanguage, String targetLanguage, String domainName,
                                 String domainDescription, List<GlossaryTerm> glossary, String context, boolean includeTerms) {
            this(sourceText, sourceLanguage, targetLanguage, domainName, domainDescription, glossary, context, includeTerms, null);
        }
        public TranslationPrompt(String sourceText, String sourceLanguage, String targetLanguage,
                                 String domainName, String domainDescription, List<GlossaryTerm> glossary) {
            this(sourceText, sourceLanguage, targetLanguage, domainName, domainDescription, glossary, "", false, null);
        }
    }

    record TranslationTerm(
            @JsonPropertyDescription("术语的原文形式，必须与原文中实际出现的写法一致") String sourceTerm,
            @JsonPropertyDescription("术语在原文语境下的译文；术语表中的用法匹配时保持一致") String targetTerm,
            @JsonPropertyDescription("词性缩写，如 n.、v.、adj.；非英文术语可为空字符串") String partOfSpeech,
            @JsonPropertyDescription("国际音标，只为英文词提供，其他语言为空字符串") String ipa,
            @JsonPropertyDescription("该术语在当前语境下的简要中文解释，一到两句话") String explanation,
            @JsonPropertyDescription("术语所在的原文短语或短句，用于定位该术语出现的语境") String sourceContext) {
    }

    record TranslationResult(
            @JsonPropertyDescription("检测到的源语言，如 zh、en") String detectedLanguage,
            @JsonPropertyDescription("自然流畅的译文；只给当前语境下最合适的一种表达") String translatedText,
            @JsonPropertyDescription("仅当输入确实缺少语境且存在歧义时给出，最多两项") List<TranslationAlternative> alternatives,
            @JsonPropertyDescription("原文中实际出现、值得学习的重点术语列表") List<TranslationTerm> terms) {
    }

    record TranslationAlternative(String meaning, String note) {
    }

    record LookupPrompt(String sourceText, String translatedText, String side, String selectedText,
                        String paragraph, int start, int end) {
    }

    record LookupResult(
            @JsonPropertyDescription("所选内容在当前双语语境中的中文含义") String meaning,
            @JsonPropertyDescription("一到两句简短中文说明") String explanation,
            @JsonPropertyDescription("可靠时返回另一侧的对应原文，否则为空字符串") String counterpartText,
            @JsonPropertyDescription("可收藏到英语词库的英文词或短语，否则为空字符串") String learningWord,
            @JsonPropertyDescription("与学习词对应的当前语境中文义，否则为空字符串") String chineseMeaning) {
    }

    record LookupDetailsPrompt(String sourceText, String translatedText, String selectedText,
                               String meaning, String learningWord) {
    }

    record LookupDetailsResult(
            @JsonPropertyDescription("仅为英文学习词提供国际音标，否则为空字符串") String ipa,
            @JsonPropertyDescription("简短词性，否则为空字符串") String partOfSpeech,
            @JsonPropertyDescription("一条符合当前语境的例句") String exampleSentence,
            @JsonPropertyDescription("例句的中文释义") String exampleTranslation) {
    }

    record GradePrompt(String direction, String word, String expectedMeaning, String answer, String partOfSpeech,
                       String context) {
    }

    record GradeResult(
            @JsonPropertyDescription("判定结果，只能是 CORRECT、PARTIAL、WRONG 之一") String result,
            @JsonPropertyDescription("针对学习者答案的简短中文反馈") String feedback,
            @JsonPropertyDescription("若答案显然对应另一个英语单词，返回该单词；否则返回空字符串") String confusedWith) {
    }

    record MnemonicPrompt(List<String> words, String style, String mistakeContext) {
    }

    record MnemonicResult(
            @JsonPropertyDescription("一句朗朗上口的记忆口诀") String slogan,
            @JsonPropertyDescription("基于字形的视觉联想") String visualHook,
            @JsonPropertyDescription("面向程序员的信息技术类比") String itAnalogy,
            @JsonPropertyDescription("能体现两词区别的对比例句") String contrastExample,
            @JsonPropertyDescription("一个能快速回想起词义的回忆钩子") String recallHook) {
    }
}

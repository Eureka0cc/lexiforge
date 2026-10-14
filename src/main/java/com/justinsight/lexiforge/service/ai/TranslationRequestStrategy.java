package com.justinsight.lexiforge.service.ai;

public final class TranslationRequestStrategy {
    /**
     * Reasoning models burn completion tokens on hidden reasoning before emitting the
     * JSON body (reasoning_tokens counts toward max_tokens), so every plan needs extra
     * headroom or the JSON gets truncated mid-stream (finish_reason=length).
     */
    private static final int REASONING_HEADROOM = 512;

    private TranslationRequestStrategy() {
    }

    public static Plan select(String sourceText) {
        return select(sourceText, true);
    }

    public static Plan select(String sourceText, boolean includeTerms) {
        String text = sourceText == null ? "" : sourceText.trim();
        int words = text.isEmpty() ? 0 : text.split("\\s+").length;
        if (text.length() <= 40 && words <= 4) {
            return new Plan(Kind.FAST, REASONING_HEADROOM + (includeTerms ? 384 : 128), includeTerms ? 1 : 0,
                    "输入很短，简洁翻译；没有上下文时不要凭空假定词性或具体使用场景。");
        }
        if (text.length() <= 800) {
            return new Plan(Kind.BALANCED, REASONING_HEADROOM
                    + Math.max(768, text.length() * 2 + (includeTerms ? 1024 : 256)), includeTerms ? 5 : 0,
                    "准确翻译整段内容，保持上下文连贯，术语解释简洁。");
        }
        return new Plan(Kind.DOCUMENT, REASONING_HEADROOM + text.length() * 2 + (includeTerms ? 2048 : 256), includeTerms ? 8 : 0,
                "完整翻译文档，保留段落和代码结构，保持全文术语一致。");
    }

    public enum Kind { FAST, BALANCED, DOCUMENT }

    public record Plan(Kind kind, int maxOutputTokens, int termLimit, String instruction) {
    }
}

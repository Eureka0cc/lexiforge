package com.justinsight.lexiforge.service.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonStringFieldDecoderTest {
    @Test
    void handlesEscapesUnicodeAndArbitraryBoundaries() {
        var decoder = new JsonStringFieldDecoder("translatedText");
        StringBuilder value = new StringBuilder();
        for (String chunk : new String[]{"{\"translated", "Text\":\"你\\", "n好\\u", "0021\",\"alternatives\":[]}"}) {
            value.append(decoder.accept(chunk));
        }
        assertThat(value.toString()).isEqualTo("你\n好!");
    }

    @Test
    void neverEmitsOtherJsonFields() {
        var decoder = new JsonStringFieldDecoder("translatedText");
        assertThat(decoder.accept("{\"detectedLanguage\":\"en\",")) .isEmpty();
        assertThat(decoder.accept("\"translatedText\":\"ok\"}")) .isEqualTo("ok");
    }

    @Test
    void ignoresNestedKeysAndKeyNamesInsideStringsAtEverySplit() {
        String json = "{\"note\":\"\\\"translatedText\\\":\\\"fake\\\"\",\"nested\":{\"translatedText\":\"wrong\"},\"translatedText\":\"你好\\n世界\\uD83D\\uDE80\"}";
        for (int split = 0; split <= json.length(); split++) {
            var decoder = new JsonStringFieldDecoder("translatedText");
            String output = decoder.accept(json.substring(0, split)) + decoder.accept(json.substring(split));
            assertThat(output).as("split %s", split).isEqualTo("你好\n世界🚀");
        }
    }

    @Test
    void holdsHighSurrogateUntilCompleteCharacterAndRejectsDuplicateField() {
        var decoder = new JsonStringFieldDecoder("translatedText");
        assertThat(decoder.accept("{\"translatedText\":\"\\uD83D")).isEmpty();
        assertThat(decoder.accept("\\uDE80\"")).isEqualTo("🚀");
        assertThatThrownBy(() -> decoder.accept(",\"translatedText\":\"different\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidEscapesInsteadOfPublishingCorruptedText() {
        var decoder = new JsonStringFieldDecoder("translatedText");
        assertThatThrownBy(() -> decoder.accept("{\"translatedText\":\"\\q\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

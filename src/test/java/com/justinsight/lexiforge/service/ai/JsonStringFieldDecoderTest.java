package com.justinsight.lexiforge.service.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
}

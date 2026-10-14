package com.justinsight.lexiforge.service.ai;

/** 单次扫描增量 JSON，仅暴露顶层指定字符串，保持转义与 Unicode 字符完整。 */
public final class JsonStringFieldDecoder {
    private final String field;
    private final StringBuilder token = new StringBuilder();
    private int depth;
    private boolean inString, escape, expectingKey, expectingValue, keyToken, capture, captured;
    private int unicodeDigits, unicodeValue;
    private String key;
    private char highSurrogate;

    public JsonStringFieldDecoder(String field) { this.field = field; }

    public String accept(String chunk) {
        StringBuilder delta = new StringBuilder();
        if (chunk == null) return "";
        for (int index = 0; index < chunk.length(); index++) {
            char ch = chunk.charAt(index);
            if (inString) {
                if (unicodeDigits > 0) {
                    int digit = Character.digit(ch, 16);
                    if (digit < 0) throw new IllegalArgumentException("无效 Unicode 转义");
                    unicodeValue = unicodeValue * 16 + digit;
                    if (--unicodeDigits == 0) append((char) unicodeValue, delta);
                } else if (escape) {
                    escape = false;
                    switch (ch) {
                        case 'u' -> { unicodeDigits = 4; unicodeValue = 0; }
                        case '"', '\\', '/' -> append(ch, delta);
                        case 'n' -> append('\n', delta);
                        case 'r' -> append('\r', delta);
                        case 't' -> append('\t', delta);
                        case 'b' -> append('\b', delta);
                        case 'f' -> append('\f', delta);
                        default -> throw new IllegalArgumentException("无效 JSON 转义");
                    }
                } else if (ch == '\\') {
                    escape = true;
                } else if (ch == '"') {
                    if (highSurrogate != 0) throw new IllegalArgumentException("不完整 Unicode 字符");
                    inString = false;
                    if (keyToken) { key = token.toString(); expectingKey = false; }
                    else if (depth == 1) expectingValue = false;
                    if (capture) captured = true;
                    capture = false;
                } else {
                    if (ch < 0x20) throw new IllegalArgumentException("未转义的控制字符");
                    append(ch, delta);
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
                keyToken = depth == 1 && expectingKey;
                capture = depth == 1 && expectingValue && field.equals(key);
                if (capture && captured) throw new IllegalArgumentException("重复译文字段");
                token.setLength(0);
            } else if (ch == '{' || ch == '[') {
                depth++;
                if (depth == 1) expectingKey = true;
            } else if (ch == '}' || ch == ']') {
                depth--;
                if (depth < 0) throw new IllegalArgumentException("无效 JSON 结构");
            } else if (depth == 1 && ch == ':') {
                expectingValue = true;
            } else if (depth == 1 && ch == ',') {
                expectingKey = true;
                expectingValue = false;
                key = null;
            }
        }
        return delta.toString();
    }

    private void append(char ch, StringBuilder delta) {
        if (keyToken) { token.append(ch); return; }
        if (!capture) return;
        if (highSurrogate != 0) {
            if (!Character.isLowSurrogate(ch)) throw new IllegalArgumentException("无效 Unicode 字符");
            delta.append(highSurrogate).append(ch);
            highSurrogate = 0;
        } else if (Character.isHighSurrogate(ch)) {
            highSurrogate = ch;
        } else {
            if (Character.isLowSurrogate(ch)) throw new IllegalArgumentException("无效 Unicode 字符");
            delta.append(ch);
        }
    }
}

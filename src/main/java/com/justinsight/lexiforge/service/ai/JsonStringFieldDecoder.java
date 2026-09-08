package com.justinsight.lexiforge.service.ai;

/** Incrementally exposes only complete decoded characters from one JSON string field. */
public final class JsonStringFieldDecoder {
    private final String field;
    private final StringBuilder json = new StringBuilder();
    private int emitted;

    public JsonStringFieldDecoder(String field) {
        this.field = field;
    }

    public String accept(String chunk) {
        if (chunk != null) json.append(chunk);
        String decoded = decodeAvailable(json, field);
        if (decoded.length() <= emitted) return "";
        String delta = decoded.substring(emitted);
        emitted = decoded.length();
        return delta;
    }

    private static String decodeAvailable(CharSequence json, String field) {
        String needle = "\"" + field + "\"";
        int key = json.toString().indexOf(needle);
        if (key < 0) return "";
        int cursor = key + needle.length();
        while (cursor < json.length() && Character.isWhitespace(json.charAt(cursor))) cursor++;
        if (cursor >= json.length() || json.charAt(cursor++) != ':') return "";
        while (cursor < json.length() && Character.isWhitespace(json.charAt(cursor))) cursor++;
        if (cursor >= json.length() || json.charAt(cursor++) != '"') return "";
        StringBuilder value = new StringBuilder();
        while (cursor < json.length()) {
            char current = json.charAt(cursor++);
            if (current == '"') break;
            if (current != '\\') {
                value.append(current);
                continue;
            }
            if (cursor >= json.length()) break;
            char escape = json.charAt(cursor++);
            switch (escape) {
                case '"', '\\', '/' -> value.append(escape);
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'u' -> {
                    if (cursor + 4 > json.length()) return value.toString();
                    try {
                        value.append((char) Integer.parseInt(json.subSequence(cursor, cursor + 4).toString(), 16));
                    } catch (NumberFormatException ignored) {
                        return value.toString();
                    }
                    cursor += 4;
                }
                default -> { return value.toString(); }
            }
        }
        return value.toString();
    }
}

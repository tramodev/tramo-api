package com.tramo.backend.common;

import com.tramo.backend.exception.RequestErrorCode;
import com.tramo.backend.exception.RequestValidationException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class LexicalContent {
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> TYPES = Set.of("root", "paragraph", "text", "linebreak", "tab",
            "heading", "quote", "list", "listitem", "code", "code-highlight", "link", "autolink",
            "image", "equation", "music", "embed", "table", "tablerow", "tablecell", "horizontalrule");
    private static final Set<String> SCHEMES = Set.of("http", "https", "mailto", "sms", "tel");
    private static final Set<String> EMBED_FIELDS = Set.of("type", "version", "format", "provider", "id");
    private static final Pattern FONT_SIZE = Pattern.compile("[0-9]+(?:\\.[0-9]+)?px");
    private static final Pattern COLOR = Pattern.compile("rgb\\((?:25[0-5]|2[0-4]\\d|1?\\d?\\d), (?:25[0-5]|2[0-4]\\d|1?\\d?\\d), (?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\)");

    private LexicalContent() {}

    public static void validate(String content) {
        if (content == null || content.isEmpty()) return;
        try {
            JsonNode state = MAPPER.readTree(content);
            if (state == null || !state.isObject() || !"root".equals(state.path("root").path("type").asText())
                    || !state.path("root").path("children").isArray()) throw invalid();
            validateNode(state.path("root"), 0);
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private static void validateNode(JsonNode node, int depth) {
        String type = node.path("type").asText("");
        if (depth > 100 || !node.isObject() || !TYPES.contains(type)
                || (depth > 0 && "root".equals(type))) throw invalid();
        validateStyle(node.path("style"));
        validateStyle(node.path("textStyle"));
        if ("embed".equals(type)) {
            if (node.properties().stream().anyMatch(entry -> !EMBED_FIELDS.contains(entry.getKey()))) throw invalid();
            String provider = node.path("provider").asText("");
            String id = node.path("id").asText("");
            if (!("youtube".equals(provider) && id.matches("[A-Za-z0-9_-]{11}")
                    || "tweet".equals(provider) && id.matches("[1-9][0-9]{0,19}"))) throw invalid();
        }
        if ("link".equals(type) || "autolink".equals(type)) {
            JsonNode url = node.path("url");
            if (!url.isString()) throw invalid();
            String rel = node.path("rel").asText("");
            boolean internal = "link".equals(type) && "#".equals(url.asText())
                    && rel.matches("(?:tramo-idea:|mypath-idea:)[0-9]+|tramo-extraction:[0-9a-fA-F-]{36}");
            boolean placeholder = "link".equals(type) && "https://".equals(url.asText());
            if (!internal && !placeholder) {
                URI uri = URI.create(url.asText().strip()
                        .replace(" ", "%20").replace("{", "%7B").replace("}", "%7D"));
                if (uri.getScheme() == null || !SCHEMES.contains(uri.getScheme().toLowerCase(Locale.ROOT))) throw invalid();
            }
        }
        if ("equation".equals(type) || "music".equals(type)) {
            JsonNode source = node.path("equation".equals(type) ? "equation" : "abc");
            if (!source.isString() || source.asText().length() > 10000) throw invalid();
        }
        if (node.has("children")) {
            if (!node.path("children").isArray()) throw invalid();
            for (JsonNode child : node.path("children")) validateNode(child, depth + 1);
        }
    }

    private static void validateStyle(JsonNode style) {
        if (style.isMissingNode()) return;
        if (!style.isString()) throw invalid();
        for (String declaration : style.asText().split(";", -1)) {
            if (declaration.isBlank()) continue;
            int colon = declaration.indexOf(':');
            if (colon < 0) throw invalid();
            String property = declaration.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = declaration.substring(colon + 1).strip();
            if (!("font-size".equals(property) && FONT_SIZE.matcher(value).matches()
                    && Double.parseDouble(value.substring(0, value.length() - 2)) >= 8
                    && Double.parseDouble(value.substring(0, value.length() - 2)) <= 72
                    || ("color".equals(property) || "background-color".equals(property)) && COLOR.matcher(value).matches())) throw invalid();
        }
    }

    private static RequestValidationException invalid() {
        return new RequestValidationException(RequestErrorCode.EDITOR_CONTENT_INVALID);
    }
}

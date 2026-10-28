package com.tramo.backend.common;

import com.tramo.backend.exception.RequestErrorCode;
import com.tramo.backend.exception.RequestValidationException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

public final class LexicalContent {
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> TYPES = Set.of("root", "paragraph", "text", "linebreak", "tab",
            "heading", "quote", "list", "listitem", "code", "code-highlight", "link", "autolink",
            "image", "equation", "music", "embed", "table", "tablerow", "tablecell", "horizontalrule");
    private static final Set<String> SCHEMES = Set.of("http", "https", "mailto", "sms", "tel");

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
        if ("equation".equals(type) && node.path("equation").asText("").length() > 10000) throw invalid();
        if (node.has("children")) {
            if (!node.path("children").isArray()) throw invalid();
            for (JsonNode child : node.path("children")) validateNode(child, depth + 1);
        }
    }

    private static RequestValidationException invalid() {
        return new RequestValidationException(RequestErrorCode.EDITOR_CONTENT_INVALID);
    }
}

package com.tramo.backend.common;

import com.tramo.backend.exception.RequestErrorCode;
import com.tramo.backend.exception.RequestValidationException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.LoggerFactory;
import io.micrometer.core.instrument.Metrics;
import java.util.Set;
import java.util.regex.Pattern;

public final class LexicalContent {
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> TYPES = Set.of("root", "paragraph", "text", "linebreak", "tab",
            "heading", "quote", "list", "listitem", "code", "code-highlight", "link", "autolink",
            "image", "equation", "music", "embed", "table", "tablerow", "tablecell", "horizontalrule");
    private static final Set<String> EMBED_FIELDS = Set.of("type", "version", "format", "provider", "id");
    private static final Set<String> SCHEMES = Set.of("http", "https", "mailto", "sms", "tel");
    private static final Pattern FONT_SIZE = Pattern.compile("[0-9]+(?:\\.[0-9]+)?px");
    private static final Pattern COLOR = Pattern.compile("rgb\\((?:25[0-5]|2[0-4]\\d|1?\\d?\\d), (?:25[0-5]|2[0-4]\\d|1?\\d?\\d), (?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\)");

    private LexicalContent() {}

    public enum Mode { STRICT, PRESERVE_LEGACY }

    public static void validate(String content) {
        validate(content, Mode.STRICT);
    }

    public static void validate(String content, Mode mode) {
        Map<String, Integer> sources = new HashMap<>();
        validateNode(document(content), 0, mode, sources);
        if (mode == Mode.PRESERVE_LEGACY) preserved("copy", sources.values().stream().mapToInt(Integer::intValue).sum());
    }

    public static void validateUpdate(String content, String previousContent) {
        Map<String, Integer> sources = new HashMap<>();
        try { validateNode(document(previousContent), 0, Mode.PRESERVE_LEGACY, sources); }
        catch (RuntimeException invalidPrevious) { sources.clear(); }
        int before = sources.values().stream().mapToInt(Integer::intValue).sum();
        validateNode(document(content), 0, Mode.STRICT, sources);
        preserved("update", before - sources.values().stream().mapToInt(Integer::intValue).sum());
    }

    private static JsonNode document(String content) {
        if (content == null || content.isEmpty()) return null;
        try {
            JsonNode state = MAPPER.readTree(content);
            if (state == null || !state.isObject() || !"root".equals(state.path("root").path("type").asText())
                    || !state.path("root").path("children").isArray()) throw invalid();
            return state.path("root");
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private static void preserved(String operation, int count) {
        if (count == 0) return;
        Metrics.counter("editor.content.legacy.preserved", "operation", operation).increment(count);
        LoggerFactory.getLogger(LexicalContent.class).warn("event=editor_content_legacy_preserved operation={} nodes={}", operation, count);
    }

    public static String sanitizeLinks(String content) {
        if (content == null || content.isEmpty()) return content;
        JsonNode state = MAPPER.readTree(content);
        if (sanitizeChildren(state.path("root"))) return MAPPER.writeValueAsString(state);
        return content;
    }

    private static boolean sanitizeChildren(JsonNode node) {
        JsonNode children = node.path("children");
        if (!children.isArray()) return false;
        ArrayNode sanitized = MAPPER.createArrayNode();
        boolean changed = false;
        for (JsonNode child : children) {
            changed |= sanitizeChildren(child);
            String type = child.path("type").asText("");
            if (("link".equals(type) || "autolink".equals(type)) && !safeLink(child)) {
                for (JsonNode grandchild : child.path("children")) sanitized.add(grandchild);
                changed = true;
            } else sanitized.add(child);
        }
        if (changed) ((ObjectNode) node).set("children", sanitized);
        return changed;
    }

    private static boolean safeLink(JsonNode node) {
        JsonNode url = node.path("url");
        if (!url.isString()) return false;
        String value = url.asText();
        if ("link".equals(node.path("type").asText())) {
            if ("https://".equals(value)) return true;
            if ("#".equals(value) && node.path("rel").asText("")
                    .matches("(?:tramo-idea:|mypath-idea:)[0-9]+|tramo-extraction:[0-9a-fA-F-]{36}")) return true;
        }
        try {
            URI uri = URI.create(value.strip().replace(" ", "%20").replace("{", "%7B").replace("}", "%7D"));
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return SCHEMES.contains(scheme) && (!("http".equals(scheme) || "https".equals(scheme))
                    || uri.getHost() != null && !uri.getHost().isBlank());
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    private static void validateNode(JsonNode node, int depth, Mode mode, Map<String, Integer> sources) {
        if (node == null) return;
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
        if ("equation".equals(type) || "music".equals(type)) {
            JsonNode source = node.path("equation".equals(type) ? "equation" : "abc");
            if (!source.isString() || source.asText().length() > 10000) {
                String key = type + ":" + source.toString();
                if (mode == Mode.PRESERVE_LEGACY) sources.merge(key, 1, Integer::sum);
                else {
                    int remaining = sources.getOrDefault(key, 0);
                    if (remaining == 0) throw invalid();
                    sources.put(key, remaining - 1);
                }
            }
        }
        if (node.has("children")) {
            if (!node.path("children").isArray()) throw invalid();
            for (JsonNode child : node.path("children")) validateNode(child, depth + 1, mode, sources);
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

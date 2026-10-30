package com.tramo.backend.security;

import com.tramo.backend.common.ContentLimits;
import com.tramo.backend.common.LexicalContent;
import com.tramo.backend.exception.RequestValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LexicalContentTest {
    private String state(String node) {
        return "{\"root\":{\"type\":\"root\",\"children\":[" + node + "]}}";
    }

    @Test
    void boundsEquationLength() {
        assertThatCode(() -> ContentLimits.assertEditorNote(state("{\"type\":\"equation\",\"equation\":\"" + "x".repeat(10000) + "\"}")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ContentLimits.assertEditorNote(state("{\"type\":\"equation\",\"equation\":\"" + "x".repeat(10001) + "\"}")))
                .isInstanceOf(RequestValidationException.class);
    }

    @Test
    void boundsMusicLengthAndRequiresStringSources() {
        for (String type : new String[]{"equation", "music"}) {
            String field = "equation".equals(type) ? "equation" : "abc";
            assertThatCode(() -> ContentLimits.assertEditorNote(state("{\"type\":\"" + type + "\",\"" + field + "\":\"" + "x".repeat(10000) + "\"}")))
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> ContentLimits.assertEditorNote(state("{\"type\":\"" + type + "\",\"" + field + "\":\"" + "x".repeat(10001) + "\"}")))
                    .isInstanceOf(RequestValidationException.class);
            for (String value : new String[]{"null", "42", "true", "{}", "[]"}) {
                assertThatThrownBy(() -> ContentLimits.assertEditorNote(state("{\"type\":\"" + type + "\",\"" + field + "\":" + value + "}")))
                        .isInstanceOf(RequestValidationException.class);
            }
            assertThatThrownBy(() -> ContentLimits.assertEditorNote(state("{\"type\":\"" + type + "\"}")))
                    .isInstanceOf(RequestValidationException.class);
        }
    }

    @Test
    void acceptsInvalidSyntaxAsSourceText() {
        assertThatCode(() -> ContentLimits.assertEditorNote(state("{\"type\":\"equation\",\"equation\":\"{\"}")))
                .doesNotThrowAnyException();
        assertThatCode(() -> ContentLimits.assertEditorNote(state("{\"type\":\"music\",\"abc\":\"not a score\"}")))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsEditorNodesAndSupportedLinks() {
        for (String type : new String[]{"paragraph", "text", "linebreak", "tab", "heading", "quote", "list", "listitem",
                "code", "code-highlight", "image", "table", "tablerow", "tablecell", "horizontalrule"}) {
            assertThatCode(() -> ContentLimits.assertEditorNote(state("{\"type\":\"" + type + "\"}"))).doesNotThrowAnyException();
        }
        for (String type : new String[]{"link", "autolink"}) {
            for (String url : new String[]{"http://example.com", "HTTPS://example.com", "mailto:a@example.com", "sms:+123", "tel:+123"}) {
                assertThatCode(() -> ContentLimits.assertEditorNote(state("{\"type\":\"" + type + "\",\"url\":\"" + url + "\"}"))).doesNotThrowAnyException();
            }
        }
        assertThatCode(() -> ContentLimits.assertEditorNote("")).doesNotThrowAnyException();
        assertThatCode(() -> ContentLimits.assertEditorNote(state("{\"type\":\"link\",\"url\":\"#\",\"rel\":\"tramo-idea:123\"}"))).doesNotThrowAnyException();
    }

    @Test
    void acceptsManualLinkPlaceholderWhileEnteringTheDestination() {
        assertThatCode(() -> ContentLimits.assertEditorNote(state("{\"type\":\"link\",\"url\":\"https://\",\"children\":[{\"type\":\"text\",\"text\":\"Link this text\"}]}")))
                .doesNotThrowAnyException();
    }

    @Test
    void unsafeUrlsLoseTheirLinksButKeepTheirText() {
        for (String url : new String[]{"javascript:alert(1)", "data:text/html,payload", "http://", "HTTPS://", ""}) {
            String content = state("{\"type\":\"link\",\"url\":\"" + url + "\",\"children\":[{\"type\":\"text\",\"text\":\"label\"}]}");
            assertThatCode(() -> ContentLimits.assertEditorNote(content)).doesNotThrowAnyException();
            assertThat(LexicalContent.sanitizeLinks(content)).isEqualTo(state("{\"type\":\"text\",\"text\":\"label\"}"));
        }
        assertThat(LexicalContent.sanitizeLinks(state("{\"type\":\"autolink\",\"url\":\"https://\"}")))
                .isEqualTo(state(""));
        assertThatThrownBy(() -> ContentLimits.assertEditorNote(state("{\"type\":\"link\",\"url\":\"https://\",\"children\":[{\"type\":\"script\"}]}")))
                .isInstanceOf(RequestValidationException.class);
    }

    @Test
    void acceptsPastedLinksWithSpacesAndBraces() {
        for (String type : new String[]{"link", "autolink"}) {
            for (String url : new String[]{"https://example.com/my note", " https://example.com/my note ",
                    "https://example.com/{note}?filter={value}#my note", "https://example.com/my%20note?q=%7Bvalue%7D",
                    "mailto:a@example.com?subject=My note {draft}", "sms:+123?body=My note {draft}"}) {
                assertThatCode(() -> ContentLimits.assertEditorNote(state("{\"type\":\"" + type + "\",\"url\":\"" + url + "\"}")))
                        .doesNotThrowAnyException();
            }
        }
    }

    @Test
    void rejectsMalformedStatesUnknownNestedNodesAndUnsafeLinks() {
        for (String content : new String[]{"plain text", "{}", "null", "[]", " ", state("{}"), state("null"),
                state("{\"type\":\"paragraph\",\"children\":{}}"), state("{\"type\":\"paragraph\",\"children\":[{\"type\":\"script\"}]}"),
                state("{\"type\":\"root\",\"children\":[]}"), state("") + "{}"}) {
            assertThatThrownBy(() -> ContentLimits.assertEditorNote(content)).isInstanceOf(RequestValidationException.class);
        }
        for (String url : new String[]{"javascript:alert(1)", " javascript:alert({value: 1}) ", "java script:alert(1)",
                "data:text/html,{payload}", "file:///etc/passwd", "ftp://example.com", "//example.com", "/relative", "#", "", "java\\tscript:alert(1)"}) {
            assertThat(LexicalContent.sanitizeLinks(state("{\"type\":\"autolink\",\"url\":\"" + url + "\"}")))
                    .isEqualTo(state(""));
        }
        String nested = "{\"type\":\"text\"}";
        for (int i = 0; i < 105; i++) nested = "{\"type\":\"paragraph\",\"children\":[" + nested + "]}";
        String content = state(nested);
        assertThatThrownBy(() -> ContentLimits.assertEditorNote(content)).isInstanceOf(RequestValidationException.class);
    }

    @Test
    void updatePreservesOnlyExistingLegacySourcesAndTheirMultiplicity() {
        for (String type : new String[]{"equation", "music"}) {
            String field = "equation".equals(type) ? "equation" : "abc";
            for (String source : new String[]{"42", "null", "true", "{}", "[]", "\"" + "x".repeat(10001) + "\""}) {
                String legacy = "{\"type\":\"" + type + "\",\"" + field + "\":" + source + "}";
                String previous = state(legacy);
                String changed = state(legacy + ",{\"type\":\"paragraph\",\"children\":[{\"type\":\"text\",\"text\":\"saved remainder\"}]}");
                assertThatCode(() -> LexicalContent.validateUpdate(changed, previous)).doesNotThrowAnyException();
                assertThatThrownBy(() -> LexicalContent.validate(changed)).isInstanceOf(RequestValidationException.class);
                assertThatThrownBy(() -> LexicalContent.validateUpdate(changed, "")).isInstanceOf(RequestValidationException.class);
                assertThatThrownBy(() -> LexicalContent.validateUpdate(state(legacy + "," + legacy), previous)).isInstanceOf(RequestValidationException.class);
                assertThatCode(() -> LexicalContent.validateUpdate(state(""), previous)).doesNotThrowAnyException();
                assertThatCode(() -> ContentLimits.assertPreservedEditorNote(previous)).doesNotThrowAnyException();
            }
        }
    }

    @Test
    void preservationNeverSkipsStructuralOrStyleValidation() {
        for (String node : new String[]{"{\"type\":\"script\"}", "{\"type\":\"text\",\"style\":\"color: red\"}",
                "{\"type\":\"embed\",\"provider\":\"evil\",\"id\":\"123\"}"}) {
            assertThatThrownBy(() -> ContentLimits.assertPreservedEditorNote(state(node))).isInstanceOf(RequestValidationException.class);
            assertThatThrownBy(() -> LexicalContent.validateUpdate(state(node), state(node))).isInstanceOf(RequestValidationException.class);
        }
        assertThatCode(() -> LexicalContent.validateUpdate(state("{\"type\":\"text\",\"text\":\"fixed\"}"), "broken old JSON"))
                .doesNotThrowAnyException();
    }

    @Test
    void sharedEditorContentContract() throws Exception {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        try (var stream = getClass().getResourceAsStream("/contracts/editor-content.json")) {
            for (var fixture : mapper.readTree(stream)) {
                var node = (tools.jackson.databind.node.ObjectNode) fixture.path("node").deepCopy();
                String field = "equation".equals(node.path("type").asText()) ? "equation" : "abc";
                if (fixture.path("repeat").asInt() > 1) node.put(field, node.path(field).asText().repeat(fixture.path("repeat").asInt()));
                String content = state(node.toString());
                if (fixture.path("strict").asBoolean()) assertThatCode(() -> ContentLimits.assertEditorNote(content)).doesNotThrowAnyException();
                else assertThatThrownBy(() -> ContentLimits.assertEditorNote(content)).isInstanceOf(RequestValidationException.class);
                if (fixture.path("preserved").asBoolean()) assertThatCode(() -> ContentLimits.assertPreservedEditorNote(content)).doesNotThrowAnyException();
                else assertThatThrownBy(() -> ContentLimits.assertPreservedEditorNote(content)).isInstanceOf(RequestValidationException.class);
            }
        }
    }

    @Test
    void fixingOldStylesKeepsTheInventoryOfExistingLegacySources() {
        for (String source : new String[]{"42", "\"" + "x".repeat(10001) + "\""}) {
            String legacy = "{\"type\":\"equation\",\"equation\":" + source + "}";
            for (String nodes : new String[]{legacy + ",{\"type\":\"text\",\"style\":\"color: red;\"}",
                    "{\"type\":\"paragraph\",\"style\":\"position:fixed;\",\"children\":[" + legacy + "]}"}) {
                String previous = state(nodes);
                String repaired = previous.replace("color: red;", "").replace("position:fixed;", "");
                assertThatCode(() -> LexicalContent.validateUpdate(repaired, previous)).doesNotThrowAnyException();
                assertThatThrownBy(() -> LexicalContent.validateUpdate(previous, previous)).isInstanceOf(RequestValidationException.class);
                assertThatThrownBy(() -> LexicalContent.validateUpdate(state(legacy + "," + legacy), previous)).isInstanceOf(RequestValidationException.class);
            }
        }
    }
}

package com.tramo.backend.security;

import com.tramo.backend.common.ContentLimits;
import com.tramo.backend.exception.RequestValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LexicalContentTest {
    private String state(String node) {
        return "{\"root\":{\"type\":\"root\",\"children\":[" + node + "]}}";
    }

    @Test
    void acceptsEditorNodesAndSupportedLinks() {
        for (String type : new String[]{"paragraph", "text", "linebreak", "tab", "heading", "quote", "list", "listitem",
                "code", "code-highlight", "image", "equation", "music", "embed", "table", "tablerow", "tablecell", "horizontalrule"}) {
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
    void placeholderDoesNotAllowUnsafeUrlsOrUnknownChildren() {
        for (String url : new String[]{"javascript:alert(1)", "data:text/html,payload", "http://", "HTTPS://", ""}) {
            assertThatThrownBy(() -> ContentLimits.assertEditorNote(state("{\"type\":\"link\",\"url\":\"" + url + "\"}")))
                    .isInstanceOf(RequestValidationException.class);
        }
        assertThatThrownBy(() -> ContentLimits.assertEditorNote(state("{\"type\":\"autolink\",\"url\":\"https://\"}")))
                .isInstanceOf(RequestValidationException.class);
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
                state("{\"type\":\"root\",\"children\":[]}"), state("{\"type\":\"link\"}"), state("") + "{}"}) {
            assertThatThrownBy(() -> ContentLimits.assertEditorNote(content)).isInstanceOf(RequestValidationException.class);
        }
        for (String url : new String[]{"javascript:alert(1)", " javascript:alert({value: 1}) ", "java script:alert(1)",
                "data:text/html,{payload}", "file:///etc/passwd", "ftp://example.com", "//example.com", "/relative", "#", "", "java\\tscript:alert(1)"}) {
            assertThatThrownBy(() -> ContentLimits.assertEditorNote(state("{\"type\":\"autolink\",\"url\":\"" + url + "\"}")))
                    .isInstanceOf(RequestValidationException.class);
        }
        String nested = "{\"type\":\"text\"}";
        for (int i = 0; i < 105; i++) nested = "{\"type\":\"paragraph\",\"children\":[" + nested + "]}";
        String content = state(nested);
        assertThatThrownBy(() -> ContentLimits.assertEditorNote(content)).isInstanceOf(RequestValidationException.class);
    }
}

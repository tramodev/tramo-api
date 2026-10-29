package com.tramo.backend.common;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

class LexicalContentScanTest {
    @Test
    void reportsLiveNotesAndNestedVersionAndSnapshotContentWithoutPrintingSources() {
        var output = new ByteArrayOutputStream();
        var previous = System.out;
        String legacy = "{\"root\":{\"type\":\"root\",\"children\":[{\"type\":\"equation\",\"equation\":42}]}}";
        var mapper = JsonMapper.builder().build();
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            LexicalContentScan.inspect("item_content", 1, "content", legacy);
            LexicalContentScan.inspect("item_content", 2, "content", "{\"root\":{\"type\":\"root\",\"children\":[]}}");
            LexicalContentScan.snapshot("project_version", 3, mapper.valueToTree(java.util.Map.of("items", java.util.List.of(java.util.Map.of("content", legacy)))), "", 0);
            LexicalContentScan.snapshot("project_snapshot", 4, mapper.valueToTree(java.util.Map.of("trails", java.util.List.of(java.util.Map.of("items", java.util.List.of(java.util.Map.of("content", legacy)), "description", "secret".repeat(2000))))), "", 0);
        } finally { System.setOut(previous); }
        String report = output.toString(StandardCharsets.UTF_8);
        assertThat(report.lines().count()).isEqualTo(4);
        assertThat(report).contains("item_content", "project_version", "project_snapshot", "invalid_trail_description", "/trails/0/items/0/content")
                .doesNotContain("secret", "equation", "42");
    }
}

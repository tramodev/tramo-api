package com.tramo.backend.common;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.sql.DriverManager;
import java.util.Map;

public final class LexicalContentScan {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    public static void main(String[] args) throws Exception {
        try (var connection = DriverManager.getConnection(System.getenv().getOrDefault("JDBC_DATABASE_URL", "jdbc:postgresql://localhost:5432/tramo"),
                System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"))) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
            try (var query = connection.prepareStatement("select 'item_content' as source, id, content as body from item_content union all "
                    + "select 'project_version', id, snapshot from project_version union all select 'project_snapshot', id, content from project_snapshot")) {
                query.setFetchSize(100);
                try (var rows = query.executeQuery()) {
                    long scanned = 0;
                    while (rows.next()) {
                        scanned++;
                        String source = rows.getString("source");
                        long id = rows.getLong("id");
                        String content = rows.getString("body");
                        if ("item_content".equals(source)) inspect(source, id, "content", content);
                        else {
                            try { snapshot(source, id, MAPPER.readTree(content), "", 0); }
                            catch (RuntimeException invalid) { report(source, id, "snapshot", "invalid_snapshot"); }
                        }
                    }
                    System.out.println(MAPPER.writeValueAsString(Map.of("scanned", scanned)));
                }
            }
            connection.rollback();
        }
    }

    static void snapshot(String source, long id, JsonNode node, String path, int depth) {
        if (depth > 100 || node == null) throw new IllegalArgumentException("Invalid snapshot");
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) snapshot(source, id, node.get(i), path + "/" + i, depth + 1);
        } else if (node.isObject()) {
            for (var field : node.properties()) {
                String location = path + "/" + field.getKey();
                if ("content".equals(field.getKey())) {
                    if (field.getValue().isString() || field.getValue().isNull()) inspect(source, id, location, field.getValue().asText(null));
                    else report(source, id, location, "invalid_content_type");
                } else if ("description".equals(field.getKey()) && path.contains("/trails/")) {
                    try { ContentLimits.assertTrailDescription(field.getValue().asText(null)); }
                    catch (RuntimeException invalid) { report(source, id, location, "invalid_trail_description"); }
                } else if (field.getValue().isContainer()) snapshot(source, id, field.getValue(), location, depth + 1);
            }
        } else throw new IllegalArgumentException("Invalid snapshot");
    }

    static void inspect(String source, long id, String path, String content) {
        try { ContentLimits.assertEditorNote(content); }
        catch (RuntimeException invalid) { report(source, id, path, "invalid_editor_content"); }
    }

    private static void report(String source, long id, String path, String issue) {
        System.out.println(MAPPER.writeValueAsString(Map.of("source", source, "id", id, "path", path, "issue", issue)));
    }
}

package com.tramo.backend.project.service;

import com.tramo.backend.exception.ProjectExportException;
import com.tramo.backend.project.dto.ProjectExportDTO;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

public class ProjectExportMarkdown {
    private final ObjectMapper mapper;
    private final ProjectExportDTO data;
    private final Map<Long, String> anchors = new LinkedHashMap<>();
    private final Map<Long, ProjectExportDTO.ItemData> items = new LinkedHashMap<>();
    private final Map<Long, String> bodies = new HashMap<>();
    private final Set<String> warnings;
    private int nodes;
    private int sourceEmbeds;

    public ProjectExportMarkdown(ObjectMapper mapper, ProjectExportDTO data, Set<String> warnings) {
        this.mapper = mapper;
        this.data = data;
        this.warnings = warnings;
        data.items().forEach(item -> items.put(item.id(), item));
        data.trails().forEach(trail -> trail.steps().forEach(step -> anchors.putIfAbsent(step.itemId(), "trail-" + trail.id() + "-step-" + step.id())));
        data.looseItemIds().forEach(id -> anchors.putIfAbsent(id, "note-" + id));
    }

    public String render() {
        for (var item : data.items()) {
            String content = item.content();
            if (content == null || content.isBlank()) { bodies.put(item.id(), ""); continue; }
            JsonNode root;
            try { root = mapper.readTree(content); }
            catch (RuntimeException failure) {
                warnings.add("Note " + item.id() + ": invalid editor JSON is shown as original text.");
                bodies.put(item.id(), fence("", content));
                continue;
            }
            bodies.put(item.id(), node(root.has("root") ? root.get("root") : root, item.id(), 0));
        }
        if (sourceEmbeds > 0) warnings.add(sourceEmbeds + " equations or music scores exported as source. Review the original source for rendering errors.");
        StringBuilder md = new StringBuilder("<a id=\"top\"></a>\n\n# ").append(escape(data.project().title())).append("\n\n");
        String description = data.project().description();
        if (description != null && !description.isBlank()) md.append(escape(description)).append("\n\n");
        if (data.project().thumbnailImageUrl() != null && data.assets().containsKey(data.project().thumbnailImageUrl()))
            md.append("![Project thumbnail](").append(data.assets().get(data.project().thumbnailImageUrl())).append(")\n\n");
        if (!warnings.isEmpty()) { md.append("## Export warnings\n\n"); warnings.forEach(w -> md.append("- ").append(escape(w)).append("\n")); md.append("\n"); }
        md.append("## Contents\n\n");
        data.trails().forEach(trail -> {
            md.append("- [").append(escape(trail.title())).append("](#trail-").append(trail.id()).append(")\n");
            trail.steps().forEach(step -> md.append("  - [").append(escape(items.get(step.itemId()).title())).append("](#trail-").append(trail.id()).append("-step-").append(step.id()).append(")\n"));
        });
        md.append("- [Notes outside trails](#loose-notes)\n\n");
        for (var trail : data.trails()) {
            md.append("<a id=\"trail-").append(trail.id()).append("\"></a>\n\n## ").append(escape(trail.title())).append("\n\n");
            if (trail.description() != null && !trail.description().isBlank()) md.append(escape(trail.description())).append("\n\n");
            for (var step : trail.steps()) {
                article(md, step.itemId(), "trail-" + trail.id() + "-step-" + step.id());
                check(md);
            }
        }
        md.append("<a id=\"loose-notes\"></a>\n\n## Notes outside trails\n\n");
        for (Long id : data.looseItemIds()) { article(md, id, "note-" + id); check(md); }
        return md.append("---\n\n[Back to top](#top) · Original project data: [project.json](project.json). Exported ").append(escape(data.exportedAt())).append(".\n").toString();
    }

    private void article(StringBuilder md, Long id, String anchor) {
        var item = items.get(id);
        md.append("<a id=\"").append(anchor).append("\"></a>\n\n### ").append(escape(item.title())).append("\n\n");
        String body = bodies.get(id);
        if (!body.isBlank()) md.append(body).append("\n\n");
    }

    private String node(JsonNode n, Long itemId, int depth) {
        if (depth > 100 || ++nodes > 200000) throw ProjectExportException.tooLarge();
        String type = n.path("type").asText("");
        if ("image".equals(type)) {
            String reference = n.path("imageId").asText("");
            if (reference.isBlank()) reference = n.path("src").asText("");
            String path = data.assets().get(reference);
            if (path == null) throw ProjectExportException.resource("image in note " + itemId);
            String caption = n.path("caption").asText("");
            return "![" + escape(n.path("altText").asText("")) + "](" + path + ")" + (caption.isBlank() ? "" : "<br>*" + escape(caption) + "*");
        }
        if ("equation".equals(type) || "music".equals(type)) {
            sourceEmbeds++;
            boolean math = "equation".equals(type);
            JsonNode source = n.path(math ? "equation" : "abc");
            if (!source.isString()) warnings.add("Note " + itemId + ": " + type + " source is not text; original JSON is preserved.");
            else if (source.asText().length() > 10000) warnings.add("Note " + itemId + ": " + type + " source exceeds 10,000 characters; original source is preserved.");
            warnings.add("Note " + itemId + ": " + (math ? "equation shown as LaTeX source" : "music shown as ABC notation without sheet rendering or audio") + ".");
            String text = source.isString() ? source.asText() : source.toString();
            if (!math) return fence("abc", text);
            return n.path("inline").asBoolean(false) ? "$" + text + "$" : "$$\n" + text + "\n$$";
        }
        if ("text".equals(type) || "code-highlight".equals(type) || "tab".equals(type)) return text("tab".equals(type) ? "\t" : n.path("text").asText(""), n.path("format").asInt(0));
        if ("linebreak".equals(type)) return "<br>";
        if ("horizontalrule".equals(type)) return "---";
        if ("link".equals(type) || "autolink".equals(type)) {
            String body = inline(n, itemId, depth);
            String rel = n.path("rel").asText("");
            String prefix = rel.startsWith("tramo-idea:") ? "tramo-idea:" : rel.startsWith("mypath-idea:") ? "mypath-idea:" : null;
            String href = n.path("url").asText("");
            if (prefix != null) {
                try { Long target = Long.valueOf(rel.substring(prefix.length())); href = anchors.containsKey(target) ? "#" + anchors.get(target) : ""; }
                catch (NumberFormatException invalid) { href = ""; }
                if (href.isEmpty()) { warnings.add("Note " + itemId + ": internal link target is outside this export."); return body + " (outside this export)"; }
            } else if (!ProjectExportHtml.safeUrl(href)) { warnings.add("Note " + itemId + ": unsafe or unavailable link rendered as text."); return body; }
            return "[" + (body.isBlank() ? escape(href) : body) + "](" + href.replace("(", "%28").replace(")", "%29") + ")";
        }
        return switch (type) {
            case "root" -> blocks(n, itemId, depth);
            case "paragraph", "listitem", "tablerow", "tablecell" -> inline(n, itemId, depth);
            case "heading" -> "#".repeat(level(n.path("tag").asText(""))) + " " + inline(n, itemId, depth);
            case "quote" -> "> " + inline(n, itemId, depth);
            case "list" -> list(n, itemId, depth, "").stripTrailing();
            case "code" -> fence(n.path("language").asText("").replaceAll("[^A-Za-z0-9+#.-]", ""), plain(n, depth));
            case "table" -> table(n, itemId, depth);
            default -> {
                warnings.add("Note " + itemId + ": unsupported node " + type + " preserved as JSON.");
                yield "**Unsupported content: " + escape(type) + "**\n\n" + fence("json", n.toString());
            }
        };
    }

    private String blocks(JsonNode parent, Long itemId, int depth) {
        StringJoiner out = new StringJoiner("\n\n");
        for (JsonNode child : parent.path("children")) { String block = node(child, itemId, depth + 1); if (!block.isBlank()) out.add(block); }
        return out.toString();
    }

    private String inline(JsonNode parent, Long itemId, int depth) {
        StringBuilder out = new StringBuilder();
        for (JsonNode child : parent.path("children")) { out.append(node(child, itemId, depth + 1)); check(out); }
        return out.toString();
    }

    private String list(JsonNode n, Long itemId, int depth, String indent) {
        if (depth > 100) throw ProjectExportException.tooLarge();
        boolean numbered = "number".equals(n.path("listType").asText("")), checklist = "check".equals(n.path("listType").asText(""));
        int number = Math.max(1, n.path("start").asInt(1));
        StringBuilder out = new StringBuilder();
        for (JsonNode item : n.path("children")) {
            StringBuilder text = new StringBuilder(), nested = new StringBuilder();
            for (JsonNode child : item.path("children")) {
                if ("list".equals(child.path("type").asText(""))) nested.append(list(child, itemId, depth + 2, indent + "    "));
                else text.append(node(child, itemId, depth + 2));
            }
            if (!text.isEmpty() || nested.isEmpty()) {
                String marker = numbered ? number++ + ". " : "- ";
                if (checklist || item.path("checked").isBoolean()) marker += item.path("checked").asBoolean(false) ? "[x] " : "[ ] ";
                out.append(indent).append(marker).append(text).append("\n");
            }
            out.append(nested);
            check(out);
        }
        return out.toString();
    }

    private String table(JsonNode n, Long itemId, int depth) {
        List<List<String>> rows = new ArrayList<>();
        int columns = 0;
        for (JsonNode row : n.path("children")) {
            List<String> cells = new ArrayList<>();
            for (JsonNode cell : row.path("children")) cells.add(blocks(cell, itemId, depth + 2).replace("\n\n", "<br>").replace("\n", " "));
            rows.add(cells);
            columns = Math.max(columns, cells.size());
        }
        if (rows.isEmpty() || columns == 0) return "";
        StringBuilder out = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            out.append("|");
            for (int c = 0; c < columns; c++) out.append(" ").append(c < rows.get(r).size() ? rows.get(r).get(c) : "").append(" |");
            out.append("\n");
            if (r == 0) out.append("|").append(" --- |".repeat(columns)).append("\n");
        }
        return out.toString().stripTrailing();
    }

    private String plain(JsonNode n, int depth) {
        if (depth > 100 || ++nodes > 200000) throw ProjectExportException.tooLarge();
        String type = n.path("type").asText("");
        if ("linebreak".equals(type)) return "\n";
        if ("tab".equals(type)) return "\t";
        if (n.has("text")) return n.path("text").asText("");
        StringBuilder out = new StringBuilder();
        for (JsonNode child : n.path("children")) out.append(plain(child, depth + 1));
        return out.toString();
    }

    private static String text(String raw, int format) {
        if ((format & 128) != 0) raw = raw.toLowerCase(Locale.ROOT);
        if ((format & 256) != 0) raw = raw.toUpperCase(Locale.ROOT);
        if ((format & 512) != 0) raw = capitalize(raw);
        String core = raw.strip();
        if (core.isEmpty() || format == 0) return escape(raw);
        String lead = raw.substring(0, raw.indexOf(core)), trail = raw.substring(raw.indexOf(core) + core.length());
        String text = (format & 16) != 0 ? code(core) : escape(core);
        if ((format & 32) != 0) text = "<sub>" + text + "</sub>";
        if ((format & 64) != 0) text = "<sup>" + text + "</sup>";
        if ((format & 8) != 0) text = "<u>" + text + "</u>";
        if ((format & 1024) != 0) text = "<mark>" + text + "</mark>";
        if ((format & 4) != 0) text = "~~" + text + "~~";
        if ((format & 2) != 0) text = "*" + text + "*";
        if ((format & 1) != 0) text = "**" + text + "**";
        return escape(lead) + text + escape(trail);
    }

    private static String capitalize(String value) {
        StringBuilder out = new StringBuilder(value.length());
        boolean start = true;
        for (char c : value.toCharArray()) { out.append(start ? Character.toUpperCase(c) : c); start = Character.isWhitespace(c); }
        return out.toString();
    }

    private static String code(String raw) {
        String ticks = "`";
        while (raw.contains(ticks)) ticks += "`";
        String pad = raw.startsWith("`") || raw.endsWith("`") ? " " : "";
        return ticks + pad + raw + pad + ticks;
    }

    private static String fence(String language, String text) {
        String ticks = "```";
        while (text.contains(ticks)) ticks += "`";
        return ticks + language + "\n" + text + "\n" + ticks;
    }

    private static int level(String tag) { return Set.of("h1", "h2", "h3", "h4", "h5", "h6").contains(tag) ? tag.charAt(1) - '0' : 2; }

    public static String escape(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toCharArray()) { if ("\\`*_[]<>|~#".indexOf(c) >= 0) out.append('\\'); out.append(c); }
        return out.toString();
    }

    private static void check(StringBuilder value) { if (value.length() > 8 * 1024 * 1024) throw ProjectExportException.tooLarge(); }
}

package com.tramo.backend.project.service;

import com.tramo.backend.project.dto.ProjectExportDTO;
import com.tramo.backend.exception.ProjectExportException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.*;

public class ProjectExportHtml {
    private final ObjectMapper mapper;
    private final ProjectExportDTO data;
    private final Map<Long, String> anchors = new LinkedHashMap<>();
    private final Map<Long, ProjectExportDTO.ItemData> items = new LinkedHashMap<>();
    private final Map<Long, String> bodies = new HashMap<>();
    private final Set<String> warnings;
    private int nodes;

    public ProjectExportHtml(ObjectMapper mapper, ProjectExportDTO data, Set<String> warnings) {
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
                bodies.put(item.id(), "<pre>" + escape(content) + "</pre>");
                continue;
            }
            bodies.put(item.id(), node(root.has("root") ? root.get("root") : root, item.id(), 0));
        }
        StringBuilder html = new StringBuilder("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src 'self' file:; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'\"><title>");
        html.append(escape(data.project().title())).append("</title><style>body{font:16px/1.6 system-ui,sans-serif;margin:0 auto;padding:2rem;max-width:900px;color:#222;background:white}a{color:#174d92}nav,article,section{margin-bottom:2rem}article{border-top:1px solid #bbb;padding-top:1rem}img{max-width:100%;height:auto}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#f3f3f3;padding:1rem}blockquote{border-left:3px solid #bbb;padding-left:1rem}table{border-collapse:collapse;display:block;overflow:auto}td,th{border:1px solid #aaa;padding:.5rem}p{white-space:pre-wrap}figure{margin:1rem 0}figcaption,.warning{font-size:.9rem}article:target{outline:2px solid #aaa}input{pointer-events:none}@media(max-width:600px){body{padding:1rem}}@media print{body{max-width:none;padding:0}nav{break-after:page}article{break-inside:auto}a{color:inherit}pre{background:none}} </style></head><body><header id=\"top\"><h1>")
            .append(escape(data.project().title())).append("</h1><p>").append(escape(data.project().description())).append("</p></header>");
        if (data.project().thumbnailImageUrl() != null && data.assets().containsKey(data.project().thumbnailImageUrl()))
            html.append("<img alt=\"Project thumbnail\" src=\"").append(data.assets().get(data.project().thumbnailImageUrl())).append("\">");
        if (!warnings.isEmpty()) { html.append("<aside class=\"warning\"><h2>Export warnings</h2><ul>"); warnings.forEach(w -> html.append("<li>").append(escape(w)).append("</li>")); html.append("</ul></aside>"); }
        html.append("<nav aria-label=\"Project index\"><h2>Contents</h2><ul>");
        data.trails().forEach(trail -> {
            html.append("<li><a href=\"#trail-").append(trail.id()).append("\">").append(escape(trail.title())).append("</a><ul>");
            trail.steps().forEach(step -> html.append("<li><a href=\"#trail-").append(trail.id()).append("-step-").append(step.id()).append("\">").append(escape(items.get(step.itemId()).title())).append("</a></li>"));
            html.append("</ul></li>");
        });
        html.append("<li><a href=\"#loose-notes\">Notes outside trails</a></li></ul></nav>");
        for (var trail : data.trails()) {
            html.append("<section id=\"trail-").append(trail.id()).append("\"><h2>").append(escape(trail.title())).append("</h2><p>").append(escape(trail.description())).append("</p>");
            for (var step : trail.steps()) {
                article(html, step.itemId(), "trail-" + trail.id() + "-step-" + step.id());
                check(html);
            }
            html.append("</section>");
        }
        html.append("<section id=\"loose-notes\"><h2>Notes outside trails</h2>");
        for (Long id : data.looseItemIds()) { article(html, id, "note-" + id); check(html); }
        return html.append("</section><footer><a href=\"#top\">Back to top</a><p>Original project data: <a href=\"project.json\">project.json</a>. Exported ").append(escape(data.exportedAt())).append(".</p></footer></body></html>").toString();
    }

    private void article(StringBuilder html, Long id, String anchor) {
        var item = items.get(id);
        html.append("<article id=\"").append(anchor).append("\"><h3 style=\"text-align:").append(alignment(item.titleAlign())).append("\">").append(escape(item.title())).append("</h3>").append(bodies.get(id));
        List<ProjectExportDTO.AssociationData> relations = data.associations().stream().filter(a -> a.sourceItemId().equals(id) || a.targetId().equals(id)).toList();
        if (!relations.isEmpty()) {
            html.append("<aside><h4>Connections</h4><ul>");
            for (var association : relations) {
                html.append("<li>").append(itemLink(association.sourceItemId())).append(" — ").append(itemLink(association.targetId()));
                if (association.text() != null) html.append("<p>").append(escape(association.text())).append("</p>");
                html.append("</li>");
            }
            html.append("</ul></aside>");
        }
        html.append("</article>");
    }

    private String itemLink(Long id) {
        var item = items.get(id);
        return item != null && anchors.containsKey(id) ? "<a href=\"#" + anchors.get(id) + "\">" + escape(item.title()) + "</a>" : "Note " + id + " (outside this export)";
    }
    private String node(JsonNode n, Long itemId, int depth) {
        if (depth > 100 || ++nodes > 200000) throw ProjectExportException.tooLarge();
        String type = n.path("type").asText("");
        if ("image".equals(type)) {
            String reference = n.path("imageId").asText("");
            if (reference.isBlank()) reference = n.path("src").asText("");
            String path = data.assets().get(reference);
            if (path == null) throw ProjectExportException.resource("image in note " + itemId);
            return "<figure><img src=\"" + path + "\" alt=\"" + escape(n.path("altText").asText("")) + "\"" + dimension(n, "width") + dimension(n, "height") + "><figcaption>" + escape(n.path("caption").asText("")) + "</figcaption></figure>";
        }
        if ("equation".equals(type) || "music".equals(type)) {
            boolean math = "equation".equals(type);
            warnings.add("Note " + itemId + ": " + (math ? "equation shown as LaTeX source" : "music shown as ABC notation without sheet rendering or audio") + ".");
            return "<" + (math && n.path("inline").asBoolean(false) ? "code" : "pre") + ">" + escape(n.path(math ? "equation" : "abc").asText("")) + "</" + (math && n.path("inline").asBoolean(false) ? "code" : "pre") + ">";
        }
        if ("text".equals(type) || "code-highlight".equals(type) || "tab".equals(type)) {
            String text = escape("tab".equals(type) ? "\t" : n.path("text").asText(""));
            int format = n.path("format").asInt(0);
            String[] tags = {"strong", "em", "s", "u", "code", "sub", "sup"};
            for (int i = 0; i < tags.length; i++) if ((format & (1 << i)) != 0) text = "<" + tags[i] + ">" + text + "</" + tags[i] + ">";
            if ((format & 128) != 0) text = "<span style=\"text-transform:lowercase\">" + text + "</span>";
            if ((format & 256) != 0) text = "<span style=\"text-transform:uppercase\">" + text + "</span>";
            if ((format & 512) != 0) text = "<span style=\"text-transform:capitalize\">" + text + "</span>";
            if ((format & 1024) != 0) text = "<mark>" + text + "</mark>";
            String style = safeStyle(n.path("style").asText(""));
            return style.isBlank() ? text : "<span style=\"" + style + "\">" + text + "</span>";
        }
        StringBuilder children = new StringBuilder();
        for (JsonNode child : n.path("children")) { children.append(node(child, itemId, depth + 1)); check(children); }
        String body = children.toString();
        String tag = switch (type) {
            case "root" -> "div"; case "paragraph" -> "p"; case "quote" -> "blockquote";
            case "heading" -> Set.of("h1", "h2", "h3", "h4", "h5", "h6").contains(n.path("tag").asText("")) ? n.path("tag").asText("") : "h2";
            case "list" -> "number".equals(n.path("listType").asText("")) ? "ol" : "ul";
            case "listitem" -> "li"; case "code" -> "pre"; case "table" -> "table"; case "tablerow" -> "tr";
            case "tablecell" -> n.path("headerState").asInt(0) != 0 ? "th" : "td";
            default -> null;
        };
        if ("linebreak".equals(type)) return "<br>";
        if ("horizontalrule".equals(type)) return "<hr>";
        if ("link".equals(type) || "autolink".equals(type)) {
            String rel = n.path("rel").asText("");
            String prefix = rel.startsWith("tramo-idea:") ? "tramo-idea:" : rel.startsWith("mypath-idea:") ? "mypath-idea:" : null;
            String href = n.path("url").asText("");
            if (prefix != null) {
                try { Long target = Long.valueOf(rel.substring(prefix.length())); href = anchors.containsKey(target) ? "#" + anchors.get(target) : ""; }
                catch (NumberFormatException invalid) { href = ""; }
                if (href.isEmpty()) { warnings.add("Note " + itemId + ": internal link target is outside this export."); return body + " <span>(outside this export)</span>"; }
            } else if (!safeUrl(href)) { warnings.add("Note " + itemId + ": unsafe or unavailable link rendered as text."); return body; }
            return "<a href=\"" + escape(href) + "\" rel=\"noreferrer noopener\">" + body + "</a>";
        }
        if (tag == null) {
            warnings.add("Note " + itemId + ": unsupported node " + type + " preserved as JSON.");
            return "<details open><summary>Unsupported content: " + escape(type) + "</summary><pre>" + escape(n.toString()) + "</pre></details>";
        }
        String attributes = "code".equals(type) ? " data-language=\"" + escape(n.path("language").asText("")) + "\"" : "";
        if ("list".equals(type) && "ol".equals(tag)) attributes += " start=\"" + Math.max(1, n.path("start").asInt(1)) + "\"";
        if ("listitem".equals(type) && n.path("checked").isBoolean()) body = "<input type=\"checkbox\" disabled" + (n.path("checked").asBoolean(false) ? " checked" : "") + " aria-label=\"Checklist item\"> " + body;
        if ("tablecell".equals(type)) attributes += " colspan=\"" + Math.min(100, Math.max(1, n.path("colSpan").asInt(1))) + "\" rowspan=\"" + Math.min(100, Math.max(1, n.path("rowSpan").asInt(1))) + "\"";
        String format = n.path("format").asText("");
        String style = safeStyle(n.path("style").asText(""));
        if (Set.of("left", "center", "right", "justify").contains(format)) style += "text-align:" + format + ";";
        int indent = Math.min(20, Math.max(0, n.path("indent").asInt(0)));
        if (indent > 0 && !"listitem".equals(type)) style += "margin-left:" + indent + "em;";
        if (Set.of("rtl", "ltr").contains(n.path("direction").asText(""))) attributes += " dir=\"" + n.path("direction").asText("") + "\"";
        if (!style.isBlank()) attributes += " style=\"" + style + "\"";
        return "<" + tag + attributes + ">" + body + "</" + tag + ">";
    }
    public static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
    private static String alignment(String value) { return Set.of("left", "right", "center").contains(value == null ? "" : value) ? value : "center"; }
    private static String dimension(JsonNode node, String field) { int value = node.path(field).asInt(0); return value > 0 && value <= 10000 ? " " + field + "=\"" + value + "\"" : ""; }
    private static String safeStyle(String value) {
        StringBuilder safe = new StringBuilder();
        for (String declaration : value.split(";")) {
            String[] pair = declaration.trim().split(":", 2);
            if (pair.length != 2) continue;
            String name = pair[0].trim(), v = pair[1].trim();
            if (Set.of("color", "background-color").contains(name) && v.matches("rgb\\(\\d{1,3}, \\d{1,3}, \\d{1,3}\\)")) safe.append(name).append(":").append(v).append(";");
            if ("font-size".equals(name) && v.matches("\\d+(\\.\\d+)?px")) { double size = Double.parseDouble(v.substring(0, v.length()-2)); if (size >= 8 && size <= 72) safe.append(name).append(":").append(v).append(";"); }
        }
        return safe.toString();
    }
    private static boolean safeUrl(String value) {
        if (value.chars().anyMatch(c -> c <= 32 || c == 127)) return false;
        try { URI uri = URI.create(value); String scheme = uri.getScheme(); return scheme != null && (Set.of("http", "https").contains(scheme.toLowerCase(Locale.ROOT)) && uri.getHost() != null || "mailto".equalsIgnoreCase(scheme)); }
        catch (IllegalArgumentException invalid) { return false; }
    }
    private static void check(StringBuilder value) { if (value.length() > 8 * 1024 * 1024) throw ProjectExportException.tooLarge(); }
}

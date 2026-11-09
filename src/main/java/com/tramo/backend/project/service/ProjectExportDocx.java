package com.tramo.backend.project.service;

import com.tramo.backend.common.LexicalContent;
import com.tramo.backend.exception.ProjectExportException;
import com.tramo.backend.project.dto.ProjectExportDTO;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import javax.imageio.ImageIO;
import java.io.*;
import java.math.BigInteger;
import java.util.*;

public class ProjectExportDocx {
    private static final Map<String, String> PALETTE = Map.of("red", "E79591", "orange", "F0AA54", "green", "56A767", "blue", "476EAC", "purple", "7C61BF", "gray", "9AA0A6");
    private static final String MONO = "Courier New";
    private final ObjectMapper mapper;
    private final ProjectExportDTO data;
    private final Set<String> warnings;
    private final Map<String, byte[]> assets;
    private final Map<Long, ProjectExportDTO.ItemData> items = new LinkedHashMap<>();
    private final XWPFDocument document = new XWPFDocument();
    private int nodes;
    private int sourceEmbeds;

    private record Heading(int size) {}

    public ProjectExportDocx(ObjectMapper mapper, ProjectExportDTO data, Set<String> warnings, Map<String, byte[]> assets) {
        this.mapper = mapper;
        this.data = data;
        this.warnings = warnings;
        this.assets = assets;
        data.items().forEach(item -> items.put(item.id(), item));
    }

    public void write(OutputStream out) throws IOException {
        try (document) {
            headingStyle(1);
            headingStyle(2);
            title(document.createParagraph(), data.project().title(), 24);
            text(document.createParagraph(), data.project().description());
            String thumbnail = data.project().thumbnailImageUrl();
            if (thumbnail != null && data.assets().containsKey(thumbnail)) picture(document.createParagraph(), data.assets().get(thumbnail), 0, "", thumbnail);
            for (var trail : data.trails()) {
                title(outline(document.createParagraph(), 0), trail.title(), 18);
                text(document.createParagraph(), trail.description());
                for (var step : trail.steps()) note(step.itemId());
            }
            title(outline(document.createParagraph(), 0), "Notes outside trails", 18);
            for (Long id : data.looseItemIds()) note(id);
            if (sourceEmbeds > 0) warnings.add(sourceEmbeds + " equations or music scores exported as source. Review the original source for rendering errors.");
            if (!warnings.isEmpty()) {
                title(document.createParagraph(), "Export warnings", 14);
                for (String warning : warnings) text(document.createParagraph(), "• " + warning);
            }
            text(document.createParagraph(), "Exported " + data.exportedAt() + ".");
            document.write(out);
        }
    }

    private void note(Long id) {
        var item = items.get(id);
        XWPFParagraph heading = outline(document.createParagraph(), 1);
        heading.setAlignment(ParagraphAlignment.valueOf(ProjectExportHtml.alignment(item.titleAlign()).toUpperCase(Locale.ROOT)));
        title(heading, item.title() == null || item.title().isBlank() ? "Untitled note" : item.title(), 14);
        String content = item.content();
        if (content == null || content.isBlank()) return;
        JsonNode root;
        try { root = mapper.readTree(content); }
        catch (RuntimeException failure) {
            warnings.add("Note " + item.id() + ": invalid editor JSON is shown as original text.");
            mono(document.createParagraph().createRun(), content);
            return;
        }
        block(root.has("root") ? root.get("root") : root, document, id, 0);
    }

    private void block(JsonNode n, IBody body, Long itemId, int depth) {
        count(depth);
        String type = n.path("type").asText("");
        switch (type) {
            case "root" -> { for (JsonNode child : n.path("children")) block(child, body, itemId, depth + 1); }
            case "paragraph", "heading", "quote" -> {
                XWPFParagraph p = paragraph(body);
                String format = n.path("format").asText("");
                if (Set.of("left", "center", "right").contains(format)) p.setAlignment(ParagraphAlignment.valueOf(format.toUpperCase(Locale.ROOT)));
                if ("justify".equals(format)) p.setAlignment(ParagraphAlignment.BOTH);
                int indent = Math.min(20, Math.max(0, n.path("indent").asInt(0)));
                if ("quote".equals(type)) { p.setIndentationLeft(720 + indent * 360); p.setBorderLeft(Borders.SINGLE); }
                else if (indent > 0) p.setIndentationLeft(indent * 360);
                Heading heading = "heading".equals(type) ? new Heading(switch (n.path("tag").asText("")) { case "h1" -> 20; case "h3" -> 15; case "h4" -> 13; case "h5" -> 12; case "h6" -> 11; default -> 17; }) : null;
                inline(n, p, itemId, depth, heading);
            }
            case "list" -> list(n, body, itemId, depth, 0);
            case "code" -> {
                XWPFParagraph p = paragraph(body);
                p.setIndentationLeft(360);
                mono(p.createRun(), plain(n, depth));
            }
            case "table" -> table(n, body, itemId, depth);
            case "horizontalrule" -> paragraph(body).setBorderBottom(Borders.SINGLE);
            default -> run(n, paragraph(body), itemId, depth, null);
        }
    }

    private void inline(JsonNode parent, XWPFParagraph p, Long itemId, int depth, Heading heading) {
        for (JsonNode child : parent.path("children")) run(child, p, itemId, depth + 1, heading);
    }

    private void run(JsonNode n, XWPFParagraph p, Long itemId, int depth, Heading heading) {
        count(depth);
        String type = n.path("type").asText("");
        switch (type) {
            case "text", "code-highlight" -> {
                XWPFRun run = p.createRun();
                int format = n.path("format").asInt(0);
                if (heading != null) { run.setBold(true); run.setFontSize(heading.size()); }
                format(run, format, n.path("style").asText(""));
                run.setText(transform(n.path("text").asText(""), format));
            }
            case "tab" -> p.createRun().addTab();
            case "linebreak" -> p.createRun().addBreak();
            case "image" -> {
                String reference = n.path("imageId").asText("");
                if (reference.isBlank()) reference = n.path("src").asText("");
                String path = data.assets().get(reference);
                if (path == null) throw ProjectExportException.resource("image in note " + itemId);
                int width = n.path("width").asInt(0);
                picture(p, path, width > 0 && width <= 10000 ? width : 0, n.path("caption").asText(""), "image in note " + itemId);
            }
            case "equation", "music" -> {
                sourceEmbeds++;
                boolean math = "equation".equals(type);
                JsonNode source = n.path(math ? "equation" : "abc");
                if (!source.isString()) warnings.add("Note " + itemId + ": " + type + " source is not text; original JSON is preserved.");
                else if (source.asText().length() > 10000) warnings.add("Note " + itemId + ": " + type + " source exceeds 10,000 characters; original source is preserved.");
                warnings.add("Note " + itemId + ": " + (math ? "equation shown as LaTeX source" : "music shown as ABC notation without sheet rendering or audio") + ".");
                mono(p.createRun(), source.isString() ? source.asText() : source.toString());
            }
            case "link", "autolink" -> {
                String rel = n.path("rel").asText("");
                String href = n.path("url").asText("");
                if (rel.startsWith("tramo-idea:") || rel.startsWith("mypath-idea:")) {
                    inline(n, p, itemId, depth, heading);
                    Long target = null;
                    try { target = Long.valueOf(rel.substring(rel.indexOf(':') + 1)); } catch (NumberFormatException invalid) {}
                    if (target == null || !items.containsKey(target)) { warnings.add("Note " + itemId + ": internal link target is outside this export."); p.createRun().setText(" (outside this export)"); }
                } else if (!ProjectExportHtml.safeUrl(href)) {
                    warnings.add("Note " + itemId + ": unsafe or unavailable link rendered as text.");
                    inline(n, p, itemId, depth, heading);
                } else {
                    XWPFHyperlinkRun link = p.createHyperlinkRun(href);
                    String label = plain(n, depth);
                    link.setText(label.isBlank() ? href : label);
                    link.setUnderline(UnderlinePatterns.SINGLE);
                    link.setColor("174D92");
                }
            }
            default -> {
                warnings.add("Note " + itemId + ": unsupported node " + type + " preserved as JSON.");
                mono(p.createRun(), "Unsupported content: " + type + "\n" + n);
            }
        }
    }

    private void list(JsonNode n, IBody body, Long itemId, int depth, int level) {
        count(depth);
        boolean numbered = "number".equals(n.path("listType").asText("")), checklist = "check".equals(n.path("listType").asText(""));
        int number = Math.max(1, n.path("start").asInt(1));
        for (JsonNode item : n.path("children")) {
            List<JsonNode> content = new ArrayList<>(), nested = new ArrayList<>();
            for (JsonNode child : item.path("children")) ("list".equals(child.path("type").asText("")) ? nested : content).add(child);
            if (!content.isEmpty() || nested.isEmpty()) {
                XWPFParagraph p = paragraph(body);
                p.setIndentationLeft(360 * (level + 1));
                p.setIndentationHanging(360);
                String marker = numbered ? number++ + "." : checklist || item.path("checked").isBoolean() ? (item.path("checked").asBoolean(false) ? "☒" : "☐") : "•";
                XWPFRun run = p.createRun();
                run.setText(marker);
                run.addTab();
                for (JsonNode child : content) run(child, p, itemId, depth + 2, null);
            }
            for (JsonNode child : nested) list(child, body, itemId, depth + 2, level + 1);
        }
    }

    private void table(JsonNode n, IBody body, Long itemId, int depth) {
        List<JsonNode> rows = new ArrayList<>();
        int columns = 0;
        for (JsonNode row : n.path("children")) { rows.add(row); columns = Math.max(columns, row.path("children").size()); }
        if (rows.isEmpty() || columns == 0) return;
        if (body != document) {
            for (JsonNode row : rows) for (JsonNode cell : row.path("children")) for (JsonNode child : cell.path("children")) block(child, body, itemId, depth + 3);
            return;
        }
        XWPFTable table = document.createTable(rows.size(), columns);
        table.setWidth("100%");
        for (int r = 0; r < rows.size(); r++) {
            JsonNode cells = rows.get(r).path("children");
            for (int c = 0; c < cells.size(); c++) {
                XWPFTableCell cell = table.getRow(r).getCell(c);
                for (JsonNode child : cells.get(c).path("children")) block(child, cell, itemId, depth + 3);
                if (cell.getParagraphs().size() > 1) cell.removeParagraph(0);
            }
        }
    }

    private void picture(XWPFParagraph p, String path, int requestedWidth, String caption, String reference) {
        byte[] bytes = assets.get(path);
        if (bytes == null || bytes.length < 4) throw ProjectExportException.resource(reference);
        int type = (bytes[0] & 0xFF) == 0x89 ? Document.PICTURE_TYPE_PNG : (bytes[0] & 0xFF) == 0xFF ? Document.PICTURE_TYPE_JPEG : bytes[0] == 'G' ? Document.PICTURE_TYPE_GIF : -1;
        try {
            var image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null || type < 0) throw ProjectExportException.resource(reference);
            double width = image.getWidth() * 0.75, height = image.getHeight() * 0.75;
            if (requestedWidth > 0) { height = height * requestedWidth * 0.75 / width; width = requestedWidth * 0.75; }
            if (width > 450) { height = height * 450 / width; width = 450; }
            XWPFRun run = p.createRun();
            run.addPicture(new ByteArrayInputStream(bytes), type, path, Units.toEMU(width), Units.toEMU(height));
            if (!caption.isBlank()) { run.addBreak(); XWPFRun text = p.createRun(); text.setItalic(true); text.setText(caption); }
        } catch (IOException | InvalidFormatException failure) { throw ProjectExportException.resource(reference); }
    }

    private XWPFParagraph paragraph(IBody body) {
        return body instanceof XWPFTableCell cell ? cell.addParagraph() : document.createParagraph();
    }

    private static XWPFParagraph outline(XWPFParagraph p, int level) {
        p.setStyle("Heading" + (level + 1));
        return p;
    }

    private void headingStyle(int level) {
        var style = CTStyle.Factory.newInstance();
        style.setType(STStyleType.PARAGRAPH);
        style.setStyleId("Heading" + level);
        style.addNewName().setVal("heading " + level);
        style.addNewQFormat();
        style.addNewPPr().addNewOutlineLvl().setVal(BigInteger.valueOf(level - 1));
        document.createStyles().addStyle(new XWPFStyle(style));
    }

    private static void title(XWPFParagraph p, String value, int size) {
        XWPFRun run = p.createRun();
        run.setBold(true);
        run.setFontSize(size);
        run.setText(value == null ? "" : value);
    }

    private static void text(XWPFParagraph p, String value) {
        if (value != null && !value.isBlank()) p.createRun().setText(value);
    }

    private static void mono(XWPFRun run, String value) {
        run.setFontFamily(MONO);
        String[] lines = value.split("\n", -1);
        for (int i = 0; i < lines.length; i++) { if (i > 0) run.addBreak(); run.setText(lines[i]); }
    }

    private static void format(XWPFRun run, int format, String style) {
        if ((format & 1) != 0) run.setBold(true);
        if ((format & 2) != 0) run.setItalic(true);
        if ((format & 4) != 0) run.setStrikeThrough(true);
        if ((format & 8) != 0) run.setUnderline(UnderlinePatterns.SINGLE);
        if ((format & 16) != 0) run.setFontFamily(MONO);
        if ((format & 32) != 0) run.setSubscript(VerticalAlign.SUBSCRIPT);
        if ((format & 64) != 0) run.setSubscript(VerticalAlign.SUPERSCRIPT);
        if ((format & 1024) != 0) run.setTextHighlightColor("yellow");
        for (String declaration : style.split(";")) {
            String[] pair = declaration.trim().split(":", 2);
            if (pair.length != 2) continue;
            String name = pair[0].trim(), value = pair[1].trim();
            if ("color".equals(name) && LexicalContent.isAllowedColor(value)) run.setColor(hex(value));
            if ("font-size".equals(name) && value.matches("\\d+(\\.\\d+)?px")) {
                double size = Double.parseDouble(value.substring(0, value.length() - 2));
                if (size >= 8 && size <= 72) run.setFontSize((int) Math.round(size * 0.75));
            }
        }
    }

    private static String hex(String color) {
        if (color.startsWith("var(--ed-")) return PALETTE.get(color.substring(9, color.length() - 1));
        String[] parts = color.substring(4, color.length() - 1).split(",\\s*");
        return String.format("%02X%02X%02X", Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }

    private static String transform(String value, int format) {
        if ((format & 128) != 0) return value.toLowerCase(Locale.ROOT);
        if ((format & 256) != 0) return value.toUpperCase(Locale.ROOT);
        if ((format & 512) == 0) return value;
        StringBuilder out = new StringBuilder(value.length());
        boolean start = true;
        for (char c : value.toCharArray()) { out.append(start ? Character.toUpperCase(c) : c); start = Character.isWhitespace(c); }
        return out.toString();
    }

    private String plain(JsonNode n, int depth) {
        count(depth);
        String type = n.path("type").asText("");
        if ("linebreak".equals(type)) return "\n";
        if ("tab".equals(type)) return "\t";
        if (n.has("text")) return n.path("text").asText("");
        StringBuilder out = new StringBuilder();
        for (JsonNode child : n.path("children")) out.append(plain(child, depth + 1));
        return out.toString();
    }

    private void count(int depth) { if (depth > 100 || ++nodes > 200000) throw ProjectExportException.tooLarge(); }
}

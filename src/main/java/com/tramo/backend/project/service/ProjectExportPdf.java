package com.tramo.backend.project.service;

import com.openhtmltopdf.extend.FSStream;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PageMode;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import java.io.*;
import java.util.Map;

public final class ProjectExportPdf {
    private static final String SCHEME = "tramo-export";

    private ProjectExportPdf() {}

    public static void write(String html, Map<String, byte[]> assets, OutputStream out) throws IOException {
        var document = Jsoup.parse(html);
        document.select("meta[http-equiv], footer p").remove();
        document.select("input[type=checkbox]").forEach(box -> box.replaceWith(new TextNode(box.hasAttr("checked") ? "[x]" : "[ ]")));
        document.head().appendElement("style").text("table{display:table}td p,th p{margin:0}tr{page-break-inside:avoid}");
        Element bookmarks = document.head().appendElement("bookmarks");
        for (Element section : document.select("body > section[id]")) {
            Element trail = bookmark(bookmarks, section.selectFirst("> h2"), section.id(), "Untitled");
            for (Element article : section.select("> article[id]")) bookmark(trail, article.selectFirst("> h3"), article.id(), "Untitled note");
        }
        var builder = new PdfRendererBuilder();
        builder.useFastMode();
        builder.useUriResolver((base, uri) -> assets.containsKey(uri) ? SCHEME + ":" + uri : null);
        builder.useProtocolsStreamImplementation(url -> stream(assets.get(url.substring(SCHEME.length() + 1))), SCHEME);
        builder.withW3cDocument(new W3CDom().fromJsoup(document), SCHEME + ":");
        var rendered = new ByteArrayOutputStream();
        builder.toStream(rendered);
        builder.run();
        try (var pdf = Loader.loadPDF(rendered.toByteArray())) {
            pdf.getDocumentCatalog().setPageMode(PageMode.USE_OUTLINES);
            pdf.save(out);
        }
    }

    private static Element bookmark(Element parent, Element heading, String id, String fallback) {
        String name = heading == null ? "" : heading.text().strip();
        return parent.appendElement("bookmark").attr("name", name.isEmpty() ? fallback : name).attr("href", "#" + id);
    }

    private static FSStream stream(byte[] bytes) {
        return new FSStream() {
            public InputStream getStream() { return bytes == null ? null : new ByteArrayInputStream(bytes); }
            public Reader getReader() { return null; }
        };
    }
}

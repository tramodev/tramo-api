package com.tramo.backend.project.service;

import com.openhtmltopdf.extend.FSStream;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
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
        var builder = new PdfRendererBuilder();
        builder.useFastMode();
        builder.useUriResolver((base, uri) -> assets.containsKey(uri) ? SCHEME + ":" + uri : null);
        builder.useProtocolsStreamImplementation(url -> stream(assets.get(url.substring(SCHEME.length() + 1))), SCHEME);
        builder.withW3cDocument(new W3CDom().fromJsoup(document), SCHEME + ":");
        builder.toStream(out);
        builder.run();
    }

    private static FSStream stream(byte[] bytes) {
        return new FSStream() {
            public InputStream getStream() { return bytes == null ? null : new ByteArrayInputStream(bytes); }
            public Reader getReader() { return null; }
        };
    }
}

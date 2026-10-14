package com.tramo.backend.common;

import com.tramo.backend.exception.PayloadTooLargeException;
import java.nio.charset.StandardCharsets;

public final class ContentLimits {
    public static final int NOTE_BYTES = 1024 * 1024;
    public static final int REQUEST_BYTES = 4 * NOTE_BYTES;

    private ContentLimits() {}

    public static int bytes(String content) {
        return content == null ? 0 : content.getBytes(StandardCharsets.UTF_8).length;
    }

    public static void assertNote(String content) {
        if (content != null && (content.length() > NOTE_BYTES || bytes(content) > NOTE_BYTES)) {
            throw new PayloadTooLargeException();
        }
    }
}

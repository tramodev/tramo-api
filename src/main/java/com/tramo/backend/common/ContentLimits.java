package com.tramo.backend.common;

import com.tramo.backend.exception.PayloadTooLargeException;
import com.tramo.backend.exception.RequestErrorCode;
import com.tramo.backend.exception.RequestValidationException;
import java.nio.charset.StandardCharsets;

public final class ContentLimits {
    public static final int NOTE_BYTES = 1024 * 1024;
    public static final int REQUEST_BYTES = 4 * NOTE_BYTES;
    public static final int TRAIL_DESCRIPTION_CHARACTERS = 10000;

    private ContentLimits() {}

    public static int bytes(String content) {
        return content == null ? 0 : content.getBytes(StandardCharsets.UTF_8).length;
    }

    public static void assertNote(String content) {
        if (content != null && (content.length() > NOTE_BYTES || bytes(content) > NOTE_BYTES)) {
            throw new PayloadTooLargeException();
        }
    }

    public static void assertEditorNote(String content) {
        assertNote(content);
        LexicalContent.validate(content);
    }

    public static void assertPreservedEditorNote(String content) {
        LexicalContent.validate(content, LexicalContent.Mode.PRESERVE_LEGACY);
    }

    public static void assertTrailDescription(String description) {
        if (description != null && description.length() > TRAIL_DESCRIPTION_CHARACTERS) {
            throw new RequestValidationException(RequestErrorCode.TRAIL_DESCRIPTION_SIZE_INVALID);
        }
    }
}

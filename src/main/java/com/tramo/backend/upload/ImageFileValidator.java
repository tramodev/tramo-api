package com.tramo.backend.upload;

import com.tramo.backend.exception.RequestErrorCode;
import com.tramo.backend.exception.RequestValidationException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.event.IIOReadProgressListener;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.zip.CRC32;

public final class ImageFileValidator {
    private static final long MAX_PIXELS = 4_000_000;
    private static final long MAX_TOTAL_PIXELS = 40_000_000;
    private static final int MAX_FRAMES = 256;
    private static final int MAX_DIMENSION = 8192;
    private static final int MAX_METADATA_BYTES = 1_048_576;
    private static final int MAX_METADATA_BLOCKS = 1024;

    private ImageFileValidator() {}

    public static void validate(byte[] bytes, String type) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            checkContainer(bytes, type, deadline);
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid();
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!type.equals("image/" + format)) throw invalid();
                reader.setInput(input, false, true);
                reader.addIIOReadWarningListener((source, warning) -> { throw invalid(); });
                reader.addIIOReadProgressListener(new IIOReadProgressListener() {
                    public void sequenceStarted(ImageReader source, int minIndex) { check(); }
                    public void sequenceComplete(ImageReader source) { check(); }
                    public void imageStarted(ImageReader source, int imageIndex) { check(); }
                    public void imageProgress(ImageReader source, float percentageDone) { check(); }
                    public void imageComplete(ImageReader source) { check(); }
                    public void thumbnailStarted(ImageReader source, int imageIndex, int thumbnailIndex) { check(); }
                    public void thumbnailProgress(ImageReader source, float percentageDone) { check(); }
                    public void thumbnailComplete(ImageReader source) { check(); }
                    public void readAborted(ImageReader source) { throw invalid(); }
                    private void check() {
                        if (System.nanoTime() > deadline) throw new RequestValidationException(RequestErrorCode.IMAGE_DECODING_TIMEOUT);
                    }
                });
                if ("image/gif".equals(type)) {
                    dimensions((bytes[6] & 255) | (bytes[7] & 255) << 8, (bytes[8] & 255) | (bytes[9] & 255) << 8);
                }
                int frames = reader.getNumImages(true);
                if (frames < 1 || frames > MAX_FRAMES) throw invalid();
                long total = 0;
                for (int i = 0; i < frames; i++) {
                    int width = reader.getWidth(i), height = reader.getHeight(i);
                    total += dimensions(width, height);
                    if (total > MAX_TOTAL_PIXELS || System.nanoTime() > deadline) throw invalid();
                    var decoded = reader.read(i);
                    if (decoded == null) throw invalid();
                    decoded.flush();
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException | IndexOutOfBoundsException failure) {
            throw new RequestValidationException(RequestErrorCode.IMAGE_TRUNCATED, failure);
        }
    }

    private static long dimensions(int width, int height) {
        long pixels = (long) width * height;
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION || pixels > MAX_PIXELS)
            throw new RequestValidationException(RequestErrorCode.IMAGE_DIMENSIONS_EXCEEDED);
        return pixels;
    }

    private static void checkContainer(byte[] bytes, String type, long deadline) {
        if (bytes.length < 12) throw invalid();
        MetadataBudget metadata = new MetadataBudget(deadline);
        switch (type) {
            case "image/jpeg" -> {
                if ((bytes[0] & 255) != 255 || (bytes[1] & 255) != 216 ||
                        (bytes[bytes.length - 2] & 255) != 255 || (bytes[bytes.length - 1] & 255) != 217) throw invalid();
                checkJpegMetadata(bytes, metadata);
            }
            case "image/png" -> {
                var buffer = ByteBuffer.wrap(bytes);
                if (buffer.getLong() != 0x89504e470d0a1a0aL) throw invalid();
                boolean end = false;
                while (buffer.remaining() >= 12) {
                    int length = buffer.getInt();
                    int start = buffer.position();
                    int chunk = buffer.getInt();
                    if (length < 0 || length > buffer.remaining() - 4) throw invalid();
                    CRC32 crc = new CRC32();
                    crc.update(bytes, start, length + 4);
                    buffer.position(buffer.position() + length);
                    if ((int) crc.getValue() != buffer.getInt()) throw invalid();
                    if (chunk != 0x49484452 && chunk != 0x49444154 && chunk != 0x49454e44) {
                        metadata.block(length);
                        checkPngMetadata(bytes, start + 4, length, chunk, metadata);
                    }
                    if (chunk == 0x49454e44) {
                        if (length != 0 || buffer.hasRemaining()) throw invalid();
                        end = true;
                        break;
                    }
                }
                if (!end) throw invalid();
            }
            case "image/webp" -> {
                var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                if (buffer.getInt() != 0x46464952 || Integer.toUnsignedLong(buffer.getInt()) != bytes.length - 8L ||
                        buffer.getInt() != 0x50424557) throw invalid();
                checkWebpChunks(buffer, true, metadata);
            }
            case "image/gif" -> checkGif(bytes, deadline, metadata);
            default -> throw invalid();
        }
    }

    private static void checkWebpChunks(ByteBuffer buffer, boolean allowFrames, MetadataBudget metadata) {
        int frames = 0;
        long totalPixels = 0;
        while (buffer.hasRemaining()) {
            if (buffer.remaining() < 8) throw invalid();
            int chunk = buffer.getInt();
            long length = Integer.toUnsignedLong(buffer.getInt());
            long padded = length + (length & 1);
            if (padded > buffer.remaining()) throw invalid();
            ByteBuffer payload = buffer.slice(buffer.position(), (int) length).order(ByteOrder.LITTLE_ENDIAN);
            switch (chunk) {
                case 0x20385056 -> {
                    if (length < 10) throw invalid();
                    dimensions(Short.toUnsignedInt(payload.getShort(6)) & 0x3fff,
                            Short.toUnsignedInt(payload.getShort(8)) & 0x3fff);
                }
                case 0x4c385056 -> {
                    if (length < 5 || payload.get(0) != 0x2f) throw invalid();
                    int sizes = payload.getInt(1);
                    dimensions(1 + (sizes & 0x3fff), 1 + ((sizes >>> 14) & 0x3fff));
                }
                case 0x58385056 -> {
                    if (length != 10) throw invalid();
                    dimensions(1 + uint24(payload, 4), 1 + uint24(payload, 7));
                }
                case 0x48504c41 -> {}
                case 0x464d4e41 -> {
                    if (!allowFrames || ++frames > MAX_FRAMES || length < 16) throw invalid();
                    totalPixels += dimensions(1 + uint24(payload, 6), 1 + uint24(payload, 9));
                    if (totalPixels > MAX_TOTAL_PIXELS) throw invalid();
                    payload.position(16);
                    checkWebpChunks(payload.slice().order(ByteOrder.LITTLE_ENDIAN), false, metadata);
                }
                default -> {
                    metadata.block((int) length);
                    if (chunk == 0x50434349 && (length < 128 ||
                            Integer.toUnsignedLong(payload.duplicate().order(ByteOrder.BIG_ENDIAN).getInt(0)) != length)) throw invalid();
                }
            }
            buffer.position(buffer.position() + (int) padded);
        }
    }

    private static int uint24(ByteBuffer buffer, int offset) {
        return Byte.toUnsignedInt(buffer.get(offset)) | Byte.toUnsignedInt(buffer.get(offset + 1)) << 8
                | Byte.toUnsignedInt(buffer.get(offset + 2)) << 16;
    }

    private static void checkGif(byte[] bytes, long deadline, MetadataBudget metadata) {
        String signature = new String(bytes, 0, 6, java.nio.charset.StandardCharsets.US_ASCII);
        if (!signature.equals("GIF87a") && !signature.equals("GIF89a")) throw invalid();
        dimensions((bytes[6] & 255) | (bytes[7] & 255) << 8, (bytes[8] & 255) | (bytes[9] & 255) << 8);
        int p = 13;
        if ((bytes[10] & 128) != 0) p += 3 * (1 << ((bytes[10] & 7) + 1));
        int frames = 0;
        long totalPixels = 0;
        while (p < bytes.length) {
            int block = bytes[p++] & 255;
            if (block == 59) {
                if (frames == 0 || p != bytes.length) throw invalid();
                return;
            }
            java.io.ByteArrayOutputStream compressed = null;
            int codeSize = 0;
            long pixels = 0;
            if (block == 44) {
                if (++frames > MAX_FRAMES || p + 9 >= bytes.length) throw invalid();
                pixels = dimensions((bytes[p + 4] & 255) | (bytes[p + 5] & 255) << 8,
                        (bytes[p + 6] & 255) | (bytes[p + 7] & 255) << 8);
                totalPixels += pixels;
                if (totalPixels > MAX_TOTAL_PIXELS) throw invalid();
                int flags = bytes[p + 8] & 255;
                p += 9;
                if ((flags & 128) != 0) p += 3 * (1 << ((flags & 7) + 1));
                if (p >= bytes.length) throw invalid();
                codeSize = bytes[p++] & 255;
                if (codeSize < 2 || codeSize > 8) throw invalid();
                compressed = new java.io.ByteArrayOutputStream();
            } else if (block == 33) {
                metadata.block(2);
                p++;
            } else throw invalid();
            int length;
            do {
                if (p >= bytes.length) throw invalid();
                length = bytes[p++] & 255;
                if (length > bytes.length - p) throw invalid();
                if (compressed != null) compressed.write(bytes, p, length);
                else metadata.add(length + 1);
                p += length;
            } while (length != 0);
            if (compressed != null) checkGifCodes(compressed.toByteArray(), codeSize, pixels, deadline);
        }
        throw invalid();
    }

    private static void checkGifCodes(byte[] data, int minimumSize, long pixels, long deadline) {
        int clear = 1 << minimumSize;
        int end = clear + 1;
        int next = end + 1;
        int size = minimumSize + 1;
        int previous = -1;
        int[] lengths = new int[4096];
        java.util.Arrays.fill(lengths, 0, clear, 1);
        int bit = 0;
        long produced = 0;
        boolean first = true;
        while (bit + size <= data.length * 8) {
            if (System.nanoTime() > deadline) throw invalid();
            int code = 0;
            for (int i = 0; i < size; i++, bit++) code |= ((data[bit / 8] >> (bit % 8)) & 1) << i;
            if (first && code != clear) throw invalid();
            first = false;
            if (code == clear) {
                next = end + 1;
                size = minimumSize + 1;
                previous = -1;
                continue;
            }
            if (code == end) {
                if (produced != pixels) throw invalid();
                return;
            }
            int length;
            if (code < next) length = lengths[code];
            else if (code == next && previous >= 0 && next < 4096) length = lengths[previous] + 1;
            else throw invalid();
            if (length == 0 || (previous < 0 && code >= clear)) throw invalid();
            produced += length;
            if (produced > pixels) throw invalid();
            if (previous >= 0 && next < 4096) {
                lengths[next++] = lengths[previous] + 1;
                if (next == (1 << size) && size < 12) size++;
            }
            previous = code;
        }
        throw invalid();
    }

    private static void checkJpegMetadata(byte[] bytes, MetadataBudget metadata) {
        int p = 2;
        while (p < bytes.length) {
            if ((bytes[p++] & 255) != 255) continue;
            while (p < bytes.length && (bytes[p] & 255) == 255) p++;
            if (p >= bytes.length) throw invalid();
            int marker = bytes[p++] & 255;
            if (marker == 0 || marker == 1 || (marker >= 0xd0 && marker <= 0xd9)) continue;
            if (p + 2 > bytes.length) throw invalid();
            int length = ((bytes[p] & 255) << 8) | (bytes[p + 1] & 255);
            if (length < 2 || length > bytes.length - p) throw invalid();
            if ((marker >= 0xe0 && marker <= 0xef) || marker == 0xfe) metadata.block(length - 2);
            p += length;
        }
    }

    private static void checkPngMetadata(byte[] bytes, int start, int length, int chunk, MetadataBudget metadata) {
        if (chunk != 0x7a545874 && chunk != 0x69545874 && chunk != 0x69434350) return;
        int end = start + length;
        int keywordEnd = terminated(bytes, start, end);
        if (keywordEnd - start < 1 || keywordEnd - start > 79) throw invalid();
        int p = keywordEnd + 1;
        boolean compressed = true;
        if (chunk == 0x69545874) {
            if (p >= end || (bytes[p] != 0 && bytes[p] != 1)) throw invalid();
            compressed = bytes[p++] == 1;
        }
        if (p >= end || bytes[p++] != 0) throw invalid();
        if (chunk == 0x69545874) {
            p = terminated(bytes, p, end) + 1;
            p = terminated(bytes, p, end) + 1;
        }
        if (compressed) metadata.inflate(bytes, p, end - p);
    }

    private static int terminated(byte[] bytes, int start, int end) {
        for (int p = start; p < end; p++) if (bytes[p] == 0) return p;
        throw invalid();
    }

    private static final class MetadataBudget {
        private final long deadline;
        private int remaining = MAX_METADATA_BYTES;
        private int blocks;

        private MetadataBudget(long deadline) { this.deadline = deadline; }

        private void block(int bytes) {
            if (++blocks > MAX_METADATA_BLOCKS) throw new RequestValidationException(RequestErrorCode.IMAGE_METADATA_BLOCK_LIMIT);
            add(bytes);
        }

        private void add(int bytes) {
            if (bytes < 0 || bytes > remaining) throw new RequestValidationException(RequestErrorCode.IMAGE_METADATA_BYTE_LIMIT);
            remaining -= bytes;
            if (System.nanoTime() > deadline) throw new RequestValidationException(RequestErrorCode.IMAGE_DECODING_TIMEOUT);
        }

        private void inflate(byte[] bytes, int offset, int length) {
            java.util.zip.Inflater inflater = new java.util.zip.Inflater();
            byte[] scratch = new byte[8192];
            try {
                inflater.setInput(bytes, offset, length);
                while (!inflater.finished()) {
                    int read = inflater.inflate(scratch);
                    add(read);
                    if (read == 0 && !inflater.finished()) throw invalid();
                }
                if (inflater.getRemaining() != 0) throw invalid();
            } catch (java.util.zip.DataFormatException corrupt) {
                throw new RequestValidationException(RequestErrorCode.IMAGE_METADATA_INVALID, corrupt);
            } finally {
                inflater.end();
            }
        }
    }

    private static IllegalArgumentException invalid() {
        return new RequestValidationException(RequestErrorCode.IMAGE_INVALID);
    }
}

// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.upload;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

public final class ImageMetadataProbe {
    public static void main(String[] args) throws Exception {
        String scenario = args[0];
        if (scenario.equals("baseline")) {
            byte[] bytes = png("zTXt", 2048, 256 * 1024, false, false);
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var reader = ImageIO.getImageReaders(input).next();
                try {
                    reader.setInput(input, false, false);
                    reader.read(0);
                } finally { reader.dispose(); }
            }
            throw new AssertionError("Baseline did not reproduce heap exhaustion");
        }
        if (scenario.equals("formats")) {
            for (String type : new String[]{"jpeg", "png", "gif"}) ImageFileValidator.validate(image(type, false), "image/" + type);
            for (String fixture : new String[]{"valid.webp", "lossless.webp", "animated.webp", "animated.gif"}) {
                try (var input = ImageMetadataProbe.class.getResourceAsStream("/images/" + fixture)) {
                    ImageFileValidator.validate(input.readAllBytes(), fixture.endsWith("gif") ? "image/gif" : "image/webp");
                }
            }
            System.out.println("PASS formats");
            return;
        }
        if (scenario.equals("reader-behavior")) {
            byte[] bytes = png("tEXt", 1, 32, false, true);
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var reader = ImageIO.getImageReaders(input).next();
                try {
                    reader.setInput(input, false, true);
                    reader.read(0);
                    if (!contains(reader.getImageMetadata(0).getAsTree("javax_imageio_png_1.0"), "tEXtEntry"))
                        throw new AssertionError("Expected palette PNG reader to retain text despite ignoreMetadata");
                } finally { reader.dispose(); }
            }
            byte[] gif = gifMetadata(1, 32);
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(gif))) {
                var reader = ImageIO.getImageReaders(input).next();
                try {
                    reader.setInput(input, false, true);
                    reader.read(0);
                    System.out.println("GIF retains comments with ignoreMetadata=" +
                            contains(reader.getImageMetadata(0).getAsTree("javax_imageio_gif_image_1.0"), "CommentExtension"));
                } finally { reader.dispose(); }
            }
            System.out.println("PASS reader-behavior");
            return;
        }
        boolean normal = scenario.startsWith("normal-");
        byte[] bytes;
        String type = "image/png";
        if (scenario.endsWith("webp")) {
            type = "image/webp";
            byte[] profile = normal || scenario.startsWith("corrupt") ? ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData() : new byte[2 * 1024 * 1024];
            if (scenario.startsWith("corrupt")) ByteBuffer.wrap(profile).putInt(Integer.MAX_VALUE);
            bytes = webpMetadata(profile);
        } else if (scenario.endsWith("gif")) {
            type = "image/gif";
            bytes = gifMetadata(normal ? 1 : 1025, 32);
        } else if (scenario.endsWith("jpeg")) {
            type = "image/jpeg";
            bytes = jpegMetadata(normal ? 1 : 32, normal ? 32 : 65531);
        } else {
            String chunk = scenario.contains("itxt") ? "iTXt" : scenario.contains("icc") ? "iCCP" : scenario.contains("text") ? "tEXt" : "zTXt";
            int count = scenario.startsWith("bomb") ? 2048 : scenario.startsWith("aggregate") ? 5 : scenario.startsWith("blocks") ? 1025 : 1;
            int expanded = normal ? (scenario.contains("large") ? 256 * 1024 : 32) : scenario.startsWith("blocks") ? 1 : scenario.startsWith("single") ? 2 * 1024 * 1024 : 256 * 1024;
            if (chunk.equals("iCCP") && normal) {
                bytes = pngChunks(image("png", scenario.contains("palette")), chunk, 1,
                        metadata(chunk, ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData(), false));
            } else bytes = png(chunk, count, expanded, scenario.startsWith("corrupt") || scenario.startsWith("truncated"), scenario.contains("palette"));
            if (scenario.equals("normal-uncompressed-itxt"))
                bytes = pngChunks(image("png", false), "iTXt", 1, "note\0\0\0\0\0hola ñ".getBytes(StandardCharsets.UTF_8));
            if (scenario.equals("bomb-after-ztxt"))
                bytes = pngChunks(image("png", false), "zTXt", 2048, metadata("zTXt", new byte[256 * 1024], false), 0x49454e44);
            if (scenario.startsWith("header")) {
                byte[] data = metadata(chunk, new byte[64], false);
                data[5] = (byte) (chunk.equals("iTXt") ? 2 : 1);
                bytes = pngChunks(image("png", false), chunk, 1, data);
            }
            if (scenario.startsWith("truncated")) {
                byte[] data = metadata(chunk, new byte[64], false);
                bytes = pngChunks(image("png", false), chunk, 1, Arrays.copyOf(data, data.length - 3));
            }
            if (scenario.equals("aggregate-mixed")) {
                bytes = image("png", false);
                for (String block : new String[]{"zTXt", "iTXt", "iCCP", "tEXt", "zTXt"})
                    bytes = pngChunks(bytes, block, 1, metadata(block, new byte[256 * 1024], false));
            }
        }
        boolean rejected = false;
        try { ImageFileValidator.validate(bytes, type); }
        catch (IllegalArgumentException failure) {
            if (normal) throw failure;
            if (!scenario.startsWith("corrupt") && !scenario.startsWith("truncated") && !scenario.startsWith("header") && !failure.getMessage().contains("metadata")) throw failure;
            rejected = true;
        }
        if (normal == rejected) throw new AssertionError("Unexpected validation result for " + scenario);
        System.out.println("PASS " + scenario + " " + (rejected ? "REJECTED" : "ACCEPTED"));
    }

    private static boolean contains(org.w3c.dom.Node node, String name) {
        if (node.getNodeName().equals(name)) return true;
        for (var child = node.getFirstChild(); child != null; child = child.getNextSibling()) if (contains(child, name)) return true;
        return false;
    }

    private static byte[] image(String format, boolean palette) throws Exception {
        var output = new ByteArrayOutputStream();
        var image = new BufferedImage(2, 2, palette ? BufferedImage.TYPE_BYTE_INDEXED : BufferedImage.TYPE_INT_RGB);
        if (!ImageIO.write(image, format, output)) throw new AssertionError("Missing writer");
        return output.toByteArray();
    }

    private static byte[] png(String chunk, int count, int expanded, boolean corrupt, boolean palette) throws Exception {
        byte[] plain = new byte[expanded];
        Arrays.fill(plain, (byte) 'A');
        return pngChunks(image("png", palette), chunk, count, metadata(chunk, plain, corrupt));
    }

    private static byte[] metadata(String chunk, byte[] plain, boolean corrupt) throws Exception {
        var output = new ByteArrayOutputStream();
        output.write("note".getBytes(StandardCharsets.US_ASCII));
        output.write(0);
        if (chunk.equals("tEXt")) { output.write(plain); return output.toByteArray(); }
        if (chunk.equals("iTXt")) output.write(1);
        output.write(0);
        if (chunk.equals("iTXt")) { output.write(0); output.write(0); }
        var compressed = new ByteArrayOutputStream();
        try (var deflater = new DeflaterOutputStream(compressed)) { deflater.write(plain); }
        byte[] data = compressed.toByteArray();
        if (corrupt) data[data.length - 1] ^= 1;
        output.write(data);
        return output.toByteArray();
    }

    private static byte[] pngChunks(byte[] base, String name, int count, byte[] payload) throws Exception {
        return pngChunks(base, name, count, payload, 0x49444154);
    }

    private static byte[] pngChunks(byte[] base, String name, int count, byte[] payload, int beforeChunk) throws Exception {
        int p = 8;
        while (ByteBuffer.wrap(base, p + 4, 4).getInt() != beforeChunk) p += 12 + ByteBuffer.wrap(base, p, 4).getInt();
        var output = new ByteArrayOutputStream();
        output.write(base, 0, p);
        var data = new DataOutputStream(output);
        byte[] type = name.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32(); crc.update(type); crc.update(payload);
        for (int i = 0; i < count; i++) {
            data.writeInt(payload.length); data.write(type); data.write(payload); data.writeInt((int) crc.getValue());
        }
        output.write(base, p, base.length - p);
        return output.toByteArray();
    }

    private static byte[] webpMetadata(byte[] profile) throws Exception {
        byte[] original;
        try (var input = ImageMetadataProbe.class.getResourceAsStream("/images/valid.webp")) { original = input.readAllBytes(); }
        var output = new ByteArrayOutputStream();
        var header = ByteBuffer.allocate(12 + 18 + 8 + profile.length + (profile.length & 1)).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(0x46464952).putInt(header.capacity() + original.length - 20).putInt(0x50424557);
        header.putInt(0x58385056).putInt(10).put(new byte[]{32, 0, 0, 0, 2, 0, 0, 1, 0, 0});
        header.putInt(0x50434349).putInt(profile.length).put(profile);
        if ((profile.length & 1) != 0) header.put((byte) 0);
        output.write(header.array()); output.write(original, 12, original.length - 12);
        return output.toByteArray();
    }

    private static byte[] gifMetadata(int count, int length) throws Exception {
        byte[] base = image("gif", false);
        int p = 13 + ((base[10] & 128) == 0 ? 0 : 3 * (1 << ((base[10] & 7) + 1)));
        var output = new ByteArrayOutputStream(); output.write(base, 0, p);
        for (int i = 0; i < count; i++) {
            output.write(33); output.write(254); output.write(length); output.write(new byte[length]); output.write(0);
        }
        output.write(base, p, base.length - p);
        return output.toByteArray();
    }

    private static byte[] jpegMetadata(int count, int length) throws Exception {
        byte[] base = image("jpeg", false);
        var output = new ByteArrayOutputStream(); output.write(base, 0, 2);
        var data = new DataOutputStream(output);
        for (int i = 0; i < count; i++) { data.writeShort(0xfffe); data.writeShort(length + 2); data.write(new byte[length]); }
        output.write(base, 2, base.length - 2);
        return output.toByteArray();
    }
}

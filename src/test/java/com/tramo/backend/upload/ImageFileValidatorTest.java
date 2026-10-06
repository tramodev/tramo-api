// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.upload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import javax.imageio.ImageIO;
import static org.assertj.core.api.Assertions.*;

class ImageFileValidatorTest {
    @ParameterizedTest
    @ValueSource(strings = {"jpeg", "png", "gif"})
    void acceptsJdkFormats(String format) throws Exception {
        ImageFileValidator.validate(encoded(format, 3, 2), "image/" + format);
    }

    @ParameterizedTest
    @ValueSource(strings = {"valid.webp", "lossless.webp", "animated.webp", "animated.gif"})
    void acceptsWebpAndAnimatedGif(String fixture) throws Exception {
        try (var input = getClass().getResourceAsStream("/images/" + fixture)) {
            assertThat(input).isNotNull();
            ImageFileValidator.validate(input.readAllBytes(), fixture.endsWith("gif") ? "image/gif" : "image/webp");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"jpeg", "png", "gif"})
    void rejectsTruncatedImages(String format) throws Exception {
        byte[] bytes = encoded(format, 3, 2);
        assertThatThrownBy(() -> ImageFileValidator.validate(Arrays.copyOf(bytes, bytes.length - 5), "image/" + format))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsFalseMimeAndBytes() throws Exception {
        byte[] png = encoded("png", 3, 2);
        assertThatThrownBy(() -> ImageFileValidator.validate(png, "image/jpeg")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImageFileValidator.validate(new byte[100], "image/jpeg")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCorruptionAndExcessiveDimensions() throws Exception {
        byte[] corrupt = encoded("png", 3, 2);
        corrupt[corrupt.length / 2] ^= 1;
        assertThatThrownBy(() -> ImageFileValidator.validate(corrupt, "image/png")).isInstanceOf(IllegalArgumentException.class);
        byte[] excessive = encoded("png", 8193, 1);
        assertThatThrownBy(() -> ImageFileValidator.validate(excessive, "image/png")).hasMessageContaining("dimensions");
        byte[] excessivePixels = encoded("png", 4001, 4000);
        assertThatThrownBy(() -> ImageFileValidator.validate(excessivePixels, "image/png")).hasMessageContaining("dimensions");
    }

    @Test
    void rejectsTruncatedWebpAndCorruptAnimatedGif() throws Exception {
        try (var input = getClass().getResourceAsStream("/images/valid.webp")) {
            byte[] webp = input.readAllBytes();
            assertThatThrownBy(() -> ImageFileValidator.validate(Arrays.copyOf(webp, webp.length - 2), "image/webp"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        try (var input = getClass().getResourceAsStream("/images/animated.gif")) {
            byte[] gif = input.readAllBytes();
            byte[] corrupt = Arrays.copyOf(gif, gif.length - 6);
            corrupt[corrupt.length - 1] = 59;
            assertThatThrownBy(() -> ImageFileValidator.validate(corrupt, "image/gif"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"too-many-frames.gif", "too-many-pixels.gif"})
    void rejectsAnimationsExceedingFrameOrTotalPixelBudget(String fixture) throws Exception {
        try (var input = getClass().getResourceAsStream("/images/" + fixture)) {
            byte[] bytes = input.readAllBytes();
            assertThatThrownBy(() -> ImageFileValidator.validate(bytes, "image/gif"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsGifWithPrematureEndOfCompressedPixels() {
        byte[] gif = java.util.HexFormat.of().parseHex("47494638396102000200800000000000ffffff2c00000000020002000002012c003b");
        assertThatThrownBy(() -> ImageFileValidator.validate(gif, "image/gif"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsWebpPayloadDimensionsHiddenBySmallCanvas() throws Exception {
        byte[] original;
        try (var input = getClass().getResourceAsStream("/images/valid.webp")) { original = input.readAllBytes(); }
        byte[] extended = new byte[original.length + 18];
        var buffer = java.nio.ByteBuffer.wrap(extended).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x46464952).putInt(extended.length - 8).putInt(0x50424557);
        buffer.putInt(0x58385056).putInt(10).put(new byte[10]);
        buffer.put(original, 12, original.length - 12);
        int payloadOffset = 12 + 18 + 8;
        buffer.putShort(payloadOffset + 6, (short) 16383);
        assertThatThrownBy(() -> ImageFileValidator.validate(extended, "image/webp"))
                .hasMessageContaining("dimensions");
    }

    private byte[] encoded(String format, int width, int height) throws Exception {
        var output = new ByteArrayOutputStream();
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        assertThat(ImageIO.write(image, format, output)).isTrue();
        image.flush();
        return output.toByteArray();
    }
}

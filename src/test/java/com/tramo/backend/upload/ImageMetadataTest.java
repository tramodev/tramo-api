package com.tramo.backend.upload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

class ImageMetadataTest {
    @TempDir Path directory;

    @Test
    void reproducesOriginalReaderHeapExhaustionOnlyInChildJvm() throws Exception {
        Result result = run("baseline");
        assertThat(result.exit()).isNotZero();
        assertThat(result.output()).contains("OutOfMemoryError");
    }

    @ParameterizedTest
    @ValueSource(strings = {"bomb-ztxt", "bomb-after-ztxt", "single-ztxt", "aggregate-ztxt", "single-itxt", "aggregate-itxt",
            "single-icc", "aggregate-icc", "aggregate-mixed", "blocks-ztxt", "single-text",
            "corrupt-ztxt", "corrupt-itxt", "corrupt-icc", "header-ztxt", "header-itxt", "header-icc", "truncated-ztxt", "truncated-itxt", "truncated-icc",
            "normal-ztxt", "normal-itxt", "normal-large-ztxt", "normal-large-itxt", "normal-uncompressed-itxt", "normal-icc", "normal-text", "normal-palette-ztxt", "normal-palette-icc",
            "normal-webp", "single-webp", "corrupt-webp", "normal-gif", "blocks-gif", "normal-jpeg", "single-jpeg", "formats", "reader-behavior"})
    void boundsMetadataWithoutExhaustingHeap(String scenario) throws Exception {
        Result result = run(scenario);
        assertThat(result.output()).doesNotContain("OutOfMemoryError", "Java heap space", "HEAP_EXHAUSTED");
        assertThat(result.exit()).withFailMessage(result.output()).isZero();
        assertThat(result.output()).contains("PASS " + scenario);
    }

    private Result run(String scenario) throws Exception {
        Path output = directory.resolve(scenario + ".log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx128m", "-XX:+ExitOnOutOfMemoryError", "-Djava.awt.headless=true", "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                ImageMetadataProbe.class.getName(), scenario).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(process.waitFor(25, TimeUnit.SECONDS)).withFailMessage("Child JVM timed out: " + scenario).isTrue();
            return new Result(process.exitValue(), Files.readString(output));
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        }
    }

    private record Result(int exit, String output) {}
}

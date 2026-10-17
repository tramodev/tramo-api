package com.tramo.backend.project.snapshot;

import com.tramo.backend.exception.PayloadTooLargeException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.JsonToken;
import tools.jackson.core.JacksonException;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.Optional;

@Component
public class SnapshotLimits {
    private final int maxBytes;
    private final int maxTrails;
    private final int maxItems;
    private final int maxReferences;
    private final ObjectMapper mapper;

    public SnapshotLimits(@Value("${app.snapshot.max-bytes:8388608}") int maxBytes,
                          @Value("${app.snapshot.max-trails:200}") int maxTrails,
                          @Value("${app.snapshot.max-items:2000}") int maxItems,
                          @Value("${app.snapshot.max-references:5000}") int maxReferences,
                          ObjectMapper mapper) {
        if (maxBytes < 1 || maxTrails < 1 || maxItems < 1 || maxReferences < 1) {
            throw new IllegalArgumentException("Snapshot limits must be positive");
        }
        this.maxBytes = maxBytes;
        this.maxTrails = maxTrails;
        this.maxItems = maxItems;
        this.maxReferences = maxReferences;
        this.mapper = mapper;
    }

    public int maxBytes() { return maxBytes; }
    public int maxTrails() { return maxTrails; }
    public int maxItems() { return maxItems; }
    public int maxReferences() { return maxReferences; }

    public ProjectSnapshotData read(String content) {
        if (content == null || content.length() > maxBytes
                || content.getBytes(StandardCharsets.UTF_8).length > maxBytes) throw exceeded();
        try (var parser = mapper.createParser(content)) {
            long objects = 0;
            while (parser.nextToken() != null) {
                if (parser.currentToken() == JsonToken.START_OBJECT
                        && ++objects > 1L + maxTrails + maxReferences) throw exceeded();
            }
        }
        ProjectSnapshotData data = mapper.readValue(content, ProjectSnapshotData.class);
        validate(data);
        return data;
    }

    public Optional<ProjectSnapshotData> readPreview(String content) {
        if (content == null) return Optional.empty();
        try {
            return Optional.of(read(content));
        } catch (PayloadTooLargeException | JacksonException | IllegalArgumentException | NullPointerException invalid) {
            return Optional.empty();
        }
    }

    public void validate(ProjectSnapshotData data) {
        if (data.trails() == null || data.trails().size() > maxTrails) throw exceeded();
        long references = data.looseItems().size();
        Set<Long> ids = new HashSet<>();
        for (var trail : data.trails()) {
            references += trail.items().size();
            if (references > maxReferences) throw exceeded();
            for (var item : trail.items()) ids.add(item.id());
        }
        for (var item : data.looseItems()) ids.add(item.id());
        if (references > maxReferences || ids.size() > maxItems) throw exceeded();
    }

    public String write(ProjectSnapshotData data) {
        validate(data);
        return writeValue(data);
    }

    public String writeValue(Object data) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        mapper.writeValue(new OutputStream() {
            private int count;
            @Override public void write(int value) {
                if (count == maxBytes) throw exceeded();
                count++;
                bytes.write(value);
            }
            @Override public void write(byte[] value, int offset, int length) {
                if (length > maxBytes - count) throw exceeded();
                count += length;
                bytes.write(value, offset, length);
            }
        }, data);
        return bytes.toString(StandardCharsets.UTF_8);
    }

    public static PayloadTooLargeException exceeded() {
        return new PayloadTooLargeException("Project snapshot exceeds the configured operation limits");
    }
}

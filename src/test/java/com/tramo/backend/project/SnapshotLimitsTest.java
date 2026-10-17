package com.tramo.backend.project;

import com.tramo.backend.exception.PayloadTooLargeException;
import com.tramo.backend.project.snapshot.ProjectSnapshotData;
import com.tramo.backend.project.snapshot.SnapshotLimits;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class SnapshotLimitsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private SnapshotLimits limits(int bytes) {
        return new SnapshotLimits(bytes, 1, 1, 1, mapper);
    }

    @Test
    void serializationAndReadsEnforceExactUtf8ByteBoundary() {
        ProjectSnapshotData data = new ProjectSnapshotData(6, 1L, "é\n\"", "Description", "published",
                null, null, List.of(), List.of(), "NONE");
        String json = limits(4096).write(data);
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        assertThat(limits(bytes).write(data)).isEqualTo(json);
        assertThat(limits(bytes).read(json)).isEqualTo(data);
        assertThatThrownBy(() -> limits(bytes - 1).write(data)).isInstanceOf(PayloadTooLargeException.class);
        assertThatThrownBy(() -> limits(bytes - 1).read(json)).isInstanceOf(PayloadTooLargeException.class);
    }
}

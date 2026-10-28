package com.tramo.backend.trail;

import com.tramo.backend.trail.dto.ItemContentRequestDTO;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ItemContentRequestTest {
    @Test
    void requiresEditorContentAndConflictBaseline() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var request = new ItemContentRequestDTO();
            request.setExpectedContent("");
            for (String content : new String[]{null, "", " "}) {
                request.setContent(content);
                assertThat(validator.validate(request)).isNotEmpty();
            }
            request.setContent("{\"root\":{\"type\":\"root\",\"children\":[]}}");
            request.setExpectedContent(null);
            assertThat(validator.validate(request)).isNotEmpty();
            request.setExpectedContent("");
            assertThat(validator.validate(request)).isEmpty();
        }
    }
}

package com.tramo.backend.comment.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CommentRequestDTO {
    @NotBlank(message = "COMMENT_REQUIRED")
    private String content;

    private Long parentId;
}

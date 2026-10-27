package com.tramo.backend.comment.dto;

import java.util.List;

public record CommentPageDTO(List<CommentDTO> content, boolean hasMore, int page) {}

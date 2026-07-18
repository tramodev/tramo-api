package com.tramo.backend.comment.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@AllArgsConstructor
public class CommentDTO {
    private Long id;
    private String content;
    private boolean deleted;
    private String authorUsername;
    private String authorAvatar;
    private Long parentId;
    private Date createdDate;
    private boolean canDelete;
}

package com.mypath.backend.project.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class PublicIdeaDTO {
    private Long id;
    private String title;
    private String type;
    private String content;
}

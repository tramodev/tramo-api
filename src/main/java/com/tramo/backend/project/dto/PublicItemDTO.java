package com.tramo.backend.project.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class PublicItemDTO {
    private Long id;
    private String title;
    private String type;
    private String content;
    private String titleAlign;
}

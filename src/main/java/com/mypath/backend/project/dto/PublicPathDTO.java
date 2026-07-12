package com.mypath.backend.project.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@AllArgsConstructor
public class PublicPathDTO {
    private Long id;
    private String title;
    private List<PublicIdeaDTO> ideas;
}

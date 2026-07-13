package com.mypath.backend.project.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class BadgeDTO {
    private String code;
    private String name;
    private String description;
    private boolean earned;
    private long progress;
    private long target;
}

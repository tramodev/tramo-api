package com.tramo.backend.trail.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@AllArgsConstructor
public class ItemResponseDTO {
    private Long id;
    private String title;
    private String type;
    private String titleAlign;
    private Date createdDate;
    private Date modifiedDate;
}

package com.tramo.backend.project.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@AllArgsConstructor
public class ProfileStatsBundleDTO {
    private ProfileStatsDTO stats;
    private List<BadgeDTO> badges;
}

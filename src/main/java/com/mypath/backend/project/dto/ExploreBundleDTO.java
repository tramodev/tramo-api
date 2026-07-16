package com.mypath.backend.project.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@AllArgsConstructor
public class ExploreBundleDTO {
    private List<ProjectFeedItemDTO> feed;
    private boolean hasMore;
    private ProjectFeedItemDTO featured;
    private List<TagCountDTO> hotTopics;
    private List<AuthorCountDTO> activeAuthors;
}

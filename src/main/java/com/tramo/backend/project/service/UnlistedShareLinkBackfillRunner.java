// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.service;

import com.tramo.backend.project.repository.ProjectRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class UnlistedShareLinkBackfillRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(UnlistedShareLinkBackfillRunner.class);

    private final JdbcTemplate jdbcTemplate;
    private final ProjectRepository projectRepository;
    private final ProjectShareLinkService shareLinkService;

    public UnlistedShareLinkBackfillRunner(JdbcTemplate jdbcTemplate, ProjectRepository projectRepository,
                                           ProjectShareLinkService shareLinkService) {
        this.jdbcTemplate = jdbcTemplate;
        this.projectRepository = projectRepository;
        this.shareLinkService = shareLinkService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Long> ids = jdbcTemplate.queryForList(
                "UPDATE project SET visibility = 'private' WHERE visibility = 'unlisted' RETURNING id", Long.class);
        projectRepository.findAllById(ids).forEach(shareLinkService::convertUnlisted);
        if (!ids.isEmpty()) log.info("Unlisted backfill converted {} projects to private view links", ids.size());
    }
}

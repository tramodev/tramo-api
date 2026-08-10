package com.tramo.backend.project.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;




@Component
public class ProjectSnapshotBackfillRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(ProjectSnapshotBackfillRunner.class);

    private final ProjectPublishService publishService;

    public ProjectSnapshotBackfillRunner(ProjectPublishService publishService) {
        this.publishService = publishService;
    }

    @Override
    public void run(ApplicationArguments args) {
        publishService.backfillMissingPublishSnapshots();
        log.info("ProjectSnapshot backfill check complete");
    }
}

package com.tramo.backend.trail.service;

import com.tramo.backend.trail.repository.ItemContentRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class ItemTextStatsBackfillRunner implements ApplicationRunner {
    private final ItemContentRepository repository;

    public ItemTextStatsBackfillRunner(ItemContentRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) {
        var batch = repository.findTop100ByWordCountIsNullOrderByIdAsc();
        while (!batch.isEmpty()) {
            batch.forEach(content -> content.setContent(content.getContent()));
            repository.saveAllAndFlush(batch);
            batch = repository.findTop100ByWordCountIsNullOrderByIdAsc();
        }
    }
}

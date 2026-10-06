// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.trail.service;

import com.tramo.backend.trail.entity.Item;
import com.tramo.backend.trail.entity.ItemImageReference;
import com.tramo.backend.trail.repository.ItemImageReferenceRepository;
import com.tramo.backend.trail.repository.ItemRepository;
import com.tramo.backend.upload.R2Client;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Set;



@Component
public class ItemImageReferenceBackfillRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(ItemImageReferenceBackfillRunner.class);

    private final ItemRepository itemRepository;
    private final ItemImageReferenceRepository itemImageReferenceRepository;
    private final R2Client r2Client;

    public ItemImageReferenceBackfillRunner(ItemRepository itemRepository,
                                             ItemImageReferenceRepository itemImageReferenceRepository,
                                             R2Client r2Client) {
        this.itemRepository = itemRepository;
        this.itemImageReferenceRepository = itemImageReferenceRepository;
        this.r2Client = r2Client;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (itemImageReferenceRepository.count() > 0) {
            return;
        }
        int backfilled = 0;
        for (Item item : itemRepository.findAll()) {
            String content = item.getContent() != null ? item.getContent().getContent() : null;
            Set<String> urls = r2Client.extractReferencedUrls(content);
            for (String url : urls) {
                ItemImageReference reference = new ItemImageReference();
                reference.setItem(item);
                reference.setUrl(url);
                itemImageReferenceRepository.save(reference);
            }
            backfilled += urls.size();
        }
        log.info("ItemImageReference backfill inserted {} rows", backfilled);
    }
}

package com.tramo.backend.tag.service;

import com.tramo.backend.tag.entity.Tag;
import com.tramo.backend.tag.repository.TagRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;


@Component
public class TagSeeder implements ApplicationRunner {

    private final TagRepository tagRepository;
    private final List<String> seedNames;

    public TagSeeder(TagRepository tagRepository,
                      @Value("${app.tags.seed:}") List<String> seedNames) {
        this.tagRepository = tagRepository;
        this.seedNames = seedNames;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean changed = false;
        for (String rawName : seedNames) {
            String name = rawName.trim().toLowerCase();
            if (name.isEmpty()) continue;

            Tag tag = tagRepository.findByName(name).orElse(null);
            if (tag == null) {
                tagRepository.save(new Tag(name, true, null));
                changed = true;
            } else if (!tag.isOfficial()) {
                tag.setOfficial(true);
                tagRepository.save(tag);
                changed = true;
            }
        }
        if (changed) {
        }
    }
}

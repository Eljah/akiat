package io.github.eljah.akiat.service;

import io.github.eljah.akiat.repo.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.Comparator;

@Component
@ConditionalOnProperty(prefix="akiat.generation",name="enabled",havingValue="true")
public class GenerationScheduler {
    private final References references;
    private final Stories stories;
    private final StoryGenerator generator;
    public GenerationScheduler(References references, Stories stories, StoryGenerator generator) {
        this.references=references; this.stories=stories; this.generator=generator;
    }

    /** Twice weekly by default; creates only an unpublished DRAFT. */
    @Scheduled(cron="${akiat.generation.cron}",zone="${akiat.generation.time-zone}")
    public void produceDraft() {
        references.findAll().stream().filter(r->r.reviewed)
            .min(Comparator.comparingLong(r->stories.countByReferenceId(r.id)))
            .ifPresent(r->generator.generate(r.id));
    }
}

package io.github.eljah.akiat.service;

import io.github.eljah.akiat.domain.EmotionalReference;
import io.github.eljah.akiat.repo.References;
import io.github.eljah.akiat.web.Dtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

@Service
public class ReferenceService {
    private final References references;
    public ReferenceService(References references) { this.references = references; }

    @Transactional
    public Dtos.ReferenceView create(Dtos.ReferenceInput input) {
        var reference = new EmotionalReference(input.title().trim(), input.author().trim(), input.sourceUrl(),
            input.emotionalProfile().trim(), input.notes(), input.audienceAge()==null?5:input.audienceAge());
        return Dtos.ReferenceView.from(references.save(reference));
    }

    @Transactional(readOnly=true)
    public List<Dtos.ReferenceView> list() {
        return references.findAll().stream().map(Dtos.ReferenceView::from).toList();
    }

    @Transactional
    public Dtos.ReferenceView review(UUID id) {
        EmotionalReference reference=references.findById(id)
            .orElseThrow(()->new NotFoundException("Reference not found: "+id));
        if (reference.emotionalProfile==null || reference.emotionalProfile.isBlank()) {
            throw new IllegalStateException("Emotional profile is required");
        }
        reference.reviewed=true;
        return Dtos.ReferenceView.from(references.save(reference));
    }
}

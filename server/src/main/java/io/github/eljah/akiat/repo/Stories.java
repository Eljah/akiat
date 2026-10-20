package io.github.eljah.akiat.repo;

import io.github.eljah.akiat.domain.Story;
import io.github.eljah.akiat.domain.StoryState;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
import java.util.List;
import java.util.Optional;

public interface Stories extends JpaRepository<Story, UUID> {
    List<Story> findByStateOrderByPublishedAtDesc(StoryState state);
    List<Story> findByStateOrderByCreatedAtDesc(StoryState state);
    List<Story> findTop20ByOrderByCreatedAtDesc();
    long countByReferenceId(UUID id);
    Optional<Story> findByExternalId(String externalId);
}

package io.github.eljah.akiat.repo;

import io.github.eljah.akiat.domain.EmotionalReference;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface References extends JpaRepository<EmotionalReference, UUID> {}

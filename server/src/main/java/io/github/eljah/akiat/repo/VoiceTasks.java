package io.github.eljah.akiat.repo;

import io.github.eljah.akiat.domain.TaskState;
import io.github.eljah.akiat.domain.VoiceTask;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
import java.util.Optional;

public interface VoiceTasks extends JpaRepository<VoiceTask, UUID> {
    Optional<VoiceTask> findFirstByStateOrderByCreatedAtAsc(TaskState state);
    boolean existsByStoryIdAndState(UUID id, TaskState state);
}

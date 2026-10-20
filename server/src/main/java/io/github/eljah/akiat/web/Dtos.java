package io.github.eljah.akiat.web;

import io.github.eljah.akiat.domain.*;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;

public final class Dtos {
    private Dtos() {}

    public record ReferenceInput(@NotBlank String title, @NotBlank String author, String sourceUrl,
                                 @NotBlank String emotionalProfile, String notes,
                                 @Min(2) @Max(17) Integer audienceAge) {}
    public record ReferenceView(UUID id, String title, String author, String sourceUrl,
                                String emotionalProfile, String notes, int audienceAge, boolean reviewed) {
        public static ReferenceView from(EmotionalReference r) {
            return new ReferenceView(r.id, r.title, r.author, r.sourceUrl, r.emotionalProfile,
                r.notes, r.audienceAge==null?5:r.audienceAge, r.reviewed);
        }
    }
    public record StoryView(UUID id, UUID referenceId, String referenceTitle, String title, String genre,
                            String outline, String tatText, StoryState state, boolean languageApproved,
                            boolean audioApproved, boolean rightsCleared, Long audioLength,
                            Integer durationSeconds, Instant createdAt, Instant publishedAt) {
        public static StoryView from(Story s) {
            return new StoryView(s.id, s.reference==null?null:s.reference.id,
                s.reference==null?null:s.reference.title, s.title, s.genre, s.outline, s.tatText,
                s.state, s.languageApproved, s.audioApproved, s.rightsCleared,
                s.audioLength, s.durationSeconds, s.createdAt, s.publishedAt);
        }
    }
    public record TextChange(@NotBlank String title, @NotBlank String tatText, String outline, String genre) {}
    public record TaskView(UUID id, UUID storyId, String title, String text,
                           TaskState state, String claimedBy) {
        public static TaskView from(VoiceTask t) {
            return new TaskView(t.id, t.story.id, t.story.title, t.story.tatText, t.state, t.claimedBy);
        }
    }
    public record GenerationRequest(@NotNull UUID referenceId) {}
}

package io.github.eljah.akiat.mcp;

import io.github.eljah.akiat.domain.StoryState;
import io.github.eljah.akiat.service.*;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;
import java.util.UUID;

/** Codex writes literary text; MCP receives it, stages TTS jobs and controls guarded RSS publication. */
@Component
public class EditorialTools {
    private final ReferenceService refs;
    private final EditorialService stories;
    private final ReferenceDiscovery search;

    public EditorialTools(ReferenceService refs, EditorialService stories,
                          ReferenceDiscovery search) {
        this.refs=refs;this.stories=stories;this.search=search;
    }

    @McpTool(description="List verified and unverified emotional references available to the Akiat literary editorial team")
    public String list_emotional_references() { return refs.list().toString(); }

    @McpTool(description="List unpublished fairy tale drafts for language review")
    public String list_akiat_drafts() { return stories.list(StoryState.DRAFT).toString(); }

    @McpTool(description="Get one Akiat story including its editorial status and Tatar text")
    public String get_akiat_story(String storyId) { return stories.get(UUID.fromString(storyId)).toString(); }

    @McpTool(description="Search publicly indexed descriptions of Russian authored children's fairy tales; returns candidates only, not copyrighted full texts")
    public String search_fairy_tale_references(String searchQuery) { return search.search(searchQuery).toString(); }

    @McpTool(description="Save a COMPLETE, original Tatar fairy tale ALREADY written by Codex. Does not call any LLM. Returns a DRAFT; uses a stable clientId to prevent duplicate episodes.")
    public io.github.eljah.akiat.web.Dtos.StoryView submit_tatar_story(
            io.github.eljah.akiat.web.Dtos.CodexSubmission input) {
        return stories.submitFromCodex(input);
    }

    @McpTool(description="Approve the literary Tatar text after actual editorial review. Only DRAFT stories; required before generating audio.")
    public io.github.eljah.akiat.web.Dtos.StoryView approve_tatar_text(String storyId) {
        return stories.approveLanguage(UUID.fromString(storyId));
    }

    @McpTool(description="Queue a TTS production task for an editorially approved Tatar fairy tale. The asynchronous Python worker picks it up and uploads the MP3. Safe to retry.")
    public io.github.eljah.akiat.web.Dtos.TaskView start_tatar_narration(String storyId) {
        return stories.queueTts(UUID.fromString(storyId));
    }

    @McpTool(description="Get the status of one asynchronous TTS narration job. Check this before audio approval.")
    public io.github.eljah.akiat.web.Dtos.TaskView get_tatar_narration_job(String taskId) {
        return stories.getTtsTask(UUID.fromString(taskId));
    }

    @McpTool(description="Approve narration only after an authorized editor actually listened to the stored audio and checked pronunciation, loudness, quality and age suitability.")
    public io.github.eljah.akiat.web.Dtos.StoryView approve_tatar_narration(String storyId) {
        return stories.approveAudio(UUID.fromString(storyId));
    }

    @McpTool(description="Record explicit editor confirmation that story text, narrator voice/model and sound recordings have the necessary publication rights. Do not call without verification.")
    public io.github.eljah.akiat.web.Dtos.StoryView confirm_tatar_publication_rights(String storyId) {
        return stories.confirmRights(UUID.fromString(storyId));
    }

    @McpTool(description="FINAL PUBLIC RELEASE: add a READY, rights-cleared episode to the public RSS feed. Only call after explicit approval by the human user. confirmation MUST be PUBLISH:<storyId>.")
    public io.github.eljah.akiat.web.Dtos.StoryView publish_tatar_episode(String storyId, String confirmation) {
        UUID id=UUID.fromString(storyId);
        if (!("PUBLISH:"+id).equals(confirmation)) {
            throw new IllegalArgumentException("Missing exact publication confirmation: PUBLISH:<storyId>");
        }
        return stories.publish(id);
    }
}

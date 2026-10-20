package io.github.eljah.akiat.mcp;

import io.github.eljah.akiat.domain.StoryState;
import io.github.eljah.akiat.service.*;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import java.util.UUID;

/** MCP can inspect, search and draft. Publishing requires deliberate REST editorial action. */
@Component
public class EditorialTools {
    private final ReferenceService refs;
    private final EditorialService stories;
    private final StoryGenerator generator;
    private final ReferenceDiscovery search;

    public EditorialTools(ReferenceService refs, EditorialService stories,
                          StoryGenerator generator, ReferenceDiscovery search) {
        this.refs=refs;this.stories=stories;this.generator=generator;this.search=search;
    }

    @Tool(description="List verified and unverified emotional references available to the Akiat literary editorial team")
    public String list_emotional_references() { return refs.list().toString(); }

    @Tool(description="List unpublished fairy tale drafts for language review")
    public String list_akiat_drafts() { return stories.list(StoryState.DRAFT).toString(); }

    @Tool(description="Get one Akiat story including its editorial status and Tatar text")
    public String get_akiat_story(String storyId) { return stories.get(UUID.fromString(storyId)).toString(); }

    @Tool(description="Search publicly indexed descriptions of Russian authored children's fairy tales; returns candidates only, not copyrighted full texts")
    public String search_fairy_tale_references(String searchQuery) { return search.search(searchQuery).toString(); }

    @Tool(description="Generate one independent Tatar story DRAFT from a previously human-reviewed abstract emotional profile; NEVER publishes")
    public String create_akiat_draft(String referenceId) { return generator.generate(UUID.fromString(referenceId)).toString(); }
}

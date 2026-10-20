package io.github.eljah.akiat;

import io.github.eljah.akiat.domain.StoryState;
import io.modelcontextprotocol.server.McpSyncServer;
import java.util.stream.Collectors;
import java.util.Set;
import io.github.eljah.akiat.mcp.EditorialTools;
import io.github.eljah.akiat.service.EditorialService;
import io.github.eljah.akiat.web.Dtos;
import io.github.eljah.akiat.web.PublicFeedController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={
    "akiat.admin.password=test-secret",
    "spring.datasource.url=jdbc:h2:mem:codexpubtest;DB_CLOSE_DELAY=-1",
    "akiat.feed.base-url=https://example.org"
})
class CodexMcpPublishingFlowTests {
    @Autowired EditorialTools mcp;
    @Autowired EditorialService editorial;
    @Autowired PublicFeedController feed;
    @Autowired McpSyncServer mcpServer;

    @Test
    void mcpServerAdvertisesCodexPublicationTools() {
        Set<String> names=mcpServer.listTools().stream().map(tool -> tool.name()).collect(Collectors.toSet());
        for(String name: Set.of("submit_tatar_story", "get_akiat_story", "approve_tatar_text", "start_tatar_narration", "get_tatar_narration_job", "approve_tatar_narration", "confirm_tatar_publication_rights", "publish_tatar_episode")) {
            assertTrue(names.contains(name), "MCP server does not advertise: "+name+"; found="+names);
        }
    }

    @Test
    void codexControlsPublicationWithoutJavaTextGeneration() throws Exception {
        String body="Бу яңа татарча әкият, аның бөтен вакыйгалары һәм геройлары мөстәкыйль. ".repeat(12);
        Dtos.CodexSubmission submission=new Dtos.CodexSubmission(
            "codex:test:mcp-release-001", "Агач белән ай", body,
            "quiet curiosity, subtle warmth, gentle absurd", "References are emotional only",
            "An original story with new situations", "quiet_fantasy", 6);
        var draft=mcp.submit_tatar_story(submission);
        assertEquals(StoryState.DRAFT, draft.state());
        assertEquals(draft.id(), mcp.submit_tatar_story(submission).id());
        assertThrows(IllegalStateException.class, () -> mcp.submit_tatar_story(
            new Dtos.CodexSubmission(submission.clientId(),submission.title(),"another text ".repeat(40),
                submission.emotionalProfile(),submission.referenceNotes(),submission.outline(),
                submission.genre(),submission.audienceAge())));
        String id=draft.id().toString();
        assertThrows(IllegalStateException.class, () -> mcp.start_tatar_narration(id));
        assertThrows(IllegalStateException.class, () -> mcp.publish_tatar_episode(id,"PUBLISH:"+id));
        mcp.approve_tatar_text(id);
        var task=mcp.start_tatar_narration(id);
        assertEquals(task.id(), mcp.start_tatar_narration(id).id());
        assertEquals(task.id(), mcp.get_tatar_narration_job(task.id().toString()).id());
        var claimed=editorial.claimTts("worker-for-test");
        assertEquals(task.id(),claimed.id());
        editorial.audioReceived(task.id(),id+".mp3", 19000L, 120);
        assertThrows(IllegalStateException.class, () -> mcp.publish_tatar_episode(id,"PUBLISH:"+id));
        mcp.approve_tatar_narration(id);
        mcp.confirm_tatar_publication_rights(id);
        assertThrows(IllegalArgumentException.class, () -> mcp.publish_tatar_episode(id,"yes"));
        assertEquals(StoryState.PUBLISHED,mcp.publish_tatar_episode(id,"PUBLISH:"+id).state());
        assertEquals(StoryState.PUBLISHED,mcp.publish_tatar_episode(id,"PUBLISH:"+id).state());
        assertTrue(feed.feed().contains("Агач белән ай"));
    }
}

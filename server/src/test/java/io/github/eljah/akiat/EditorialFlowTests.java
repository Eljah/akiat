package io.github.eljah.akiat;

import io.github.eljah.akiat.domain.*;
import io.github.eljah.akiat.repo.*;
import io.github.eljah.akiat.service.*;
import io.github.eljah.akiat.web.PublicFeedController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={
    "akiat.admin.password=development-test-pass",
    "spring.datasource.url=jdbc:h2:mem:akiattest;DB_CLOSE_DELAY=-1",
    "akiat.feed.base-url=https://example.org/akiat",
    "akiat.feed.image-url=https://example.org/cover.jpg"
})
class EditorialFlowTests {
    @Autowired References references;
    @Autowired Stories stories;
    @Autowired EditorialService editing;
    @Autowired PublicFeedController rss;

    @Test
    void workflowRequiresAllThreeHumanApprovalsBeforeRssPublication() throws Exception {
        var reference=references.save(new EmotionalReference("Эмоциональный ориентир", "Исследователь",
            "https://example.org/book", "Warm absurdity with no moral", "No copied plot",5));
        Story tale=stories.save(new Story(reference,"Кызыклы хикәя", "kind_absurd",
            "new original concept", "Татарча әкият тексты, аны балалар укый. ".repeat(5)));
        UUID id=tale.id;
        assertThrows(IllegalStateException.class,()->editing.publish(id));
        editing.approveLanguage(id);
        var job=editing.queueTts(id);
        var claim=editing.claimTts("test-worker");
        assertNotNull(claim);
        assertEquals(job.id(),claim.id());
        editing.audioReceived(claim.id(),id+".mp3",12345,123);
        editing.approveAudio(id);
        assertThrows(IllegalStateException.class,()->editing.publish(id));
        editing.confirmRights(id);
        editing.publish(id);
        String feed=rss.feed();
        assertTrue(feed.contains("Кызыклы хикәя"));
        assertTrue(feed.contains("https://example.org/akiat/media/"+id+".mp3"));
        assertTrue(feed.contains("audio/mpeg"));
        assertTrue(feed.contains("length=\"12345\""));
    }
}

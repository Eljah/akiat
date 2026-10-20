package io.github.eljah.akiat.service;

import io.github.eljah.akiat.domain.*;
import io.github.eljah.akiat.repo.*;
import io.github.eljah.akiat.web.Dtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;
import java.util.List;

@Service
public class StoryGenerator {
    private final Stories stories;
    private final References references;
    private final LlmClient llm;
    public StoryGenerator(Stories stories, References references, LlmClient llm) {
        this.stories=stories;
        this.references=references;
        this.llm=llm;
    }

    /** No copyrighted text is supplied to the generator, only an independently reviewed abstract emotional profile. */
    public Dtos.StoryView generate(UUID referenceId) {
        EmotionalReference reference=references.findById(referenceId)
            .orElseThrow(()->new NotFoundException("Reference not found: "+referenceId));
        if (!reference.reviewed) {
            throw new IllegalStateException("An editor must review the emotional reference first");
        }
        String recent=stories.findTop20ByOrderByCreatedAtDesc().stream()
            .map(s->s.title).reduce("",(a,b)->a+"\n"+b);
        String originality="You create entirely original Tatar-language children's literary fairy tales. "
            +"The source of inspiration is only a generic emotional profile, NEVER a plot. "
            +"Do not reuse the reference's characters, plot turns, prose, jokes, structural arrangement, "
            +"memorable scenes, or protected expression. Do not use direct moralizing. "
            +"Keep age-appropriate emotions and everyday contemporary settings. "
            +"Write in natural literary Tatar Cyrillic, not Russian translated literally.";
        String profile="Эмоциональный профиль: "+reference.emotionalProfile
            +"\nВозраст: "+reference.audienceAge
            +"\nУже существующие названия, нельзя повторять: "+recent;
        String concept=llm.complete(originality,
            "Create a brand-new story concept matching ONLY the emotional qualities below. "
            +"Write a Tatar title as the FIRST LINE and then a distinct 120-200 word Tatar synopsis. "
            +"No preface and no Markdown header.\n"+profile);
        String[] lines=concept.split("\\R",2);
        String title=lines[0].replaceAll("^[#*\\s]+","").strip();
        if (title.length()<2 || title.length()>350 || lines.length<2) {
            throw new IllegalStateException("Concept has no usable title / outline; no story saved");
        }
        String tatText=llm.complete(originality,
            "Write an original Tatar fairy tale for a child around age "+reference.audienceAge
            +", based solely on this NEW original concept. About 600-1100 Tatar words, "
            +"subtle emotions, natural dialogue, original ending without an explicit lesson. "
            +"Output ONLY the Tatar story including a title as the first line.\n"+concept);
        if (tatText.length()<900) throw new IllegalStateException("Generated tale too short; not stored");
        Story story=new Story(reference,title,"modern_original",concept,tatText);
        return Dtos.StoryView.from(stories.save(story));
    }
}

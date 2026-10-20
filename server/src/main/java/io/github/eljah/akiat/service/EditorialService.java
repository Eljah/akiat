package io.github.eljah.akiat.service;

import io.github.eljah.akiat.domain.*;
import io.github.eljah.akiat.repo.*;
import io.github.eljah.akiat.web.Dtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service
public class EditorialService {
    private final Stories stories;
    private final VoiceTasks tasks;
    public EditorialService(Stories stories, VoiceTasks tasks) { this.stories=stories; this.tasks=tasks; }

    @Transactional(readOnly=true)
    public List<Dtos.StoryView> list(StoryState state) {
        List<Story> result=state==null?stories.findAll():stories.findByStateOrderByCreatedAtDesc(state);
        return result.stream().map(Dtos.StoryView::from).toList();
    }

    @Transactional(readOnly=true)
    public Dtos.StoryView get(UUID id) { return Dtos.StoryView.from(require(id)); }

    /**
     * Accept a Codex-written Tatar tale. Stable clientId guarantees retries do not produce
     * duplicate podcast episodes; using the same key with different text is an error.
     */
    @Transactional
    public Dtos.StoryView submitFromCodex(Dtos.CodexSubmission input) {
        Objects.requireNonNull(input, "submission is required");
        String key=Objects.requireNonNull(input.clientId(), "clientId is required").strip();
        String title=Objects.requireNonNull(input.title(), "title is required").strip();
        String body=Objects.requireNonNull(input.tatText(), "tatText is required").strip();
        if (key.length()<3 || key.length()>180 || title.length()<2 || title.length()>350
                || body.length()<100 || body.length()>200000) {
            throw new IllegalArgumentException("Invalid clientId/title/tatText lengths");
        }
        if (input.audienceAge()!=null && (input.audienceAge()<2 || input.audienceAge()>17)) {
            throw new IllegalArgumentException("audienceAge must be between 2 and 17");
        }
        Optional<Story> prior=stories.findByExternalId(key);
        if (prior.isPresent()) {
            Story existing=prior.get();
            if (!Objects.equals(existing.title,title) || !Objects.equals(existing.tatText,body)) {
                throw new IllegalStateException("clientId already exists with different title/text");
            }
            return Dtos.StoryView.from(existing);
        }
        Story story=new Story(null,title,
            input.genre()==null?"modern_original":input.genre(),
            input.outline(),body);
        story.externalId=key;
        story.emotionalFingerprint=input.emotionalProfile();
        story.referenceSummary=input.referenceNotes();
        story.audienceAge=input.audienceAge()==null?5:input.audienceAge();
        return Dtos.StoryView.from(stories.save(story));
    }

    @Transactional(readOnly=true)
    public Dtos.TaskView getTtsTask(UUID id) {
        return Dtos.TaskView.from(tasks.findById(id)
            .orElseThrow(()->new NotFoundException("TTS task not found: "+id)));
    }

    @Transactional
    public Dtos.StoryView edit(UUID id, Dtos.TextChange input) {
        Story s=require(id);
        if (s.state==StoryState.PUBLISHED) {
            throw new IllegalStateException("Cannot edit a published story in place; create a revision");
        }
        if (s.state==StoryState.TTS_QUEUED || s.state==StoryState.AUDIO_REVIEW) {
            throw new IllegalStateException("Cancel current TTS task before editing text");
        }
        s.title=input.title().trim(); s.tatText=input.tatText().trim();
        s.outline=input.outline(); s.genre=input.genre();
        s.state=StoryState.DRAFT;
        s.languageApproved=false; s.audioApproved=false; s.rightsCleared=false;
        s.updatedAt=Instant.now();
        return Dtos.StoryView.from(stories.save(s));
    }

    @Transactional
    public Dtos.StoryView approveLanguage(UUID id) {
        Story s=require(id);
        if (s.state==StoryState.LANGUAGE_APPROVED) return Dtos.StoryView.from(s);
        if (s.state!=StoryState.DRAFT) throw new IllegalStateException("Expected DRAFT");
        if (s.tatText==null || s.tatText.length()<100) throw new IllegalStateException("Missing story text");
        s.languageApproved=true; s.state=StoryState.LANGUAGE_APPROVED; s.updatedAt=Instant.now();
        return Dtos.StoryView.from(stories.save(s));
    }

    @Transactional
    public Dtos.TaskView queueTts(UUID id) {
        Story s=require(id);
        if (s.state==StoryState.TTS_QUEUED) {
            return tasks.findFirstByStoryIdOrderByCreatedAtDesc(id)
                .map(Dtos.TaskView::from)
                .orElseThrow(()->new IllegalStateException("Story is queued but no task exists"));
        }
        if (!s.languageApproved || s.state!=StoryState.LANGUAGE_APPROVED) {
            throw new IllegalStateException("Tatar language review required before TTS");
        }
        VoiceTask task=tasks.save(new VoiceTask(s));
        s.state=StoryState.TTS_QUEUED; s.updatedAt=Instant.now(); stories.save(s);
        return Dtos.TaskView.from(task);
    }

    @Transactional
    public synchronized Dtos.TaskView claimTts(String worker) {
        var opt=tasks.findFirstByStateOrderByCreatedAtAsc(TaskState.QUEUED);
        if (opt.isEmpty()) return null;
        VoiceTask t=opt.get();
        t.state=TaskState.CLAIMED; t.claimedBy=worker; t.updatedAt=Instant.now();
        return Dtos.TaskView.from(tasks.save(t));
    }

    @Transactional
    public void failTts(UUID id, String reason) {
        VoiceTask t=requireClaimedTask(id);
        t.state=TaskState.FAILED;
        t.error=reason==null?"unknown error":reason.substring(0,Math.min(1900,reason.length()));
        t.updatedAt=Instant.now();
        tasks.save(t);
        Story story=t.story;
        story.state=StoryState.LANGUAGE_APPROVED;
        story.updatedAt=Instant.now();
        stories.save(story);
    }

    @Transactional
    public VoiceTask requireClaimedTask(UUID id) {
        VoiceTask t=tasks.findById(id).orElseThrow(()->new NotFoundException("TTS task not found"));
        if (t.state!=TaskState.CLAIMED) throw new IllegalStateException("Task not CLAIMED");
        return t;
    }

    @Transactional
    public Dtos.StoryView audioReceived(UUID taskId, String fileName, long bytes, int seconds) {
        VoiceTask t=requireClaimedTask(taskId);
        t.state=TaskState.COMPLETED; t.updatedAt=Instant.now(); tasks.save(t);
        Story s=t.story;
        s.audioFile=fileName; s.audioLength=bytes; s.durationSeconds=seconds;
        s.audioApproved=false; s.state=StoryState.AUDIO_REVIEW; s.updatedAt=Instant.now();
        return Dtos.StoryView.from(stories.save(s));
    }

    @Transactional
    public Dtos.StoryView approveAudio(UUID id) {
        Story s=require(id);
        if (s.state==StoryState.READY) return Dtos.StoryView.from(s);
        if(s.state!=StoryState.AUDIO_REVIEW || s.audioFile==null) {
            throw new IllegalStateException("Audio must be uploaded before audio review");
        }
        s.audioApproved=true; s.state=StoryState.READY; s.updatedAt=Instant.now();
        return Dtos.StoryView.from(stories.save(s));
    }

    @Transactional
    public Dtos.StoryView confirmRights(UUID id) {
        Story s=require(id);
        if(s.state==StoryState.PUBLISHED) return Dtos.StoryView.from(s);
        s.rightsCleared=true; s.updatedAt=Instant.now();
        return Dtos.StoryView.from(stories.save(s));
    }

    @Transactional
    public Dtos.StoryView publish(UUID id) {
        Story s=require(id);
        if (s.state==StoryState.PUBLISHED) return Dtos.StoryView.from(s);
        if(s.state!=StoryState.READY || !s.languageApproved || !s.audioApproved
                || !s.rightsCleared || s.audioFile==null || s.audioLength==null || s.audioLength<=0) {
            throw new IllegalStateException("Publication requires language, audio and rights review");
        }
        s.state=StoryState.PUBLISHED; s.publishedAt=Instant.now(); s.updatedAt=Instant.now();
        return Dtos.StoryView.from(stories.save(s));
    }

    @Transactional(readOnly=true)
    public List<Story> published() { return stories.findByStateOrderByPublishedAtDesc(StoryState.PUBLISHED); }

    @Transactional(readOnly=true)
    public Story require(UUID id) {
        return stories.findById(id).orElseThrow(()->new NotFoundException("Story not found: "+id));
    }
}

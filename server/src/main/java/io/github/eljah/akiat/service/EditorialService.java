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
        s.languageApproved=false; s.audioApproved=false; s.updatedAt=Instant.now();
        return Dtos.StoryView.from(stories.save(s));
    }

    @Transactional
    public Dtos.StoryView approveLanguage(UUID id) {
        Story s=require(id);
        if (s.state!=StoryState.DRAFT) throw new IllegalStateException("Expected DRAFT");
        if (s.tatText==null || s.tatText.length()<100) throw new IllegalStateException("Missing story text");
        s.languageApproved=true; s.state=StoryState.LANGUAGE_APPROVED; s.updatedAt=Instant.now();
        return Dtos.StoryView.from(stories.save(s));
    }

    @Transactional
    public Dtos.TaskView queueTts(UUID id) {
        Story s=require(id);
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
        if(s.state!=StoryState.AUDIO_REVIEW || s.audioFile==null) {
            throw new IllegalStateException("Audio must be uploaded before audio review");
        }
        s.audioApproved=true; s.state=StoryState.READY; s.updatedAt=Instant.now();
        return Dtos.StoryView.from(stories.save(s));
    }

    @Transactional
    public Dtos.StoryView confirmRights(UUID id) {
        Story s=require(id);
        if(s.state==StoryState.PUBLISHED) throw new IllegalStateException("Already published");
        s.rightsCleared=true; s.updatedAt=Instant.now();
        return Dtos.StoryView.from(stories.save(s));
    }

    @Transactional
    public Dtos.StoryView publish(UUID id) {
        Story s=require(id);
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

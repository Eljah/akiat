package io.github.eljah.akiat.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="voice_tasks")
public class VoiceTask {
    @Id
    @GeneratedValue(strategy=GenerationType.UUID)
    public UUID id;
    @ManyToOne(optional=false)
    public Story story;
    @Enumerated(EnumType.STRING)
    @Column(nullable=false, length=32)
    public TaskState state = TaskState.QUEUED;
    @Column(length=250)
    public String claimedBy;
    public Instant createdAt=Instant.now();
    public Instant updatedAt=Instant.now();
    @Column(length=2000)
    public String error;

    protected VoiceTask() {}
    public VoiceTask(Story story) { this.story=story; }
}

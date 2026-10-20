package io.github.eljah.akiat.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="emotional_references")
public class EmotionalReference {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;
    @Column(nullable=false, length=350)
    public String title;
    @Column(nullable=false, length=250)
    public String author;
    @Column(length=2000)
    public String sourceUrl;
    @Lob
    public String emotionalProfile;
    @Lob
    public String notes;
    public Integer audienceAge = 5;
    public boolean reviewed = false;
    public Instant createdAt = Instant.now();

    protected EmotionalReference() {}

    public EmotionalReference(String title, String author, String sourceUrl, String emotionalProfile,
                              String notes, Integer audienceAge) {
        this.title = title;
        this.author = author;
        this.sourceUrl = sourceUrl;
        this.emotionalProfile = emotionalProfile;
        this.notes = notes;
        this.audienceAge = audienceAge;
    }
}

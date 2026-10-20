package io.github.eljah.akiat.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="stories")
public class Story {
    @Id
    @GeneratedValue(strategy=GenerationType.UUID)
    public UUID id;
    @ManyToOne(fetch=FetchType.EAGER)
    public EmotionalReference reference;
    @Column(nullable=false, length=350)
    public String title;
    @Column(length=150)
    public String genre;
    @Column(name="external_id", unique=true, length=180)
    public String externalId;
    @Lob
    public String emotionalFingerprint;
    @Lob
    public String referenceSummary;
    public Integer audienceAge;
    @Lob
    public String outline;
    @Lob
    public String tatText;
    @Enumerated(EnumType.STRING)
    @Column(nullable=false, length=40)
    public StoryState state = StoryState.DRAFT;
    public boolean languageApproved;
    public boolean audioApproved;
    public boolean rightsCleared;
    public String audioFile;
    public Long audioLength;
    public Integer durationSeconds;
    public Instant createdAt = Instant.now();
    public Instant updatedAt = Instant.now();
    public Instant publishedAt;
    @Version
    public Long revision;

    protected Story() {}
    public Story(EmotionalReference reference, String title, String genre, String outline, String tatText) {
        this.reference = reference;
        this.title = title;
        this.genre = genre;
        this.outline = outline;
        this.tatText = tatText;
    }
}

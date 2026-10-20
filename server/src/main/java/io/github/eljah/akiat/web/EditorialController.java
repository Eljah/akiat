package io.github.eljah.akiat.web;

import io.github.eljah.akiat.service.*;
import io.github.eljah.akiat.domain.StoryState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api")
public class EditorialController {
    private final ReferenceService refs;
    private final ReferenceDiscovery discovery;
    private final StoryGenerator generator;
    private final EditorialService stories;

    public EditorialController(ReferenceService refs, ReferenceDiscovery discovery,
                               StoryGenerator generator, EditorialService stories) {
        this.refs=refs; this.discovery=discovery; this.generator=generator; this.stories=stories;
    }

    @GetMapping("/references/discover")
    public List<ReferenceDiscovery.Candidate> discover(@RequestParam(defaultValue="современные детские авторские сказки издательство 2025") String q) {
        return discovery.search(q);
    }

    @GetMapping("/references")
    public List<Dtos.ReferenceView> references() { return refs.list(); }

    @PostMapping("/references")
    @ResponseStatus(HttpStatus.CREATED)
    public Dtos.ReferenceView createReference(@Valid @RequestBody Dtos.ReferenceInput input) {
        return refs.create(input);
    }

    @PostMapping("/references/{id}/review")
    public Dtos.ReferenceView reviewReference(@PathVariable UUID id) { return refs.review(id); }

    @GetMapping("/stories")
    public List<Dtos.StoryView> list(@RequestParam(required=false) StoryState state) {
        return stories.list(state);
    }

    @GetMapping("/stories/{id}")
    public Dtos.StoryView get(@PathVariable UUID id) { return stories.get(id); }

    @PostMapping("/stories/generate")
    @ResponseStatus(HttpStatus.CREATED)
    public Dtos.StoryView generate(@Valid @RequestBody Dtos.GenerationRequest input) {
        return generator.generate(input.referenceId());
    }

    @PutMapping("/stories/{id}/text")
    public Dtos.StoryView edit(@PathVariable UUID id, @Valid @RequestBody Dtos.TextChange input) {
        return stories.edit(id,input);
    }

    @PostMapping("/stories/{id}/approve-language")
    public Dtos.StoryView approveLanguage(@PathVariable UUID id) { return stories.approveLanguage(id); }

    @PostMapping("/stories/{id}/queue-tts")
    public Dtos.TaskView queueTts(@PathVariable UUID id) { return stories.queueTts(id); }

    @PostMapping("/stories/{id}/approve-audio")
    public Dtos.StoryView approveAudio(@PathVariable UUID id) { return stories.approveAudio(id); }

    @PostMapping("/stories/{id}/confirm-rights")
    public Dtos.StoryView confirmRights(@PathVariable UUID id) { return stories.confirmRights(id); }

    @PostMapping("/stories/{id}/publish")
    public Dtos.StoryView publish(@PathVariable UUID id) { return stories.publish(id); }
}

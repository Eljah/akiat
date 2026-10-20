package io.github.eljah.akiat.web;

import io.github.eljah.akiat.service.EditorialService;
import io.github.eljah.akiat.domain.VoiceTask;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.beans.factory.annotation.Value;
import java.util.*;
import java.nio.file.*;
import java.io.*;

@RestController
@RequestMapping("/api/tts")
public class VoiceTaskController {
    private final EditorialService stories;
    private final Path storage;

    public VoiceTaskController(EditorialService stories,
                   @Value("${akiat.media-directory}") String storagePath) {
        this.stories=stories; this.storage=Path.of(storagePath).toAbsolutePath().normalize();
    }

    @PostMapping("/jobs/claim")
    public Dtos.TaskView claim(@RequestParam String worker) {
        if(worker.isBlank() || worker.length()>150) throw new IllegalArgumentException("Invalid worker id");
        return stories.claimTts(worker);
    }

    @PostMapping("/jobs/{id}/fail")
    public void fail(@PathVariable UUID id, @RequestBody(required=false) Map<String,String> details) {
        stories.failTts(id, details==null?"unknown":details.getOrDefault("error","unknown"));
    }

    @PostMapping(value="/jobs/{id}/complete", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Dtos.StoryView complete(@PathVariable UUID id,
                                   @RequestPart("file") MultipartFile file,
                                   @RequestParam int durationSeconds) throws IOException {
        VoiceTask task=stories.requireClaimedTask(id);
        if(file.isEmpty() || file.getSize()>80L*1024*1024) {
            throw new IllegalArgumentException("MP3 must be between 1 byte and 80 MB");
        }
        if(durationSeconds<1 || durationSeconds>7200) {
            throw new IllegalArgumentException("Invalid audio duration");
        }
        Files.createDirectories(storage);
        String name=task.story.id+".mp3";
        Path target=storage.resolve(name);
        Path temp=Files.createTempFile(storage,"upload-", ".tmp");
        try {
            file.transferTo(temp);
            // Basic MP3 signature check. Decode and loudness QA remain the worker's responsibility.
            try (InputStream in=Files.newInputStream(temp)) {
                byte[] header=in.readNBytes(3);
                boolean id3=header.length==3 && header[0]=='I' && header[1]=='D' && header[2]=='3';
                boolean mpeg=header.length>=2 && (header[0]&0xff)==0xff && (header[1]&0xe0)==0xe0;
                if(!id3 && !mpeg) throw new IllegalArgumentException("Uploaded file is not recognizable MP3");
            }
            Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);
            return stories.audioReceived(id,name,file.getSize(),durationSeconds);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}

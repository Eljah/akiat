package io.github.eljah.akiat.web;

import io.github.eljah.akiat.service.EditorialService;
import io.github.eljah.akiat.domain.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.nio.file.*;
import java.io.*;
import java.net.URI;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.xml.stream.*;

@RestController
public class PublicFeedController {
    private static final String ITUNES="http://www.itunes.com/dtds/podcast-1.0.dtd";
    private final EditorialService stories;
    private final Path mediaPath;
    private final String baseUrl,title,description,author,coverUrl;

    public PublicFeedController(EditorialService stories,
        @Value("${akiat.media-directory}") String mediaPath,
        @Value("${akiat.feed.base-url}") String baseUrl,
        @Value("${akiat.feed.title}") String title,
        @Value("${akiat.feed.description}") String description,
        @Value("${akiat.feed.author}") String author,
        @Value("${akiat.feed.image-url}") String coverUrl) {
        this.stories=stories; this.mediaPath=Path.of(mediaPath).toAbsolutePath().normalize();
        this.baseUrl=baseUrl.replaceAll("/+$",""); this.title=title; this.description=description;
        this.author=author; this.coverUrl=coverUrl;
    }

    @GetMapping(value="/feed.xml",produces="application/rss+xml;charset=UTF-8")
    public String feed() throws XMLStreamException {
        StringWriter sw=new StringWriter();
        XMLStreamWriter xml=XMLOutputFactory.newInstance().createXMLStreamWriter(sw);
        xml.writeStartDocument("UTF-8","1.0");
        xml.writeStartElement("rss");xml.writeAttribute("version","2.0");
        xml.writeNamespace("itunes",ITUNES);
        xml.writeStartElement("channel");
        plain(xml,"title",title);plain(xml,"link",baseUrl);plain(xml,"description",description);
        plain(xml,"language","tt");
        itunes(xml,"author",author); itunes(xml,"explicit","false");
        if(!coverUrl.isBlank()) {
            xml.writeStartElement("itunes","image",ITUNES);
            xml.writeAttribute("href",coverUrl);xml.writeEndElement();
        }
        xml.writeStartElement("itunes","category",ITUNES);
        xml.writeAttribute("text","Kids & Family");
        xml.writeStartElement("itunes","category",ITUNES);
        xml.writeAttribute("text","Stories for Kids"); xml.writeEndElement(); xml.writeEndElement();
        for(Story story:stories.published()) {
            xml.writeStartElement("item");
            plain(xml,"title",story.title);
            plain(xml,"description","Оригинальная татароязычная сказка. "+(story.genre==null?"":story.genre));
            plain(xml,"guid",story.id.toString());
            plain(xml,"pubDate",DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC)
                .format(story.publishedAt));
            itunes(xml,"explicit","false");
            if(story.durationSeconds!=null) itunes(xml,"duration",String.valueOf(story.durationSeconds));
            xml.writeEmptyElement("enclosure");
            xml.writeAttribute("url",baseUrl+"/media/"+story.id+".mp3");
            xml.writeAttribute("length",String.valueOf(story.audioLength));
            xml.writeAttribute("type","audio/mpeg");
            xml.writeEndElement();
        }
        xml.writeEndElement();xml.writeEndElement();xml.writeEndDocument();xml.close();
        return sw.toString();
    }

    private static void plain(XMLStreamWriter xml,String key,String value) throws XMLStreamException {
        xml.writeStartElement(key);xml.writeCharacters(value==null?"":value);xml.writeEndElement();
    }
    private static void itunes(XMLStreamWriter xml,String key,String value) throws XMLStreamException {
        xml.writeStartElement("itunes",key,ITUNES);xml.writeCharacters(value);xml.writeEndElement();
    }

    @GetMapping(value="/media/{id}.mp3",produces="audio/mpeg")
    public ResponseEntity<Resource> media(@PathVariable UUID id) {
        Story s=stories.require(id);
        if(s.state!=StoryState.PUBLISHED || s.audioFile==null) return ResponseEntity.notFound().build();
        Path path=mediaPath.resolve(s.audioFile).normalize();
        if(!path.startsWith(mediaPath) || !Files.isRegularFile(path)) return ResponseEntity.notFound().build();
        Resource resource=new FileSystemResource(path);
        // Spring MVC can serve Resource byte ranges for HEAD / Range clients.
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/mpeg"))
            .cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(2)))
            .body(resource);
    }
}

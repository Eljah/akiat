package io.github.eljah.akiat.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.util.*;

/** Discovery returns candidates ONLY; an editor must verify the work and enter an emotional profile. */
@Service
public class ReferenceDiscovery {
    public record Candidate(String title, String url, String snippet) {}
    private final RestClient client=RestClient.create("https://api.search.brave.com");
    private final String apiKey;

    public ReferenceDiscovery(@Value("${akiat.search.brave-api-key}") String apiKey) {
        this.apiKey=apiKey;
    }

    @SuppressWarnings("unchecked")
    public List<Candidate> search(String query) {
        if(apiKey==null || apiKey.isBlank()) {
            throw new IllegalStateException("Configure AKIAT_BRAVE_API_KEY or add a reference manually");
        }
        if(query==null || query.isBlank() || query.length()>250) {
            throw new IllegalArgumentException("Query must be 1-250 characters");
        }
        Map<String,Object> result=client.get().uri(builder->builder.path("/res/v1/web/search")
                .queryParam("q",query).queryParam("count",10).queryParam("search_lang","ru")
                .queryParam("safesearch","strict").build())
            .header("X-Subscription-Token",apiKey)
            .header(HttpHeaders.ACCEPT,"application/json")
            .retrieve().body(Map.class);
        if(result==null || !(result.get("web") instanceof Map<?,?> web)
                || !(web.get("results") instanceof List<?> found)) return List.of();
        List<Candidate> candidates=new ArrayList<>();
        for(Object raw : found) {
            if(raw instanceof Map<?,?> item) {
                candidates.add(new Candidate(String.valueOf(item.get("title")),
                    String.valueOf(item.get("url")),
                    String.valueOf(item.get("description"))));
            }
        }
        return candidates;
    }
}

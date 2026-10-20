package io.github.eljah.akiat.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.util.List;
import java.util.Map;

/** OpenAI-compatible chat completions API: hosted provider or self-hosted /v1 backend. */
@Service
public class LlmClient {
    private final RestClient client;
    private final String model;
    private final String key;

    public LlmClient(@Value("${akiat.llm.base-url}") String baseUrl,
                     @Value("${akiat.llm.model}") String model,
                     @Value("${akiat.llm.api-key}") String key) {
        this.client=RestClient.builder().baseUrl(baseUrl).build();
        this.model=model;
        this.key=key;
    }

    @SuppressWarnings("unchecked")
    public String complete(String system, String user) {
        if (model==null || model.isBlank()) {
            throw new IllegalStateException("Configure AKIAT_LLM_MODEL and AKIAT_LLM_BASE_URL");
        }
        var request=client.post().uri("/chat/completions").contentType(MediaType.APPLICATION_JSON);
        if (key!=null && !key.isBlank()) request=request.header("Authorization", "Bearer "+key);
        Map<String,Object> payload=Map.of("model",model,
            "temperature",0.8,
            "messages",List.of(Map.of("role","system","content",system),
                                Map.of("role","user","content",user)));
        Map<String,Object> response=request.body(payload).retrieve().body(Map.class);
        if (response==null) throw new IllegalStateException("LLM returned empty HTTP body");
        var choices=(List<Map<String,Object>>)response.get("choices");
        if (choices==null || choices.isEmpty()) throw new IllegalStateException("LLM returned no choices");
        var message=(Map<String,Object>)choices.get(0).get("message");
        if (message==null || !(message.get("content") instanceof String content) || content.isBlank()) {
            throw new IllegalStateException("LLM returned no message content");
        }
        return content.strip();
    }
}

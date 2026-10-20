package io.github.eljah.akiat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import io.github.eljah.akiat.mcp.EditorialTools;

@SpringBootApplication
@EnableScheduling
public class AkiatApplication {
    public static void main(String[] args) {
        SpringApplication.run(AkiatApplication.class, args);
    }

    @Bean
    ToolCallbackProvider editorialMcpTools(EditorialTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}

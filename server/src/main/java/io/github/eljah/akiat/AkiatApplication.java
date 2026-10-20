package io.github.eljah.akiat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AkiatApplication {
    public static void main(String[] args) {
        SpringApplication.run(AkiatApplication.class, args);
    }
}

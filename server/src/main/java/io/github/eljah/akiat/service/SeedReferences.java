package io.github.eljah.akiat.service;

import io.github.eljah.akiat.domain.EmotionalReference;
import io.github.eljah.akiat.repo.References;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SeedReferences {
    @Bean
    CommandLineRunner seedInitialReferences(References references) {
        return args -> {
            if(references.count()!=0) return;
            // These are metadata-based analogies, not reviews of the copyrighted full texts.
            references.save(new EmotionalReference("Сказки про мам","Сергей Седов",
                "https://www.labirint.ru/news/2475/",
                "Warm comic exaggeration, acceptance of imperfect people, subtle tenderness without moralizing.",
                "Only publisher description reviewed; original stories must not reuse any situations, characters or jokes.",5));
            references.save(new EmotionalReference("Умная собачка Соня","Андрей Усачёв",
                "https://ast.ru/book/umnaya-sobachka-sonya-077604/",
                "Kind absurdity, literal childhood logic, light repetitive comical rhythm.",
                "Only publisher description reviewed; avoid its dog protagonist and all plot devices.",5));
            references.save(new EmotionalReference("Приключения новогодних игрушек","Елена Ракитина",
                "https://rech-deti.ru/catalog/7/179/",
                "Cozy domestic magic, slow contemplation, gentle emotional aftertaste, open ending.",
                "Only publisher description reviewed; avoid toy characters and holiday plot.",6));
        };
    }
}

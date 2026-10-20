package io.github.eljah.akiat.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class ServerSecurity {
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
        @Value("${akiat.admin.mcp-token}") String mcpToken) throws Exception {
        http.csrf(csrf -> csrf.disable()) // bearer/basic REST server, never browser cookie authentication
            .authorizeHttpRequests(reg -> reg
                .requestMatchers(HttpMethod.GET, "/feed.xml", "/media/**", "/actuator/health").permitAll()
                .requestMatchers(HttpMethod.HEAD, "/feed.xml", "/media/**").permitAll()
                .anyRequest().authenticated())
            .httpBasic(Customizer.withDefaults())
            .addFilterBefore(new McpBearerFilter(mcpToken), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    UserDetailsService users(@Value("${akiat.admin.username}") String username,
                             @Value("${akiat.admin.password}") String password,
                             PasswordEncoder encoder) {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("Set AKIAT_ADMIN_PASSWORD before starting the server.");
        }
        return new InMemoryUserDetailsManager(User.withUsername(username)
            .password(encoder.encode(password)).roles("EDITOR").build());
    }

    @Bean
    PasswordEncoder encoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}

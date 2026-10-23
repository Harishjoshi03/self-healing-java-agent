package com.example.healing.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize
                    .requestMatchers("/").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                    .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    @Bean
    UserDetailsService userDetailsService(
            @Value("${agent.security.username}") String username,
            @Value("${agent.security.password}") String password,
            PasswordEncoder passwordEncoder) {
        if (username.isBlank() || password.isBlank()) {
            throw new IllegalStateException("Set AGENT_API_USERNAME and AGENT_API_PASSWORD outside local profile");
        }
        return new InMemoryUserDetailsManager(User.withUsername(username)
                .password(passwordEncoder.encode(password)).roles("USER").build());
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
package com.cosmic.databasecms.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/health", "/api/setup/status").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/setup", "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/dashboard").hasAuthority("dashboard.read")
                        .requestMatchers(HttpMethod.GET, "/api/catalog/**").hasAuthority("catalog.read")
                        .requestMatchers(HttpMethod.POST, "/api/catalog/import").hasAuthority("database.manage")
                        .requestMatchers(HttpMethod.GET, "/api/drops/**").hasAuthority("drops.read")
                        .requestMatchers("/api/drops/**").hasAuthority("drops.write")
                        .requestMatchers(HttpMethod.GET, "/api/shops/**").hasAuthority("shops.read")
                        .requestMatchers("/api/shops/**").hasAuthority("shops.write")
                        .requestMatchers(HttpMethod.GET, "/api/gachapon/**").hasAuthority("shops.read")
                        .requestMatchers("/api/gachapon/**").hasAuthority("shops.write")
                        .requestMatchers(HttpMethod.GET, "/api/accounts/**").hasAuthority("accounts.read")
                        .requestMatchers(HttpMethod.PATCH, "/api/accounts/**").hasAuthority("accounts.write")
                        .requestMatchers(HttpMethod.GET, "/api/characters/*/inventory").hasAuthority("inventory.read")
                        .requestMatchers("/api/characters/*/inventory/**").hasAuthority("inventory.write")
                        .requestMatchers(HttpMethod.GET, "/api/characters/**").hasAuthority("characters.read")
                        .requestMatchers("/api/characters/**").hasAuthority("characters.write")
                        .requestMatchers(HttpMethod.GET, "/api/audit/**").hasAuthority("audit.read")
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(
                        (request, response, exception) -> response.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
                .logout(logout -> logout.logoutUrl("/api/auth/logout").logoutSuccessHandler(
                        (request, response, authentication) -> response.setStatus(HttpServletResponse.SC_NO_CONTENT)))
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${cosmic.allowed-origin}") String origin) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(origin));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "X-CSRF-TOKEN", "X-Requested-With"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}

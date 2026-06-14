package com.cosmic.servercms;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.Map;

import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;

@RestController
@RequestMapping("/api")
public class AuthController {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final AuthenticationManager authenticationManager;

    public AuthController(JdbcTemplate jdbc, PasswordEncoder encoder,
                          AuthenticationManager authenticationManager) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.authenticationManager = authenticationManager;
    }

    @GetMapping("/health")
    Map<String, Object> health() {
        return Map.of("status", "UP", "service", "Cosmic Server CMS");
    }

    @GetMapping("/setup/status")
    Map<String, Object> setupStatus() {
        return Map.of("required", jdbc.queryForObject("SELECT COUNT(*)=0 FROM server_cms_users", Boolean.class));
    }

    @PostMapping("/setup")
    @ResponseStatus(HttpStatus.CREATED)
    void setup(@Valid @RequestBody Setup body) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT COUNT(*)=0 FROM server_cms_users", Boolean.class))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Owner account already exists");
        }
        jdbc.update("INSERT INTO server_cms_users(username,display_name,password_hash,role_name) VALUES (?,?,?,'OWNER')",
                body.username(), body.displayName(), encoder.encode(body.password()));
    }

    @PostMapping("/auth/login")
    Map<String, Object> login(@RequestBody Login body, HttpServletRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password()));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        HttpSession session = request.getSession(true);
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, context);
        return Map.of("authenticated", true, "username", authentication.getName());
    }

    @GetMapping("/auth/me")
    Map<String, Object> me(Principal principal) {
        return Map.of("username", principal.getName());
    }

    record Setup(@NotBlank String username, @NotBlank String displayName,
                 @NotBlank @Size(min = 5) String password) {}
    record Login(String username, String password) {}
}

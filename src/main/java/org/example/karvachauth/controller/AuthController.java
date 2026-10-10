package org.example.karvachauth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.example.karvachauth.service.AuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final byte[] signupKey;

    public AuthController(AuthService authService,
                          AuthenticationManager authenticationManager,
                          SecurityContextRepository securityContextRepository,
                          @Value("${karvachauth.auth.signup-key:}") String signupKey) {
        this.authService = authService;
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.signupKey = signupKey.trim().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * POST /api/auth/signup   (sirf Postman se)
     * Header: X-Signup-Key: <karvachauth.auth.signup-key>
     * Body  : {"username":"prashant","password":"StrongPass@123","role":"ADMIN"}
     */
    @PostMapping("/signup")
    public ResponseEntity<Map<String, Object>> signup(
            @RequestHeader(value = "X-Signup-Key", required = false) String key,
            @RequestBody Map<String, String> body) {

        if (signupKey.length == 0 || key == null
                || !MessageDigest.isEqual(signupKey, key.trim().getBytes(StandardCharsets.UTF_8))) {
            log.warn("stage=SIGNUP_REJECTED reason=BAD_KEY");
            return error(HttpStatus.FORBIDDEN, "Signup not allowed");
        }
        try {
            authService.signup(body.get("username"), body.get("password"), body.get("role"));
            return ok(Map.of("message", "User created"));
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** POST /api/auth/login   Body: {"username":"..","password":".."} -> session cookie */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body,
                                                     HttpServletRequest request,
                                                     HttpServletResponse response) {
        String username = body.get("username") == null ? "" : body.get("username").trim().toLowerCase(Locale.ROOT);
        String password = body.get("password") == null ? "" : body.get("password");
        try {
            Authentication auth = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(username, password));

            // Session fixation se bachao: login par naya session id
            if (request.getSession(false) != null) {
                request.changeSessionId();
            }
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);

            authService.recordLogin(auth.getName());
            log.info("stage=LOGIN_OK username={}", auth.getName());
            return ok(userBody(auth));

        } catch (AuthenticationException e) {
            log.warn("stage=LOGIN_FAILED username={}", username);
            return error(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
    }

    /** GET /api/auth/me -> logged-in user, warna 401 (SecurityConfig se) */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(Authentication auth) {
        return ok(userBody(auth));
    }

    /** POST /api/auth/logout */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ok(Map.of("message", "Logged out"));
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> userBody(Authentication auth) {
        String role = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .findFirst().orElse("ROLE_VIEWER").replace("ROLE_", "");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", auth.getName());
        m.put("role", role);
        return m;
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.putAll(data);
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
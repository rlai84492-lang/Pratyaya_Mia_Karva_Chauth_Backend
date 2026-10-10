package org.example.karvachauth.serviceImple;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.karvachauth.entity.DashboardUser;
import org.example.karvachauth.repository.DashboardUserRepository;
import org.example.karvachauth.service.AuthService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService, UserDetailsService {

    private static final Pattern USERNAME = Pattern.compile("^[a-zA-Z0-9._-]{3,50}$");
    private static final int MIN_PASSWORD = 8;
    private static final Set<String> ROLES = Set.of("ADMIN", "VIEWER");

    private final DashboardUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void signup(String username, String password, String role) {
        String u = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(u).matches()) {
            throw new IllegalArgumentException("Username 3-50 chars: letters, numbers, . _ -");
        }
        if (password == null || password.length() < MIN_PASSWORD) {
            throw new IllegalArgumentException("Password must be at least " + MIN_PASSWORD + " characters");
        }
        String r = role == null || role.isBlank() ? "VIEWER" : role.trim().toUpperCase(Locale.ROOT);
        if (!ROLES.contains(r)) {
            throw new IllegalArgumentException("Role must be ADMIN or VIEWER");
        }
        if (userRepository.countByUsername(u) > 0) {
            throw new IllegalArgumentException("Username already exists");
        }
        try {
            userRepository.insertUser(u, passwordEncoder.encode(password), r);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Username already exists");
        }
        log.info("stage=DASHBOARD_USER_CREATED username={} role={}", u, r);
    }

    @Override
    public void recordLogin(String username) {
        userRepository.updateLastLogin(username);
    }

    /** Spring Security login ke waqt user yahin se load karta hai. */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String u = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        DashboardUser user = userRepository.findByUsername(u)
                .orElseThrow(() -> new UsernameNotFoundException("not found"));
        return User.withUsername(user.getUsername())
                .password(user.getPasswordHash())
                .roles(user.getRole())
                .disabled(!Boolean.TRUE.equals(user.getEnabled()))
                .build();
    }
}
package com.mypath.backend.auth.service;

import com.mypath.backend.auth.dto.AuthResponse;
import com.mypath.backend.auth.dto.AvailabilityResponseDTO;
import com.mypath.backend.auth.dto.LoginRequestDTO;
import com.mypath.backend.auth.dto.RefreshTokenRequestDTO;
import com.mypath.backend.auth.dto.RegisterRequestDTO;
import com.mypath.backend.auth.dto.RegisterResponseDTO;
import com.mypath.backend.auth.entity.EmailVerificationToken;
import com.mypath.backend.auth.entity.PasswordResetToken;
import com.mypath.backend.auth.entity.RefreshToken;
import com.mypath.backend.auth.repository.EmailVerificationTokenRepository;
import com.mypath.backend.auth.repository.PasswordResetTokenRepository;
import com.mypath.backend.auth.repository.RefreshTokenRepository;
import com.mypath.backend.exception.InvalidTokenException;
import com.mypath.backend.exception.UserAlreadyExistsException;
import com.mypath.backend.security.jwt.JwtService;
import com.mypath.backend.user.Role;
import com.mypath.backend.user.entity.User;
import com.mypath.backend.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenRepository refreshTokenRepository;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final EmailService emailService;
    private final GoogleTokenVerifier googleTokenVerifier;

    public AuthService(UserRepository userRepository, JwtService jwtService, AuthenticationManager authenticationManager,
                        PasswordEncoder passwordEncoder, RefreshTokenRepository refreshTokenRepository,
                        EmailVerificationTokenRepository emailVerificationTokenRepository,
                        PasswordResetTokenRepository passwordResetTokenRepository, EmailService emailService,
                        GoogleTokenVerifier googleTokenVerifier) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.authenticationManager = authenticationManager;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenRepository = refreshTokenRepository;
        this.emailVerificationTokenRepository = emailVerificationTokenRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.emailService = emailService;
        this.googleTokenVerifier = googleTokenVerifier;
    }

    public AvailabilityResponseDTO checkUsernameAvailability(String username) {
        boolean available = username != null && !username.isBlank()
                && !userRepository.existsByUsernameIgnoreCase(username);
        return new AvailabilityResponseDTO(available);
    }

    public AvailabilityResponseDTO checkEmailAvailability(String email) {
        boolean available = email != null && !email.isBlank()
                && !userRepository.existsByEmail(email);
        return new AvailabilityResponseDTO(available);
    }

    public RegisterResponseDTO register(RegisterRequestDTO registerRequest) {
        if (userRepository.existsByUsernameIgnoreCase(registerRequest.getUsername())) {
            throw new UserAlreadyExistsException("username", "Username '" + registerRequest.getUsername() + "' is already taken");
        }

        if (userRepository.existsByEmail(registerRequest.getEmail())) {
            throw new UserAlreadyExistsException("email", "Email '" + registerRequest.getEmail() + "' is already registered");
        }

        User user = new User();
        user.setUsername(registerRequest.getUsername());
        user.setPassword(passwordEncoder.encode(registerRequest.getPassword()));
        user.setEmail(registerRequest.getEmail());
        user.setVisibility(registerRequest.getVisibility() != null ? registerRequest.getVisibility() : true);
        user.setCreatedAt(registerRequest.getCreatedAt());
        user.setUpdatedAt(registerRequest.getUpdatedAt());
        user.setRole(Role.USER);
        user.setEmailVerified(false);

        userRepository.save(user);

        EmailVerificationToken verificationToken = createVerificationToken(user);
        emailService.sendVerificationEmail(user, verificationToken.getToken());

        return new RegisterResponseDTO("Account created. Check your email to verify your account.");
    }

    public AuthResponse verifyEmail(String token) {
        EmailVerificationToken verificationToken = emailVerificationTokenRepository.findByToken(token)
                .orElseThrow(() -> new InvalidTokenException("Invalid or expired verification link"));

        if (verificationToken.getExpiresAt().isBefore(Instant.now())) {
            emailVerificationTokenRepository.delete(verificationToken);
            throw new InvalidTokenException("Invalid or expired verification link");
        }

        User user = verificationToken.getUser();
        user.setEmailVerified(true);
        userRepository.save(user);
        emailVerificationTokenRepository.delete(verificationToken);

        String accessToken = jwtService.getToken(user);
        RefreshToken refreshToken = createRefreshToken(user);
        return new AuthResponse(accessToken, refreshToken.getToken(), user.getUsername());
    }

    @Transactional
    public void resendVerification(String username, String email) {
        Optional<User> userOpt = Optional.empty();
        if (username != null && !username.isBlank()) {
            userOpt = userRepository.findByUsernameIgnoreCase(username);
        } else if (email != null && !email.isBlank()) {
            userOpt = userRepository.findByEmail(email);
        }

        userOpt.ifPresent(user -> {
            if (!user.isEmailVerified()) {
                emailVerificationTokenRepository.deleteByUserId(user.getId());
                EmailVerificationToken token = createVerificationToken(user);
                emailService.sendVerificationEmail(user, token.getToken());
            }
        });
    }

    private EmailVerificationToken createVerificationToken(User user) {
        EmailVerificationToken evt = new EmailVerificationToken();
        evt.setUser(user);
        evt.setToken(UUID.randomUUID().toString());
        evt.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        return emailVerificationTokenRepository.save(evt);
    }

    @Transactional
    public void forgotPassword(String email) {
        userRepository.findByEmail(email).ifPresent(user -> {
            passwordResetTokenRepository.deleteByUserId(user.getId());
            PasswordResetToken token = createPasswordResetToken(user);
            emailService.sendPasswordResetEmail(user, token.getToken());
        });
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        PasswordResetToken resetToken = passwordResetTokenRepository.findByToken(token)
                .orElseThrow(() -> new InvalidTokenException("Invalid or expired reset link"));

        if (resetToken.getExpiresAt().isBefore(Instant.now())) {
            passwordResetTokenRepository.delete(resetToken);
            throw new InvalidTokenException("Invalid or expired reset link");
        }

        User user = resetToken.getUser();
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        passwordResetTokenRepository.delete(resetToken);

        refreshTokenRepository.deleteByUserId(user.getId());
    }

    private PasswordResetToken createPasswordResetToken(User user) {
        PasswordResetToken token = new PasswordResetToken();
        token.setUser(user);
        token.setToken(UUID.randomUUID().toString());
        token.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
        return passwordResetTokenRepository.save(token);
    }

    @Transactional
    public AuthResponse googleAuth(String idToken) {
        GoogleTokenVerifier.GoogleTokenPayload payload = googleTokenVerifier.verify(idToken);

        User user = userRepository.findByEmail(payload.email())
                .orElseGet(() -> createGoogleUser(payload));

        if (!user.isEmailVerified()) {
            user.setEmailVerified(true);
            userRepository.save(user);
        }

        String accessToken = jwtService.getToken(user);
        RefreshToken refreshToken = createRefreshToken(user);
        return new AuthResponse(accessToken, refreshToken.getToken(), user.getUsername());
    }

    private User createGoogleUser(GoogleTokenVerifier.GoogleTokenPayload payload) {
        User user = new User();
        user.setUsername(generateUsernameFromEmail(payload.email()));
        user.setEmail(payload.email());
        user.setPassword(null);
        user.setVisibility(true);
        user.setCreatedAt(new Date());
        user.setUpdatedAt(new Date());
        user.setRole(Role.USER);
        user.setEmailVerified(true);
        return userRepository.save(user);
    }

    private String generateUsernameFromEmail(String email) {
        String base = email.substring(0, email.indexOf('@')).replaceAll("[^a-zA-Z0-9_]", "");
        if (base.length() < 3) {
            base = (base + "user").substring(0, Math.max(3, base.length()));
        }
        base = base.substring(0, Math.min(base.length(), 20));

        String candidate = base;
        int suffix = 0;
        while (userRepository.existsByUsernameIgnoreCase(candidate)) {
            suffix++;
            String suffixStr = String.valueOf(suffix);
            candidate = base.substring(0, Math.min(base.length(), 20 - suffixStr.length())) + suffixStr;
        }
        return candidate;
    }

    public AuthResponse login(LoginRequestDTO request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getUsername(),
                        request.getPassword()
                )
        );

        User user = userRepository.findByUsernameIgnoreCase(request.getUsername())
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        String accessToken = jwtService.getToken(user);
        RefreshToken refreshToken = createRefreshToken(user);

        return new AuthResponse(accessToken, refreshToken.getToken(), user.getUsername());

    }

    public AuthResponse refresh(RefreshTokenRequestDTO request) {
        RefreshToken refreshToken = refreshTokenRepository
                .findByToken(request.getRefreshToken())
                .orElseThrow(() -> new RuntimeException("Invalid refresh token"));

        if (refreshToken.getExpiresAt().isBefore(Instant.now())) {
            throw new RuntimeException("Refresh token expired");
        }

        User user = refreshToken.getUser();
        String accessToken = jwtService.getToken(user);

        return new AuthResponse(accessToken, refreshToken.getToken(), user.getUsername());
    }



    public RefreshToken createRefreshToken(User user) {
        RefreshToken rt = new RefreshToken();
        rt.setUser(user);
        rt.setToken(UUID.randomUUID().toString());
        rt.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        return refreshTokenRepository.save(rt);

    }
    @Transactional
    public void logout(RefreshTokenRequestDTO request) {
        refreshTokenRepository.deleteByToken(request.getRefreshToken());
    }

}

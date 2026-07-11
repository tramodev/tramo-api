package com.mypath.backend.auth.service;

import com.mypath.backend.auth.dto.AuthResponse;
import com.mypath.backend.auth.dto.AvailabilityResponseDTO;
import com.mypath.backend.auth.dto.LoginRequestDTO;
import com.mypath.backend.auth.dto.RefreshTokenRequestDTO;
import com.mypath.backend.auth.dto.RegisterRequestDTO;
import com.mypath.backend.auth.dto.RegisterResponseDTO;
import com.mypath.backend.auth.entity.EmailVerificationToken;
import com.mypath.backend.auth.entity.RefreshToken;
import com.mypath.backend.auth.repository.EmailVerificationTokenRepository;
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
    private final EmailService emailService;

    public AuthService(UserRepository userRepository, JwtService jwtService, AuthenticationManager authenticationManager,
                        PasswordEncoder passwordEncoder, RefreshTokenRepository refreshTokenRepository,
                        EmailVerificationTokenRepository emailVerificationTokenRepository, EmailService emailService) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.authenticationManager = authenticationManager;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenRepository = refreshTokenRepository;
        this.emailVerificationTokenRepository = emailVerificationTokenRepository;
        this.emailService = emailService;
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
        // Always succeeds regardless of whether the account exists, so this
        // endpoint can't be used to probe which usernames/emails are registered.
    }

    private EmailVerificationToken createVerificationToken(User user) {
        EmailVerificationToken evt = new EmailVerificationToken();
        evt.setUser(user);
        evt.setToken(UUID.randomUUID().toString());
        evt.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        return emailVerificationTokenRepository.save(evt);
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

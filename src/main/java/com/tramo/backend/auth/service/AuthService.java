package com.tramo.backend.auth.service;

import com.tramo.backend.auth.dto.AuthResponse;
import com.tramo.backend.auth.dto.AvailabilityResponseDTO;
import com.tramo.backend.auth.dto.LoginRequestDTO;
import com.tramo.backend.auth.dto.RefreshTokenRequestDTO;
import com.tramo.backend.auth.dto.RegisterRequestDTO;
import com.tramo.backend.auth.dto.RegisterResponseDTO;
import com.tramo.backend.auth.entity.AgeRejectionAttempt;
import com.tramo.backend.auth.entity.EmailVerificationToken;
import com.tramo.backend.auth.entity.PasswordResetToken;
import com.tramo.backend.auth.entity.RefreshToken;
import com.tramo.backend.auth.repository.AgeRejectionAttemptRepository;
import com.tramo.backend.auth.repository.EmailVerificationTokenRepository;
import com.tramo.backend.auth.repository.PasswordResetTokenRepository;
import com.tramo.backend.auth.repository.RefreshTokenRepository;
import com.tramo.backend.exception.BirthDateAlreadySetException;
import com.tramo.backend.exception.InvalidTokenException;
import com.tramo.backend.exception.LimitExceededException;
import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.exception.UnderageRegistrationException;
import com.tramo.backend.exception.UserAlreadyExistsException;
import com.tramo.backend.security.jwt.JwtService;
import com.tramo.backend.security.ratelimit.RateLimiterService;
import com.tramo.backend.user.Role;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.UserRepository;
import io.github.bucket4j.Bucket;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenRepository refreshTokenRepository;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final EmailService emailService;
    private final GoogleTokenVerifier googleTokenVerifier;
    private final CaptchaVerifier captchaVerifier;
    private final RateLimiterService rateLimiterService;
    private final AgeRejectionAttemptRepository ageRejectionAttemptRepository;
    private final MinAgeValidator minAgeValidator;
    private final TransactionTemplate transactionTemplate;

    @Value("${app.auth.rejection-cooldown-hours}")
    private long rejectionCooldownHours;

    public AuthService(UserRepository userRepository, JwtService jwtService, AuthenticationManager authenticationManager,
                        PasswordEncoder passwordEncoder, RefreshTokenRepository refreshTokenRepository,
                        EmailVerificationTokenRepository emailVerificationTokenRepository,
                        PasswordResetTokenRepository passwordResetTokenRepository, EmailService emailService,
                        GoogleTokenVerifier googleTokenVerifier, CaptchaVerifier captchaVerifier,
                        RateLimiterService rateLimiterService, AgeRejectionAttemptRepository ageRejectionAttemptRepository,
                        MinAgeValidator minAgeValidator, PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.authenticationManager = authenticationManager;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenRepository = refreshTokenRepository;
        this.emailVerificationTokenRepository = emailVerificationTokenRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.emailService = emailService;
        this.googleTokenVerifier = googleTokenVerifier;
        this.captchaVerifier = captchaVerifier;
        this.rateLimiterService = rateLimiterService;
        this.ageRejectionAttemptRepository = ageRejectionAttemptRepository;
        this.minAgeValidator = minAgeValidator;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    private void checkIdentityRateLimit(String scope, String identifier, int capacity, int refillTokens) {
        Bucket bucket = rateLimiterService.resolveBucket(
                scope + ":" + identifier.trim().toLowerCase(), capacity, refillTokens, Duration.ofMinutes(1));
        if (!bucket.tryConsume(1)) {
            throw new LimitExceededException("Too many attempts. Try again later.");
        }
    }

    public AvailabilityResponseDTO checkUsernameAvailability(String username) {
        boolean available = username != null && !username.isBlank()
                && !userRepository.existsByUsernameIgnoreCase(username);
        return new AvailabilityResponseDTO(available);
    }

    private static final String REGISTERED_MESSAGE = "Account created. Check your email to verify your account.";

    public RegisterResponseDTO register(RegisterRequestDTO registerRequest, String ip) {
        // Checked before captcha/uniqueness so a rejected-then-retried underage signup
        // can't just try a different year - see MinAgeValidator/AgeRejectionAttempt below.
        if (ageRejectionAttemptRepository.existsByIpAddressAndRejectedAtAfter(
                ip, Instant.now().minus(rejectionCooldownHours, ChronoUnit.HOURS))) {
            throw new UnderageRegistrationException(
                    "You do not meet the minimum age requirement to create an account.");
        }

        captchaVerifier.verify(registerRequest.getCaptchaToken(), "register");

        if (userRepository.existsByUsernameIgnoreCase(registerRequest.getUsername())) {
            throw new UserAlreadyExistsException("username", "Username '" + registerRequest.getUsername() + "' is already taken");
        }

        // Unlike username, email isn't a public identifier - responding the same way
        // whether or not it's taken keeps this endpoint from being an email-enumeration
        // oracle for a social platform. No account is created, no email is sent.
        if (userRepository.existsByEmail(registerRequest.getEmail())) {
            return new RegisterResponseDTO(REGISTERED_MESSAGE);
        }

        try {
            minAgeValidator.validate(registerRequest.getBirthDate());
        } catch (UnderageRegistrationException e) {
            AgeRejectionAttempt attempt = new AgeRejectionAttempt();
            attempt.setIpAddress(ip);
            attempt.setRejectedAt(Instant.now());
            ageRejectionAttemptRepository.save(attempt);
            throw e;
        }

        User user = new User();
        user.setUsername(registerRequest.getUsername());
        user.setPassword(passwordEncoder.encode(registerRequest.getPassword()));
        user.setEmail(registerRequest.getEmail());
        user.setBirthDate(registerRequest.getBirthDate());
        user.setVisibility(registerRequest.getVisibility() != null ? registerRequest.getVisibility() : true);
        user.setCreatedAt(new Date());
        user.setUpdatedAt(new Date());
        user.setRole(Role.USER);
        user.setEmailVerified(false);

        EmailVerificationToken verificationToken;
        try {
            verificationToken = transactionTemplate.execute(status -> {
                userRepository.save(user);
                return createVerificationToken(user);
            });
        } catch (DataIntegrityViolationException e) {
            // Lost a race against a concurrent registration for the same username/email -
            // the existsBy* checks above passed for both, only one insert can win.
            if (userRepository.existsByUsernameIgnoreCase(registerRequest.getUsername())) {
                throw new UserAlreadyExistsException("username", "Username '" + registerRequest.getUsername() + "' is already taken");
            }
            return new RegisterResponseDTO(REGISTERED_MESSAGE);
        }
        emailService.sendVerificationEmail(user, verificationToken.getToken());

        return new RegisterResponseDTO(REGISTERED_MESSAGE);
    }

    @Transactional(dontRollbackOn = InvalidTokenException.class)
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
        return new AuthResponse(accessToken, refreshToken.getToken(), user.getUsername(), user.getBirthDate() == null);
    }

    @Transactional
    public void resendVerification(String username, String email) {
        String identifier = (username != null && !username.isBlank()) ? username : email;
        if (identifier != null && !identifier.isBlank()) {
            checkIdentityRateLimit("resend-verification", identifier, 3, 3);
        }

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
        if (email != null && !email.isBlank()) {
            checkIdentityRateLimit("forgot-password", email, 3, 3);
        }

        userRepository.findByEmail(email).ifPresent(user -> {
            passwordResetTokenRepository.deleteByUserId(user.getId());
            PasswordResetToken token = createPasswordResetToken(user);
            emailService.sendPasswordResetEmail(user, token.getToken());
        });
    }

    @Transactional(dontRollbackOn = InvalidTokenException.class)
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

    @Transactional
    public void changePassword(User principal, String currentPassword, String newPassword) {
        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (user.getPassword() == null || !passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
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
        return new AuthResponse(accessToken, refreshToken.getToken(), user.getUsername(), user.getBirthDate() == null);
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
        try {
            return userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            // Lost a race against a concurrent Google sign-in for the same email -
            // the other request already inserted the row, use it.
            return userRepository.findByEmail(payload.email()).orElseThrow(() -> e);
        }
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
        if (request.getUsername() != null && !request.getUsername().isBlank()) {
            checkIdentityRateLimit("login", request.getUsername(), 10, 10);
        }

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

        return new AuthResponse(accessToken, refreshToken.getToken(), user.getUsername(), user.getBirthDate() == null);

    }

    
    
    @Transactional(dontRollbackOn = InvalidTokenException.class)
    public AuthResponse refresh(RefreshTokenRequestDTO request) {
        RefreshToken refreshToken = refreshTokenRepository
                .findByToken(request.getRefreshToken())
                .orElseThrow(() -> new InvalidTokenException("Invalid refresh token"));

        if (refreshToken.isRevoked()) {
            
            refreshTokenRepository.deleteByUserId(refreshToken.getUser().getId());
            throw new InvalidTokenException("Invalid refresh token");
        }

        if (refreshToken.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidTokenException("Refresh token expired");
        }

        User user = refreshToken.getUser();
        if (user.isBanned()) {
            throw new InvalidTokenException("Invalid refresh token");
        }

        
        
        
        refreshToken.setRevoked(true);
        refreshToken.setRevokedAt(Instant.now());
        refreshTokenRepository.save(refreshToken);

        String accessToken = jwtService.getToken(user);
        RefreshToken newRefreshToken = createRefreshToken(user);

        return new AuthResponse(accessToken, newRefreshToken.getToken(), user.getUsername(), user.getBirthDate() == null);
    }

    @Transactional
    public AuthResponse setBirthDate(User principal, LocalDate birthDate) {
        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (user.getBirthDate() != null) {
            throw new BirthDateAlreadySetException("Birth date is already set.");
        }
        minAgeValidator.validate(birthDate);
        user.setBirthDate(birthDate);
        userRepository.save(user);

        String accessToken = jwtService.getToken(user);
        RefreshToken refreshToken = createRefreshToken(user);
        return new AuthResponse(accessToken, refreshToken.getToken(), user.getUsername(), false);
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

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void purgeExpiredTokens() {
        long deletedRefreshTokens = refreshTokenRepository.deleteByExpiresAtBefore(Instant.now());
        if (deletedRefreshTokens > 0) {
            log.info("purgeExpiredTokens deleted {} expired refresh tokens", deletedRefreshTokens);
        }

        long deletedRevokedRefreshTokens = refreshTokenRepository
                .deleteByRevokedTrueAndRevokedAtBefore(Instant.now().minus(48, ChronoUnit.HOURS));
        if (deletedRevokedRefreshTokens > 0) {
            log.info("purgeExpiredTokens deleted {} revoked refresh tokens past the grace period", deletedRevokedRefreshTokens);
        }

        long deletedVerificationTokens = emailVerificationTokenRepository.deleteByExpiresAtBefore(Instant.now());
        if (deletedVerificationTokens > 0) {
            log.info("purgeExpiredTokens deleted {} expired email verification tokens", deletedVerificationTokens);
        }

        long deletedResetTokens = passwordResetTokenRepository.deleteByExpiresAtBefore(Instant.now());
        if (deletedResetTokens > 0) {
            log.info("purgeExpiredTokens deleted {} expired password reset tokens", deletedResetTokens);
        }
    }

}

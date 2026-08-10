package com.tramo.backend.auth.service;

import com.tramo.backend.auth.dto.AuthResponse;
import com.tramo.backend.auth.entity.AgeRejectionAttempt;
import com.tramo.backend.auth.repository.AgeRejectionAttemptRepository;
import com.tramo.backend.exception.BirthDateAlreadySetException;
import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.exception.UnderageRegistrationException;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Service
public class AgeGateService {
    private static final Logger log = LoggerFactory.getLogger(AgeGateService.class);

    private final UserRepository userRepository;
    private final MinAgeValidator minAgeValidator;
    private final AgeRejectionAttemptRepository ageRejectionAttemptRepository;
    private final SessionService sessionService;

    @Value("${app.auth.rejection-cooldown-hours}")
    private long rejectionCooldownHours;

    public AgeGateService(UserRepository userRepository, MinAgeValidator minAgeValidator,
                          AgeRejectionAttemptRepository ageRejectionAttemptRepository,
                          SessionService sessionService) {
        this.userRepository = userRepository;
        this.minAgeValidator = minAgeValidator;
        this.ageRejectionAttemptRepository = ageRejectionAttemptRepository;
        this.sessionService = sessionService;
    }

    public void assertNotInCooldown(String ip) {
        if (ageRejectionAttemptRepository.existsByIpAddressAndRejectedAtAfter(
                ip, Instant.now().minus(rejectionCooldownHours, ChronoUnit.HOURS))) {
            throw new UnderageRegistrationException(
                    "You do not meet the minimum age requirement to create an account.");
        }
    }

    public void validateOrRecordRejection(String ip, LocalDate birthDate) {
        try {
            minAgeValidator.validate(birthDate);
        } catch (UnderageRegistrationException e) {
            AgeRejectionAttempt attempt = new AgeRejectionAttempt();
            attempt.setIpAddress(ip);
            attempt.setRejectedAt(Instant.now());
            ageRejectionAttemptRepository.save(attempt);
            throw e;
        }
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

        return sessionService.issueSession(user);
    }

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void purgeExpiredRejections() {
        long deletedAgeRejections = ageRejectionAttemptRepository
                .deleteByRejectedAtBefore(Instant.now().minus(rejectionCooldownHours, ChronoUnit.HOURS));
        if (deletedAgeRejections > 0) {
            log.info("purgeExpiredRejections deleted {} age rejection attempts past the cooldown", deletedAgeRejections);
        }
    }
}

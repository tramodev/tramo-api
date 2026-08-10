package com.tramo.backend.auth.controller;

import com.tramo.backend.auth.dto.AuthResponse;
import com.tramo.backend.auth.dto.AvailabilityResponseDTO;
import com.tramo.backend.auth.dto.BirthDateRequestDTO;
import com.tramo.backend.auth.dto.ForgotPasswordRequestDTO;
import com.tramo.backend.auth.dto.GoogleAuthRequestDTO;
import com.tramo.backend.auth.dto.LoginRequestDTO;
import com.tramo.backend.auth.dto.RegisterRequestDTO;
import com.tramo.backend.auth.dto.RegisterResponseDTO;
import com.tramo.backend.auth.dto.ResendVerificationRequestDTO;
import com.tramo.backend.auth.dto.ResetPasswordRequestDTO;
import com.tramo.backend.auth.dto.VerifyEmailRequestDTO;
import com.tramo.backend.auth.service.AgeGateService;
import com.tramo.backend.auth.service.GoogleAuthService;
import com.tramo.backend.auth.service.PasswordService;
import com.tramo.backend.auth.service.RegistrationService;
import com.tramo.backend.auth.service.SessionService;
import com.tramo.backend.auth.dto.RefreshTokenRequestDTO;
import com.tramo.backend.security.ClientIp;
import com.tramo.backend.user.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final RegistrationService registrationService;
    private final SessionService sessionService;
    private final PasswordService passwordService;
    private final GoogleAuthService googleAuthService;
    private final AgeGateService ageGateService;
    private final ClientIp clientIp;

    public AuthController(RegistrationService registrationService, SessionService sessionService,
                          PasswordService passwordService, GoogleAuthService googleAuthService,
                          AgeGateService ageGateService, ClientIp clientIp) {
        this.registrationService = registrationService;
        this.sessionService = sessionService;
        this.passwordService = passwordService;
        this.googleAuthService = googleAuthService;
        this.ageGateService = ageGateService;
        this.clientIp = clientIp;
    }

    @GetMapping("/check-username")
    public ResponseEntity<AvailabilityResponseDTO> checkUsername(@RequestParam String username) {
        return ResponseEntity.ok(registrationService.checkUsernameAvailability(username));
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponseDTO> register(@Valid @RequestBody RegisterRequestDTO registerRequest,
                                                          HttpServletRequest request) {
        return ResponseEntity.ok(registrationService.register(registerRequest, clientIp.from(request)));
    }

    @PostMapping("/birth-date")
    public ResponseEntity<AuthResponse> setBirthDate(@Valid @RequestBody BirthDateRequestDTO request,
                                                       @AuthenticationPrincipal User principal) {
        return ResponseEntity.ok(ageGateService.setBirthDate(principal, request.getBirthDate()));
    }

    @PostMapping("/verify-email")
    public ResponseEntity<AuthResponse> verifyEmail(@RequestBody VerifyEmailRequestDTO request) {
        return ResponseEntity.ok(registrationService.verifyEmail(request.getToken()));
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<Void> resendVerification(@RequestBody ResendVerificationRequestDTO request) {
        registrationService.resendVerification(request.getUsername(), request.getEmail());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequestDTO request) {
        passwordService.forgotPassword(request.getEmail());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequestDTO request) {
        passwordService.resetPassword(request.getToken(), request.getNewPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/google")
    public ResponseEntity<AuthResponse> google(@Valid @RequestBody GoogleAuthRequestDTO request) {
        return ResponseEntity.ok(googleAuthService.googleAuth(request.getIdToken()));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequestDTO loginRequest) {
        return ResponseEntity.ok(sessionService.login(loginRequest));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@RequestBody RefreshTokenRequestDTO request) {
        return ResponseEntity.ok(sessionService.refresh(request));
    }
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestBody RefreshTokenRequestDTO request
    ) {
        sessionService.logout(request);
        return ResponseEntity.ok().build();
    }

}
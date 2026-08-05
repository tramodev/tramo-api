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
import com.tramo.backend.auth.service.AuthService;
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
    private final AuthService authService;
    private final ClientIp clientIp;

    public AuthController(AuthService authService, ClientIp clientIp) {
        this.authService = authService;
        this.clientIp = clientIp;
    }

    @GetMapping("/check-username")
    public ResponseEntity<AvailabilityResponseDTO> checkUsername(@RequestParam String username) {
        return ResponseEntity.ok(authService.checkUsernameAvailability(username));
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponseDTO> register(@Valid @RequestBody RegisterRequestDTO registerRequest,
                                                          HttpServletRequest request) {
        return ResponseEntity.ok(authService.register(registerRequest, clientIp.from(request)));
    }

    @PostMapping("/birth-date")
    public ResponseEntity<AuthResponse> setBirthDate(@Valid @RequestBody BirthDateRequestDTO request,
                                                       @AuthenticationPrincipal User principal) {
        return ResponseEntity.ok(authService.setBirthDate(principal, request.getBirthDate()));
    }

    @PostMapping("/verify-email")
    public ResponseEntity<AuthResponse> verifyEmail(@RequestBody VerifyEmailRequestDTO request) {
        return ResponseEntity.ok(authService.verifyEmail(request.getToken()));
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<Void> resendVerification(@RequestBody ResendVerificationRequestDTO request) {
        authService.resendVerification(request.getUsername(), request.getEmail());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequestDTO request) {
        authService.forgotPassword(request.getEmail());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequestDTO request) {
        authService.resetPassword(request.getToken(), request.getNewPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/google")
    public ResponseEntity<AuthResponse> google(@Valid @RequestBody GoogleAuthRequestDTO request) {
        return ResponseEntity.ok(authService.googleAuth(request.getIdToken()));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequestDTO loginRequest) {
        return ResponseEntity.ok(authService.login(loginRequest));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@RequestBody RefreshTokenRequestDTO request) {
        return ResponseEntity.ok(authService.refresh(request));
    }
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestBody RefreshTokenRequestDTO request
    ) {
        authService.logout(request);
        return ResponseEntity.ok().build();
    }

}
package com.mypath.backend.auth.controller;

import com.mypath.backend.auth.dto.AuthResponse;
import com.mypath.backend.auth.dto.AvailabilityResponseDTO;
import com.mypath.backend.auth.dto.ForgotPasswordRequestDTO;
import com.mypath.backend.auth.dto.GoogleAuthRequestDTO;
import com.mypath.backend.auth.dto.LoginRequestDTO;
import com.mypath.backend.auth.dto.RegisterRequestDTO;
import com.mypath.backend.auth.dto.RegisterResponseDTO;
import com.mypath.backend.auth.dto.ResendVerificationRequestDTO;
import com.mypath.backend.auth.dto.ResetPasswordRequestDTO;
import com.mypath.backend.auth.dto.VerifyEmailRequestDTO;
import com.mypath.backend.auth.service.AuthService;
import com.mypath.backend.auth.dto.RefreshTokenRequestDTO;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
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
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/check-username")
    public ResponseEntity<AvailabilityResponseDTO> checkUsername(@RequestParam String username) {
        return ResponseEntity.ok(authService.checkUsernameAvailability(username));
    }

    @GetMapping("/check-email")
    public ResponseEntity<AvailabilityResponseDTO> checkEmail(@RequestParam String email) {
        return ResponseEntity.ok(authService.checkEmailAvailability(email));
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponseDTO> register(@Valid @RequestBody RegisterRequestDTO registerRequest){
        return ResponseEntity.ok(authService.register(registerRequest));
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
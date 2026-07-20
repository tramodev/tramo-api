package com.tramo.backend.user.controller;

import com.tramo.backend.auth.dto.ChangePasswordRequestDTO;
import com.tramo.backend.auth.service.AuthService;
import com.tramo.backend.user.dto.UpdatePreferencesRequestDTO;
import com.tramo.backend.user.dto.UserPreferencesDTO;
import com.tramo.backend.user.repository.UserRepository;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.service.UserAccountService;
import com.tramo.backend.user.service.UserPreferencesService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
@RestController
@RequestMapping("/user")
public class UserController {

        @Autowired
        private UserRepository userRepository;
        @Autowired
        private AuthService authService;
        @Autowired
        private UserAccountService userAccountService;
        @Autowired
        private UserPreferencesService userPreferencesService;

        @GetMapping("/getAll")
        public List<User> getAll() {
            return userRepository.findAll();
        }

        @PutMapping("/password")
        public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequestDTO request,
                                                    @AuthenticationPrincipal User user) {
            authService.changePassword(user, request.getCurrentPassword(), request.getNewPassword());
            return ResponseEntity.noContent().build();
        }

        @DeleteMapping("/me")
        public ResponseEntity<Void> deleteAccount(@AuthenticationPrincipal User user) {
            userAccountService.deleteAccount(user);
            return ResponseEntity.noContent().build();
        }

        @GetMapping("/preferences")
        public ResponseEntity<UserPreferencesDTO> getPreferences(@AuthenticationPrincipal User user) {
            return ResponseEntity.ok(userPreferencesService.getPreferences(user));
        }

        @PutMapping("/preferences")
        public ResponseEntity<UserPreferencesDTO> updatePreferences(@Valid @RequestBody UpdatePreferencesRequestDTO request,
                                                                      @AuthenticationPrincipal User user) {
            return ResponseEntity.ok(userPreferencesService.updatePreferences(user, request));
        }

}

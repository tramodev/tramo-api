package com.tramo.backend.subscription.controller;

import com.tramo.backend.subscription.dto.SubscriptionStatusDTO;
import com.tramo.backend.subscription.service.SubscriptionService;
import com.tramo.backend.user.entity.User;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/subscription")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @GetMapping
    public ResponseEntity<SubscriptionStatusDTO> status(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(subscriptionService.getStatus(user));
    }

    @PostMapping("/mock-upgrade")
    public ResponseEntity<SubscriptionStatusDTO> mockUpgrade(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(subscriptionService.mockUpgrade(user));
    }

    @DeleteMapping
    public ResponseEntity<SubscriptionStatusDTO> cancel(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(subscriptionService.cancel(user));
    }
}

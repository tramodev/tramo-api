// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.security.config;

import com.tramo.backend.user.repository.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class ApplicationConfig {

    private final UserRepository userRepository;

    public ApplicationConfig(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
    {
        return config.getAuthenticationManager();
    }

    @Bean
    public AuthenticationProvider authenticationProvider()
    {
        PasswordEncoder encoder = passwordEncoder();
        String fillerHash = encoder.encode("filler");
        DaoAuthenticationProvider authenticationProvider = new DaoAuthenticationProvider(userDetailService()) {
            @Override
            protected void additionalAuthenticationChecks(UserDetails userDetails,
                    UsernamePasswordAuthenticationToken authentication) {
                if (userDetails.getPassword() == null) {
                    encoder.matches(String.valueOf(authentication.getCredentials()), fillerHash);
                    throw new BadCredentialsException("Bad credentials");
                }
                super.additionalAuthenticationChecks(userDetails, authentication);
            }
        };
        authenticationProvider.setPasswordEncoder(encoder);
        authenticationProvider.setPreAuthenticationChecks(user -> { });
        authenticationProvider.setPostAuthenticationChecks(user -> {
            if (!user.isAccountNonLocked()) {
                throw new LockedException("User account is locked");
            }
            if (!user.isEnabled()) {
                throw new DisabledException("User is disabled");
            }
        });
        return authenticationProvider;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailService() {
        return username -> userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(()-> new UsernameNotFoundException("User not found"));
    }
}

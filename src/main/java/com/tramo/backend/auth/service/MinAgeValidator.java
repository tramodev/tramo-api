package com.tramo.backend.auth.service;

import com.tramo.backend.exception.UnderageRegistrationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.Period;

@Component
public class MinAgeValidator {

    @Value("${app.auth.min-age-years}")
    private int minAgeYears;

    public void validate(LocalDate birthDate) {
        if (birthDate == null || Period.between(birthDate, LocalDate.now()).getYears() < minAgeYears) {
            throw new UnderageRegistrationException(
                    "You do not meet the minimum age requirement to create an account.");
        }
    }
}

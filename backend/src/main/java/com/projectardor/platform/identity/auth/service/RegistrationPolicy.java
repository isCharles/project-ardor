package com.projectardor.platform.identity.auth.service;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Empty keeps the open local behavior; a configured list turns registration into an allowlist. */
@Component
public class RegistrationPolicy {

    private final Set<String> allowedEmails;

    public RegistrationPolicy(@Value("${app.auth.registration-allowlist:}") String allowlist) {
        this.allowedEmails = allowlist == null ? Set.of() : Arrays.stream(allowlist.split(","))
                .map(String::strip)
                .filter(email -> !email.isEmpty())
                .map(email -> email.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public void requireAllowed(String normalizedEmail) {
        if (!allowedEmails.isEmpty() && !allowedEmails.contains(normalizedEmail)) {
            throw new RegistrationNotAllowedException();
        }
    }
}

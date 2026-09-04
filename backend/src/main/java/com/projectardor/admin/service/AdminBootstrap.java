package com.projectardor.admin.service;

import java.util.Arrays;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.projectardor.auth.domain.UserRole;
import com.projectardor.auth.repository.UserAccountRepository;

@Component
public class AdminBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    private final UserAccountRepository repository;
    private final String configuredEmails;

    public AdminBootstrap(UserAccountRepository repository,
            @Value("${app.admin.emails:}") String configuredEmails) {
        this.repository = repository;
        this.configuredEmails = configuredEmails;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        long promoted = Arrays.stream(configuredEmails.split(","))
                .map(value -> value.strip().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .distinct()
                .map(repository::findByEmail)
                .flatMap(java.util.Optional::stream)
                .filter(user -> user.getRole() != UserRole.ADMIN)
                .peek(user -> user.setRole(UserRole.ADMIN))
                .count();
        if (promoted > 0) log.info("Promoted {} configured administrator account(s)", promoted);
    }
}

package com.projectardor.platform.identity.auth.security;

import java.net.URI;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import static org.assertj.core.api.Assertions.assertThat;

/** Explicit opt-in: inserts only an expiring synthetic session, never reads real sessions or users. */
@EnabledIfEnvironmentVariable(named = "ARDOR_SESSION_COMPAT_RUNTIME", matches = "true")
class LegacySessionRuntimeTests {
    @Test void legacySessionReachesCredentialFreshnessInsteadOfServerError() throws Exception {
        String id = UUID.randomUUID().toString();
        String key = "ardor:session:sessions:" + id;
        var serializer = new JdkSerializationRedisSerializer();
        var old = new com.projectardor.auth.security.ArdorPrincipal(UUID.randomUUID(),
                "session-fixture@example.invalid", "fixture-only", true, com.projectardor.auth.domain.UserRole.USER);
        var context = new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(old, null, old.getAuthorities()));
        try {
            put(key, "creationTime", serializer.serialize(System.currentTimeMillis()));
            put(key, "lastAccessedTime", serializer.serialize(System.currentTimeMillis()));
            put(key, "maxInactiveInterval", serializer.serialize(60));
            put(key, "sessionAttr:SPRING_SECURITY_CONTEXT", serializer.serialize(context));
            redis(null, "EXPIRE", key, "60");
            String cookie = Base64.getEncoder().encodeToString(id.getBytes(StandardCharsets.UTF_8));
            var connection = (HttpURLConnection) URI.create("http://127.0.0.1:3000/api/auth/me").toURL().openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("Cookie", "SESSION=" + cookie);
            try {
                assertThat(connection.getResponseCode()).isEqualTo(401);
                assertThat(new String(connection.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)).contains("SESSION_EXPIRED");
            } finally { connection.disconnect(); }
        } finally {
            redis(null, "DEL", key);
        }
    }

    private void put(String key, String field, byte[] bytes) throws Exception {
        redis(bytes, "-x", "HSET", key, field);
    }

    private void redis(byte[] input, String... arguments) throws Exception {
        var command = new java.util.ArrayList<>(java.util.List.of("docker", "exec", "-i", "project-ardor-redis-1", "redis-cli"));
        command.addAll(java.util.List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (var output = process.getOutputStream()) { if (input != null) output.write(input); }
        String reply = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertThat(process.waitFor()).isZero();
        assertThat(reply).matches("[0-9]+");
    }
}

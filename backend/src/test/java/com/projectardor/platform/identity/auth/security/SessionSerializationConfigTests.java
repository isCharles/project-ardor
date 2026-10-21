package com.projectardor.platform.identity.auth.security;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import com.projectardor.platform.identity.auth.domain.UserRole;
import static org.assertj.core.api.Assertions.*;

class SessionSerializationConfigTests {
    private final org.springframework.data.redis.serializer.RedisSerializer<Object> serializer =
            new SessionSerializationConfig().springSessionDefaultRedisSerializer();

    @Test void readsLegacyUserAndAdminSecurityContextsWithoutChangingIdentity() {
        for (com.projectardor.auth.domain.UserRole role : com.projectardor.auth.domain.UserRole.values()) {
            UUID id = UUID.randomUUID();
            var old = new com.projectardor.auth.security.ArdorPrincipal(id, "fixture@example.invalid", "fixture-hash", true, role);
            var context = new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(old, null, old.getAuthorities()));
            byte[] persisted = new JdkSerializationRedisSerializer().serialize(context);
            var restored = (SecurityContextImpl) serializer.deserialize(persisted);
            var principal = (ArdorPrincipal) restored.getAuthentication().getPrincipal();
            assertThat(principal).isEqualTo(new ArdorPrincipal(id, old.email(), old.passwordHash(), true, UserRole.valueOf(role.name())));
            assertThat(restored.getAuthentication().getAuthorities()).extracting("authority").containsExactly("ROLE_" + role.name());
            assertThat(restored.getAuthentication().isAuthenticated()).isTrue();
            byte[] rewritten = serializer.serialize(restored);
            assertThat(new String(rewritten, StandardCharsets.ISO_8859_1)).doesNotContain("com.projectardor.auth.security.ArdorPrincipal");
            assertThat(new JdkSerializationRedisSerializer().deserialize(rewritten)).isEqualTo(restored);
        }
    }

    @Test void currentPrincipalAndOrdinarySessionAttributesRoundTrip() {
        var principal = new ArdorPrincipal(UUID.randomUUID(), "fixture@example.invalid", "fixture", false, UserRole.USER);
        assertThat(serializer.deserialize(serializer.serialize(principal))).isEqualTo(principal);
        assertThat(serializer.deserialize(serializer.serialize("ordinary attribute"))).isEqualTo("ordinary attribute");
        assertThat(serializer.deserialize(new byte[0])).isNull();
    }

    @Test void corruptPayloadFailsClosedInsteadOfCreatingAuthentication() {
        assertThatThrownBy(() -> serializer.deserialize(new byte[] {1, 2, 3})).isInstanceOf(SerializationException.class);
    }

    @Test void legacyTypeWithUnexpectedSerialVersionIsNotSilentlyReinterpreted() {
        var old = new com.projectardor.auth.security.ArdorPrincipal(UUID.randomUUID(), "fixture@example.invalid",
                "fixture", true, com.projectardor.auth.domain.UserRole.USER);
        byte[] bytes = new JdkSerializationRedisSerializer().serialize(old);
        String wire = new String(bytes, StandardCharsets.ISO_8859_1);
        String name = "com.projectardor.auth.security.ArdorPrincipal";
        int descriptor = wire.indexOf(name);
        assertThat(descriptor).isPositive();
        bytes[descriptor + name.length() + 7] = 2;
        assertThatThrownBy(() -> serializer.deserialize(bytes)).isInstanceOf(SerializationException.class)
                .hasRootCauseInstanceOf(java.io.InvalidClassException.class);
    }
}

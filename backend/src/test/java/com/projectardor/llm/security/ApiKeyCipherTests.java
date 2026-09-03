package com.projectardor.llm.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ApiKeyCipherTests {

    private final ApiKeyCipher cipher = new ApiKeyCipher(
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");

    @Test
    void rejectsKnownWeakRepeatedByteKey() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new ApiKeyCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("弱密钥");
    }

    @Test
    void encryptsAndDecryptsForTheSameUser() {
        UUID userId = UUID.randomUUID();
        String apiKey = "sk-private-example-1234";

        ApiKeyCipher.EncryptedValue encrypted = cipher.encrypt(userId, apiKey);

        assertThat(encrypted.ciphertext()).isNotEqualTo(apiKey.getBytes(StandardCharsets.UTF_8));
        assertThat(cipher.decrypt(userId, encrypted.ciphertext(), encrypted.iv())).isEqualTo(apiKey);
    }

    @Test
    void rejectsCiphertextForAnotherUser() {
        UUID ownerId = UUID.randomUUID();
        ApiKeyCipher.EncryptedValue encrypted = cipher.encrypt(ownerId, "sk-private");

        assertThatThrownBy(() -> cipher.decrypt(
                UUID.randomUUID(), encrypted.ciphertext(), encrypted.iv()))
                .isInstanceOf(IllegalStateException.class);
    }
}

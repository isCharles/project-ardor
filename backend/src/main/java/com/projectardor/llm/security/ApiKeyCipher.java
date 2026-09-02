package com.projectardor.llm.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ApiKeyCipher {

    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKeySpec encryptionKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public ApiKeyCipher(@Value("${app.security.api-key-encryption-key}") String base64Key) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("ARDOR_ENCRYPTION_KEY 必须是合法的 Base64", exception);
        }
        if (key.length != 32) {
            throw new IllegalStateException("ARDOR_ENCRYPTION_KEY 解码后必须正好为 32 字节");
        }
        this.encryptionKey = new SecretKeySpec(key, "AES");
    }

    public EncryptedValue encrypt(UUID userId, String plaintext) {
        byte[] iv = new byte[IV_LENGTH];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = newCipher(Cipher.ENCRYPT_MODE, userId, iv);
            return new EncryptedValue(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)), iv);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("无法加密 API Key", exception);
        }
    }

    public String decrypt(UUID userId, byte[] ciphertext, byte[] iv) {
        try {
            Cipher cipher = newCipher(Cipher.DECRYPT_MODE, userId, iv);
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("无法解密 API Key，请检查服务端加密密钥", exception);
        }
    }

    private Cipher newCipher(int mode, UUID userId, byte[] iv) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, encryptionKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
        cipher.updateAAD(userId.toString().getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    public record EncryptedValue(byte[] ciphertext, byte[] iv) {
    }
}

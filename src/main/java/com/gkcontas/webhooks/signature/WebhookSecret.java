package com.gkcontas.webhooks.signature;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * A shared secret in the {@code whsec_<base64>} form. The key is the decoded
 * bytes, not the string: two parties that HMAC with the textual secret on one
 * side and the decoded bytes on the other never agree, and nothing tells them why.
 */
public record WebhookSecret(String value) {

    private static final String PREFIX = "whsec_";
    private static final SecureRandom RANDOM = new SecureRandom();

    public WebhookSecret {
        if (value == null || !value.startsWith(PREFIX)) {
            throw new IllegalArgumentException("secret must start with " + PREFIX);
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(value.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("secret must be base64 after " + PREFIX);
        }
        if (key.length < 24) {
            throw new IllegalArgumentException("secret must carry at least 24 bytes of key");
        }
    }

    public static WebhookSecret generate() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return new WebhookSecret(PREFIX + Base64.getEncoder().encodeToString(key));
    }

    public byte[] key() {
        return Base64.getDecoder().decode(value.substring(PREFIX.length()));
    }

    @Override
    public String toString() {
        return "WebhookSecret[redacted]";
    }
}

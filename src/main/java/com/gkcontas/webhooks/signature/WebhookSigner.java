package com.gkcontas.webhooks.signature;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 over {@code id.timestamp.body}. The id and the timestamp are inside
 * the signed content on purpose: signing the body alone lets anyone who captured
 * one request replay it with a fresh timestamp header.
 */
public final class WebhookSigner {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String VERSION = "v1";

    private WebhookSigner() {
    }

    /** The value of one signature entry: {@code v1,<base64>}. */
    public static String sign(WebhookSecret secret, String id, long timestamp, byte[] body) {
        return VERSION + "," + Base64.getEncoder().encodeToString(mac(secret, id, timestamp, body));
    }

    /**
     * The header value for every secret currently in use, space separated. During
     * a rotation the receiver may know either one.
     */
    public static String signatureHeader(List<WebhookSecret> secrets, String id, long timestamp, byte[] body) {
        List<String> entries = new ArrayList<>(secrets.size());
        for (WebhookSecret secret : secrets) {
            entries.add(sign(secret, id, timestamp, body));
        }
        return String.join(" ", entries);
    }

    /**
     * Constant time: {@link MessageDigest#isEqual} looks at every byte whatever the
     * first mismatch, where {@code String.equals} returns at the first difference
     * and leaks how many leading bytes an attacker already got right.
     */
    public static boolean matches(WebhookSecret secret, String id, long timestamp, byte[] body, String header) {
        if (header == null || header.isBlank()) {
            return false;
        }
        byte[] expected = mac(secret, id, timestamp, body);
        for (String entry : header.trim().split("\\s+")) {
            int comma = entry.indexOf(',');
            if (comma < 0 || !VERSION.equals(entry.substring(0, comma))) {
                continue;
            }
            byte[] presented;
            try {
                presented = Base64.getDecoder().decode(entry.substring(comma + 1));
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (MessageDigest.isEqual(expected, presented)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] mac(WebhookSecret secret, String id, long timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.key(), ALGORITHM));
            mac.update((id + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8));
            mac.update(body);
            return mac.doFinal();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}

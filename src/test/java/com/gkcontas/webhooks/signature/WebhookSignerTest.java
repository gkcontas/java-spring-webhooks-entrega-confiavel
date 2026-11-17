package com.gkcontas.webhooks.signature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WebhookSignerTest {

    private static final WebhookSecret SECRET = WebhookSecret.generate();
    private static final byte[] BODY = "{\"type\":\"order.paid\",\"data\":{\"amount\":10}}"
            .getBytes(StandardCharsets.UTF_8);

    @Test
    void verifiesWhatItSigned() {
        String header = WebhookSigner.sign(SECRET, "msg_1", 1_700_000_000L, BODY);

        assertThat(header).startsWith("v1,");
        assertThat(WebhookSigner.matches(SECRET, "msg_1", 1_700_000_000L, BODY, header)).isTrue();
    }

    @Test
    void theIdAndTheTimestampAreSignedTooSoNeitherCanBeSwapped() {
        String header = WebhookSigner.sign(SECRET, "msg_1", 1_700_000_000L, BODY);

        assertThat(WebhookSigner.matches(SECRET, "msg_2", 1_700_000_000L, BODY, header)).isFalse();
        assertThat(WebhookSigner.matches(SECRET, "msg_1", 1_700_000_001L, BODY, header)).isFalse();
    }

    @Test
    void oneFlippedByteInTheBodyFails() {
        String header = WebhookSigner.sign(SECRET, "msg_1", 1L, BODY);
        byte[] tampered = BODY.clone();
        tampered[tampered.length - 3] = '9';

        assertThat(WebhookSigner.matches(SECRET, "msg_1", 1L, tampered, header)).isFalse();
    }

    @Test
    void anotherSecretFails() {
        String header = WebhookSigner.sign(SECRET, "msg_1", 1L, BODY);

        assertThat(WebhookSigner.matches(WebhookSecret.generate(), "msg_1", 1L, BODY, header)).isFalse();
    }

    @Test
    void duringRotationEitherSecretVerifiesTheSameHeader() {
        WebhookSecret next = WebhookSecret.generate();
        String header = WebhookSigner.signatureHeader(List.of(next, SECRET), "msg_1", 1L, BODY);

        assertThat(header.split(" ")).hasSize(2);
        assertThat(WebhookSigner.matches(SECRET, "msg_1", 1L, BODY, header)).isTrue();
        assertThat(WebhookSigner.matches(next, "msg_1", 1L, BODY, header)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "v1", "v1,", "v1,!!!notbase64", "v2,AAAA", "garbage"})
    void malformedHeadersAreRejectedWithoutThrowing(String header) {
        assertThat(WebhookSigner.matches(SECRET, "msg_1", 1L, BODY, header)).isFalse();
    }

    @Test
    void secretsAreValidatedAndNeverPrinted() {
        assertThatThrownBy(() -> new WebhookSecret("plain-text-secret"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebhookSecret("whsec_c2hvcnQ="))
                .hasMessageContaining("24 bytes");
        assertThat(SECRET.toString()).doesNotContain(SECRET.value());
    }

    /**
     * The receiver-side pitfall: verifying a body that went through the JSON
     * mapper and back. Every one of these is the same JSON value, and none of them
     * survives the round trip byte for byte.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"a\": 1}",                              // whitespace
            "{\"amount\":1.0}",                        // trailing zero kept as 1.0, but...
            "{\"amount\":1e2}",                        // exponent becomes 100.0
            "{\"name\":\"caf\\u00e9\"}",               // escaped unicode becomes a raw character
            "{\"url\":\"a\\/b\"}",                     // escaped slash is dropped
            "{\n  \"type\":\"x\"\n}"                   // pretty printed by the sender
    })
    void reserialisedJsonNoLongerMatchesTheSignature(String raw) throws Exception {
        byte[] original = raw.getBytes(StandardCharsets.UTF_8);
        String header = WebhookSigner.sign(SECRET, "msg_1", 1L, original);

        JsonNode parsed = new ObjectMapper().readTree(original);
        byte[] reserialised = new ObjectMapper().writeValueAsBytes(parsed);

        if (raw.equals("{\"amount\":1.0}")) {
            // The one case Jackson happens to reproduce; it is luck, not a contract.
            assertThat(reserialised).isEqualTo(original);
            return;
        }
        assertThat(reserialised).isNotEqualTo(original);
        assertThat(WebhookSigner.matches(SECRET, "msg_1", 1L, reserialised, header)).isFalse();
        assertThat(WebhookSigner.matches(SECRET, "msg_1", 1L, original, header)).isTrue();
    }
}

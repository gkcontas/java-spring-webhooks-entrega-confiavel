package com.gkcontas.webhooks.sender;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TargetGuardTest {

    private static final TargetGuard GUARD = new TargetGuard(new SenderProperties(true, Duration.ofMillis(200), 50,
            Duration.ofSeconds(30), Duration.ofSeconds(2), Duration.ofSeconds(5), 10, Duration.ofSeconds(1), 2.0,
            Duration.ofMinutes(1), SenderProperties.Jitter.FULL, false, Duration.ofHours(24), "node"));

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.0.0.5", "172.16.3.4", "192.168.1.1", "169.254.169.254",
            "100.64.0.1", "0.0.0.0", "::1", "fd00::1", "fe80::1"})
    void internalAddressesAreRejected(String address) throws Exception {
        assertThat(TargetGuard.isInternal(InetAddress.getByName(address))).isTrue();
        String host = address.contains(":") ? "[" + address + "]" : address;
        assertThat(GUARD.rejectionReason(URI.create("http://" + host + "/hook")))
                .startsWith("target resolves to a non-public address");
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "1.1.1.1", "100.63.255.255", "2606:4700:4700::1111"})
    void publicAddressesPass(String address) throws Exception {
        assertThat(TargetGuard.isInternal(InetAddress.getByName(address))).isFalse();
    }
}

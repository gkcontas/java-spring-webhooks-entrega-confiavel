package com.gkcontas.webhooks.sender;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import org.springframework.stereotype.Component;

/**
 * The sender makes requests to URLs that somebody else typed. Without this check a
 * subscription to http://169.254.169.254/ or http://localhost:5432/ turns the
 * sender into a proxy into its own network.
 *
 * <p>The check runs before every attempt, not only when the subscription is
 * created, because DNS answers change: a name that resolved to a public address
 * at registration can point to 10.0.0.5 the next day.
 */
@Component
public class TargetGuard {

    private final boolean allowPrivate;

    public TargetGuard(SenderProperties properties) {
        this.allowPrivate = properties.allowPrivateTargets();
    }

    /** @return null when the target is allowed, otherwise the reason it is not */
    public String rejectionReason(URI target) {
        if (allowPrivate) {
            return null;
        }
        String host = target.getHost();
        if (host == null) {
            return "target has no host";
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (isInternal(address)) {
                    return "target resolves to a non-public address: " + address.getHostAddress();
                }
            }
            return null;
        } catch (UnknownHostException e) {
            return null; // let the HTTP client fail and retry: DNS outages are transient
        }
    }

    static boolean isInternal(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        if (address instanceof Inet6Address) {
            byte first = address.getAddress()[0];
            return (first & 0xFE) == 0xFC; // fc00::/7, unique local
        }
        byte[] bytes = address.getAddress();
        int a = bytes[0] & 0xFF;
        int b = bytes[1] & 0xFF;
        return a == 100 && b >= 64 && b <= 127; // 100.64.0.0/10, carrier-grade NAT
    }
}

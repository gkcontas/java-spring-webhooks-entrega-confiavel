package com.gkcontas.webhooks.sender.delivery;

import java.time.Duration;

/** What one HTTP attempt means for the delivery. */
public sealed interface DeliveryOutcome {

    Integer statusCode();

    String error();

    /** 2xx. */
    record Delivered(Integer statusCode) implements DeliveryOutcome {
        public String error() {
            return null;
        }
    }

    /** 408, 429, 5xx, timeouts and network errors: the receiver may succeed later. */
    record Retryable(Integer statusCode, String error, Duration retryAfter) implements DeliveryOutcome {
    }

    /** Other 4xx and 3xx: repeating the same bytes gives the same answer. */
    record Permanent(Integer statusCode, String error) implements DeliveryOutcome {
    }

    /** 410: the receiver says the endpoint is gone; stop sending to it at all. */
    record Gone(Integer statusCode) implements DeliveryOutcome {
        public String error() {
            return "endpoint gone, subscription disabled";
        }
    }

    static DeliveryOutcome fromStatus(int status, Duration retryAfter) {
        if (status >= 200 && status < 300) {
            return new Delivered(status);
        }
        if (status == 410) {
            return new Gone(status);
        }
        if (status == 408 || status == 429 || status >= 500) {
            return new Retryable(status, "HTTP " + status, retryAfter);
        }
        if (status >= 300 && status < 400) {
            // Following a redirect would send the signed payload to a URL nobody vetted.
            return new Permanent(status, "redirect not followed");
        }
        return new Permanent(status, "HTTP " + status);
    }
}

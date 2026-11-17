package com.gkcontas.webhooks.signature;

/** Header names from the Standard Webhooks specification. */
public final class WebhookHeaders {

    public static final String ID = "webhook-id";
    public static final String TIMESTAMP = "webhook-timestamp";
    public static final String SIGNATURE = "webhook-signature";

    private WebhookHeaders() {
    }
}

package com.gkcontas.webhooks.receiver;

/** Any reason to refuse a request before it reaches the inbox. Answered with 401. */
public class InvalidWebhookException extends RuntimeException {

    public InvalidWebhookException(String message) {
        super(message);
    }
}

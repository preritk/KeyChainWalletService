package com.keychain.wallet.exception;

public class IdempotencyKeyMismatchException extends RuntimeException {
    public IdempotencyKeyMismatchException(String key) {
        super("Idempotency key '" + key + "' was already used with different request parameters");
    }
}

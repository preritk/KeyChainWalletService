package com.keychain.wallet.exception;

public class InvalidCursorException extends RuntimeException {
    public InvalidCursorException() {
        super("Invalid or malformed nextToken");
    }
}

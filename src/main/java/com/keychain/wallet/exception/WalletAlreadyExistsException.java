package com.keychain.wallet.exception;

public class WalletAlreadyExistsException extends RuntimeException {
    public WalletAlreadyExistsException(String customerId) {
        super("Wallet already exists for customer: " + customerId);
    }
}

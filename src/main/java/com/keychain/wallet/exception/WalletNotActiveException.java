package com.keychain.wallet.exception;

public class WalletNotActiveException extends RuntimeException {
    public WalletNotActiveException(String status) {
        super("Wallet is not active. Status: " + status);
    }
}

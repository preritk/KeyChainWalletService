package com.keychain.wallet.exception;

public class WalletAccessDeniedException extends RuntimeException {
    public WalletAccessDeniedException() {
        super("Access denied: wallet does not belong to the authenticated customer");
    }
}

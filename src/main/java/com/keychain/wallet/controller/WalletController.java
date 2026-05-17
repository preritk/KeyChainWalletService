package com.keychain.wallet.controller;

import com.keychain.wallet.dto.request.DeductRequest;
import com.keychain.wallet.dto.request.TopUpRequest;
import com.keychain.wallet.dto.response.BalanceResponse;
import com.keychain.wallet.dto.response.CursorPagedResponse;
import com.keychain.wallet.dto.response.DeductResponse;
import com.keychain.wallet.dto.response.TopUpResponse;
import com.keychain.wallet.dto.response.TransactionResponse;
import com.keychain.wallet.dto.response.WalletResponse;
import com.keychain.wallet.manager.WalletManager;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/wallets")
public class WalletController {

    private final WalletManager walletManager;

    public WalletController(WalletManager walletManager) {
        this.walletManager = walletManager;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WalletResponse createWallet(@AuthenticationPrincipal Jwt jwt) {
        return walletManager.createWallet(jwt.getSubject());
    }

    @PostMapping("/{id}/topup")
    public TopUpResponse topUp(
            @PathVariable String id,
            @Valid @RequestBody TopUpRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return walletManager.topUp(id, jwt.getSubject(), request.amount());
    }

    @PostMapping("/{id}/deduct")
    public DeductResponse deduct(
            @PathVariable String id,
            @Valid @RequestBody DeductRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return walletManager.deduct(id, request, jwt.getSubject());
    }

    @GetMapping("/{id}/balance")
    public BalanceResponse getBalance(
            @PathVariable String id,
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication) {
        String callerId = isServiceToken(authentication) ? null : jwt.getSubject();
        return walletManager.getBalance(id, callerId);
    }

    @GetMapping("/{id}/transactions")
    public CursorPagedResponse<TransactionResponse> getTransactions(
            @PathVariable String id,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String nextToken,
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication) {
        String callerId = isServiceToken(authentication) ? null : jwt.getSubject();
        return walletManager.getTransactions(id, callerId, size, nextToken);
    }

    private boolean isServiceToken(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_SERVICE"));
    }
}

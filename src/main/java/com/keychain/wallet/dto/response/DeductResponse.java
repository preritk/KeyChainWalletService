package com.keychain.wallet.dto.response;

import java.math.BigDecimal;

public record DeductResponse(
    String walletId,
    String customerId,
    String transactionId,
    String orderId,
    BigDecimal amount,
    BigDecimal balanceBefore,
    BigDecimal balanceAfter,
    String currency
) {}

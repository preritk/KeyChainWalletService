package com.keychain.wallet.dto.response;

import java.math.BigDecimal;

public record TopUpResponse(
    String walletId,
    String customerId,
    String transactionId,
    BigDecimal amount,
    BigDecimal balanceBefore,
    BigDecimal balanceAfter,
    String currency
) {}

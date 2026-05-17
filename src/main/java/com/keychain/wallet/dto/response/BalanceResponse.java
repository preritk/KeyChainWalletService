package com.keychain.wallet.dto.response;

import java.math.BigDecimal;

public record BalanceResponse(
    String walletId,
    String customerId,
    BigDecimal balance,
    String currency
) {}

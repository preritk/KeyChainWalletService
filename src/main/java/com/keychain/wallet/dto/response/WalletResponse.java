package com.keychain.wallet.dto.response;

import java.math.BigDecimal;

public record WalletResponse(
    String id,
    String customerId,
    BigDecimal balance,
    String currency,
    String status
) {}

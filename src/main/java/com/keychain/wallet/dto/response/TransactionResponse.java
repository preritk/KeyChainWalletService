package com.keychain.wallet.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record TransactionResponse(
    String id,
    String type,
    BigDecimal amount,
    BigDecimal balanceBefore,
    BigDecimal balanceAfter,
    String status,
    String referenceId,
    String referenceType,
    OffsetDateTime createdAt
) {}

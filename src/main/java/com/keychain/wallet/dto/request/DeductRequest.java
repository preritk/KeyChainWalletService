package com.keychain.wallet.dto.request;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record DeductRequest(
    @NotBlank(message = "orderId is required")
    String orderId,

    @NotNull(message = "requestTimestamp is required")
    @Positive(message = "requestTimestamp must be a positive epoch millisecond value")
    Long requestTimestamp,

    @NotBlank(message = "customerId is required")
    String customerId,

    @NotNull(message = "amount is required")
    @Positive(message = "amount must be greater than 0")
    @Digits(integer = 16, fraction = 2, message = "amount must have at most 2 decimal places")
    BigDecimal amount
) {}

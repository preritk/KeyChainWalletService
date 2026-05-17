package com.keychain.wallet.dto.response;

import java.util.List;

public record CursorPagedResponse<T>(
    List<T> content,
    int size,
    String nextToken
) {}

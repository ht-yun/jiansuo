package com.guquan.equity.service;

import java.time.LocalDateTime;
import lombok.Getter;

@Getter
public class GsxtAccessRateLimitException extends RuntimeException {

    private final LocalDateTime retryAt;

    public GsxtAccessRateLimitException(String message, LocalDateTime retryAt) {
        super(message);
        this.retryAt = retryAt;
    }
}

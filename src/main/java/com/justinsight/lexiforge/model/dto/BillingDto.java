package com.justinsight.lexiforge.model.dto;

import jakarta.validation.constraints.*;
import jakarta.validation.Valid;

public final class BillingDto {
    private BillingDto() {}
    public record Create(@NotBlank @Size(max=64) String requestId,
                         @NotBlank @Pattern(regexp="MONTH|ANNUAL") String cycle,
                         @NotBlank @Pattern(regexp="ALIPAY_SIMULATOR|WECHAT_SIMULATOR") String channel) {}
    public record Order(String id, String channel, String cycle, String planName, long amountMinor,
                        String currency, String status, String tradeNo, String createdAt, String expiresAt,
                        String paidAt) {}
    public record Event(@NotBlank @Pattern(regexp="[0-9]+") String orderId,
                        @NotBlank @Size(max=80) String tradeNo, @Positive long amountMinor,
                        @NotBlank String currency, @NotBlank String channel,
                        @NotBlank @Pattern(regexp="PAID|REFUNDED") String status, @Min(1) int version) {}
    public record Notify(@NotNull @Valid Event event, @NotBlank @Size(max=128) String signature) {}
    public record Config(boolean enabled, String mode, boolean officialChannelConnected) {}
}

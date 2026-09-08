package com.justinsight.lexiforge.model.dto;

import java.math.BigDecimal;

public final class CommercialDto {
    private CommercialDto() {}

    public record PlanView(String id, String code, String name, BigDecimal monthlyPrice,
                           BigDecimal annualPrice, int translationLimit, int gradingLimit,
                           int mnemonicLimit, int customDomainLimit, int mnemonicVersions) {}
    public record SubscriptionView(String id, String planCode, String status,
                                   String startsAt, String expiresAt) {}
    public record Dashboard(int vocabularyCount, int dueCount, int reviewedToday,
                            double accuracy7d, int streakDays) {}
}

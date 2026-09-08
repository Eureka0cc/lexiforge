package com.justinsight.lexiforge.service;

import java.time.LocalDate;

public interface EntitlementService {
    Plan plan(long userId);

    Usage consume(long userId, UsageType type);

    Usage consume(long userId, UsageType type, LocalDate today);

    void release(long userId, UsageType type, LocalDate usageDate);

    Usage usage(long userId);

    enum UsageType { TRANSLATION, LOOKUP, GRADING, MNEMONIC }

    record Plan(String code, String name, int translationLimit, int gradingLimit,
                int mnemonicLimit, int customDomainLimit, int mnemonicVersions) {}

    record Usage(Plan plan, int translationsUsed, int lookupsUsed, int gradingsUsed,
                 int mnemonicsUsed, String resetsAt) {}
}

package com.justinsight.lexiforge.service.impl;

import com.justinsight.lexiforge.service.EntitlementService;
import com.justinsight.lexiforge.common.id.SnowflakeIdGenerator;
import com.justinsight.lexiforge.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

@Service
public class EntitlementServiceImpl implements EntitlementService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcClient jdbc;
    private final SnowflakeIdGenerator ids;
    private final boolean unlimitedUsage;

    public EntitlementServiceImpl(JdbcClient jdbc, SnowflakeIdGenerator ids,
                                  @Value("${app.development-unlimited-usage:false}") boolean unlimitedUsage) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.unlimitedUsage = unlimitedUsage;
    }

    @Override
    public Plan plan(long userId) {
        return jdbc.sql("""
                SELECT p.code,p.name,p.translation_limit,p.grading_limit,p.mnemonic_limit,
                       p.custom_domain_limit,p.mnemonic_versions
                FROM subscription s
                JOIN plan_catalog p ON p.code = CASE
                    WHEN s.status='ACTIVE' AND (s.expires_at IS NULL OR s.expires_at>now())
                    THEN s.plan_code ELSE 'FREE' END
                WHERE s.user_id=:uid
                """)
                .param("uid", userId)
                .query((rs, n) -> new Plan(rs.getString(1), rs.getString(2), rs.getInt(3),
                        rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7)))
                .single();
    }

    @Override
    @Transactional
    public Usage consume(long userId, UsageType type) {
        return consume(userId, type, LocalDate.now(ZONE));
    }

    @Override
    @Transactional
    public Usage consume(long userId, UsageType type, LocalDate today) {
        jdbc.sql("INSERT INTO daily_usage(id,user_id,usage_date) VALUES (:id,:uid,:day) " +
                        "ON CONFLICT(user_id,usage_date) DO NOTHING")
                .param("id", ids.nextId()).param("uid", userId).param("day", today).update();
        Plan plan = plan(userId);
        String column = switch (type) {
            case TRANSLATION -> "translation_count";
            case LOOKUP -> "lookup_count";
            case GRADING -> "grading_count";
            case MNEMONIC -> "mnemonic_count";
        };
        int limit = switch (type) {
            case TRANSLATION -> plan.translationLimit();
            case LOOKUP -> plan.translationLimit() * 5;
            case GRADING -> plan.gradingLimit();
            case MNEMONIC -> plan.mnemonicLimit();
        };
        int updated;
        if (unlimitedUsage) {
            updated = jdbc.sql("UPDATE daily_usage SET " + column + "=" + column +
                            "+1 WHERE user_id=:uid AND usage_date=:day")
                    .param("uid", userId).param("day", today).update();
        } else {
            updated = jdbc.sql("UPDATE daily_usage SET " + column + "=" + column +
                            "+1 WHERE user_id=:uid AND usage_date=:day AND " + column + "<:lim")
                    .param("uid", userId).param("day", today).param("lim", limit).update();
        }
        if (updated == 0) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "DAILY_QUOTA_EXCEEDED",
                    "今日额度已用完，可升级 Pro 或明日再试");
        }
        return usage(userId);
    }

    @Override
    @Transactional
    public void release(long userId, UsageType type, LocalDate usageDate) {
        String column = switch (type) {
            case TRANSLATION -> "translation_count";
            case LOOKUP -> "lookup_count";
            case GRADING -> "grading_count";
            case MNEMONIC -> "mnemonic_count";
        };
        jdbc.sql("UPDATE daily_usage SET " + column + "=GREATEST(0," + column +
                        "-1) WHERE user_id=:uid AND usage_date=:day")
                .param("uid", userId).param("day", usageDate).update();
    }

    @Override
    public Usage usage(long userId) {
        Plan plan = plan(userId);
        LocalDate date = LocalDate.now(ZONE);
        var rows = jdbc.sql("SELECT translation_count,lookup_count,grading_count,mnemonic_count " +
                        "FROM daily_usage WHERE user_id=:uid AND usage_date=:d")
                .param("uid", userId).param("d", date).query().listOfRows();
        Map<String, Object> row = rows.isEmpty() ? Map.of() : rows.getFirst();
        return new Usage(plan, number(row, "translation_count"), number(row, "lookup_count"), number(row, "grading_count"),
                number(row, "mnemonic_count"), date.plusDays(1).atStartOfDay(ZONE).toInstant().toString());
    }

    private int number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number number ? number.intValue() : 0;
    }
}

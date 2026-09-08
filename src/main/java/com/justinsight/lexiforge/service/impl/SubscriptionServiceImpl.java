package com.justinsight.lexiforge.service.impl;

import com.justinsight.lexiforge.service.SubscriptionService;
import com.justinsight.lexiforge.model.dto.CommercialDto;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class SubscriptionServiceImpl implements SubscriptionService {
    private final JdbcClient jdbc;

    public SubscriptionServiceImpl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public CommercialDto.SubscriptionView current(long userId) {
        return view(userId);
    }

    @Override
    @Transactional
    public CommercialDto.SubscriptionView activate(long userId, int days) {
        Instant now = Instant.now();
        int boundedDays = Math.max(1, Math.min(days, 365));
        jdbc.sql("UPDATE subscription SET plan_code='PRO',status='ACTIVE',starts_at=:starts," +
                        "expires_at=:expires,updated_at=now() WHERE user_id=:uid")
                .param("starts", now).param("expires", now.plus(boundedDays, ChronoUnit.DAYS))
                .param("uid", userId).update();
        return view(userId);
    }

    @Override
    @Transactional
    public CommercialDto.SubscriptionView expire(long userId) {
        jdbc.sql("UPDATE subscription SET status='EXPIRED',expires_at=now(),updated_at=now() " +
                        "WHERE user_id=:uid")
                .param("uid", userId).update();
        return view(userId);
    }

    private CommercialDto.SubscriptionView view(long userId) {
        return jdbc.sql("SELECT * FROM subscription WHERE user_id=:uid")
                .param("uid", userId)
                .query((row, n) -> new CommercialDto.SubscriptionView(
                        Long.toString(row.getLong("id")), row.getString("plan_code"),
                        row.getString("status"),
                        row.getTimestamp("starts_at") == null ? null
                                : row.getTimestamp("starts_at").toInstant().toString(),
                        row.getTimestamp("expires_at") == null ? null
                                : row.getTimestamp("expires_at").toInstant().toString()))
                .single();
    }
}

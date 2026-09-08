package com.justinsight.lexiforge.service.impl;

import com.justinsight.lexiforge.service.AdminMetricsService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AdminMetricsServiceImpl implements AdminMetricsService {
    private final JdbcClient jdbc;

    public AdminMetricsServiceImpl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, Object> metrics() {
        int users = query("SELECT count(*) FROM app_user");
        int dailyActiveUsers = query("SELECT count(DISTINCT user_id) FROM product_event " +
                "WHERE occurred_at>=CURRENT_DATE");
        int weeklyActiveUsers = query("SELECT count(DISTINCT user_id) FROM product_event " +
                "WHERE occurred_at>=now()-interval '7 days'");
        int activePro = query("SELECT count(*) FROM subscription WHERE status='ACTIVE' " +
                "AND plan_code='PRO' AND expires_at>now()");
        int mnemonics = query("SELECT count(*) FROM mnemonic");
        int helpful = query("SELECT count(*) FROM mnemonic WHERE helpful=true");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("users", users);
        result.put("dau", dailyActiveUsers);
        result.put("wau", weeklyActiveUsers);
        result.put("activePro", activePro);
        result.put("estimatedMrr", BigDecimal.valueOf(activePro * 19.9));
        result.put("mnemonics", mnemonics);
        result.put("mnemonicHelpfulRate",
                mnemonics == 0 ? 0 : Math.round(helpful * 1000.0 / mnemonics) / 10.0);
        return result;
    }

    private int query(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }
}

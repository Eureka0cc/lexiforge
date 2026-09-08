package com.justinsight.lexiforge.service.impl;

import com.justinsight.lexiforge.service.DashboardService;
import com.justinsight.lexiforge.model.dto.CommercialDto;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import java.sql.Date;

@Service
public class DashboardServiceImpl implements DashboardService {
    private final JdbcClient jdbc;

    public DashboardServiceImpl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public CommercialDto.Dashboard get(long userId) {
        int vocabulary = count("SELECT count(*) FROM vocabulary_item WHERE user_id=:uid", userId);
        int due = count("SELECT count(*) FROM review_schedule WHERE user_id=:uid AND due_at<=now()", userId);
        int reviewed = count("SELECT count(*) FROM review_attempt " +
                "WHERE user_id=:uid AND created_at>=CURRENT_DATE", userId);
        Map<String, Object> accuracy = jdbc.sql("SELECT count(*) total," +
                        "count(*) FILTER(WHERE result='CORRECT') correct FROM review_attempt " +
                        "WHERE user_id=:uid AND created_at>=now()-interval '7 days'")
                .param("uid", userId).query().singleRow();
        int total = ((Number) accuracy.get("total")).intValue();
        int correct = ((Number) accuracy.get("correct")).intValue();
        double accuracy7d = total == 0 ? 0 : Math.round(correct * 1000.0 / total) / 10.0;
        return new CommercialDto.Dashboard(vocabulary, due, reviewed, accuracy7d, streak(userId));
    }

    private int count(String sql, long userId) {
        return jdbc.sql(sql).param("uid", userId).query(Integer.class).single();
    }

    private int streak(long userId) {
        List<Date> days = jdbc.sql("SELECT DISTINCT created_at::date d FROM review_attempt " +
                        "WHERE user_id=:uid ORDER BY d DESC LIMIT 365")
                .param("uid", userId).query(Date.class).list();
        LocalDate expected = LocalDate.now();
        int streak = 0;
        for (var day : days) {
            LocalDate date = day.toLocalDate();
            if (date.equals(expected)) {
                streak++;
                expected = expected.minusDays(1);
            } else if (!date.isAfter(expected)) {
                break;
            }
        }
        return streak;
    }
}

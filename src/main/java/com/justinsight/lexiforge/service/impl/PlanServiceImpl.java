package com.justinsight.lexiforge.service.impl;

import com.justinsight.lexiforge.service.PlanService;
import com.justinsight.lexiforge.model.dto.CommercialDto;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PlanServiceImpl implements PlanService {
    private final JdbcClient jdbc;

    public PlanServiceImpl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<CommercialDto.PlanView> list() {
        return jdbc.sql("SELECT * FROM plan_catalog ORDER BY monthly_price")
                .query((row, n) -> new CommercialDto.PlanView(Long.toString(row.getLong("id")),
                        row.getString("code"), row.getString("name"),
                        row.getBigDecimal("monthly_price"), row.getBigDecimal("annual_price"),
                        row.getInt("translation_limit"), row.getInt("grading_limit"),
                        row.getInt("mnemonic_limit"), row.getInt("custom_domain_limit"),
                        row.getInt("mnemonic_versions")))
                .list();
    }
}

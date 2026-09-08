package com.justinsight.lexiforge.service.impl;

import com.justinsight.lexiforge.service.EventService;
import com.justinsight.lexiforge.common.id.SnowflakeIdGenerator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class EventServiceImpl implements EventService {
    private final JdbcClient jdbc;
    private final SnowflakeIdGenerator ids;

    public EventServiceImpl(JdbcClient jdbc, SnowflakeIdGenerator ids) {
        this.jdbc = jdbc;
        this.ids = ids;
    }

    @Override
    public void track(Long userId, String name, String propertiesJson) {
        jdbc.sql("INSERT INTO product_event(id,user_id,event_name,properties_json) VALUES(:id,:uid,:n,:p)")
                .param("id", ids.nextId())
                .param("uid", userId)
                .param("n", name)
                .param("p", propertiesJson == null ? "{}" : propertiesJson)
                .update();
    }
}

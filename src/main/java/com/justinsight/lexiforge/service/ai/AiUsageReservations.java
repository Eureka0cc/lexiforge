package com.justinsight.lexiforge.service.ai;

import com.justinsight.lexiforge.common.ApiException;
import com.justinsight.lexiforge.common.id.SnowflakeIdGenerator;
import com.justinsight.lexiforge.service.EntitlementService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.Map;

/** Durable reservations, fenced completion, and retryable crash compensation. */
@Service
public class AiUsageReservations {
    private final JdbcClient jdbc;
    private final SnowflakeIdGenerator ids;
    private final EntitlementService entitlements;
    private final long leaseSeconds;
    public AiUsageReservations(JdbcClient jdbc,SnowflakeIdGenerator ids,EntitlementService entitlements,
                               @Value("${app.ai.execution.timeout-seconds:90}") long timeoutSeconds) {
        this.jdbc=jdbc; this.ids=ids; this.entitlements=entitlements;
        leaseSeconds=Math.max(120,timeoutSeconds+60);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public long reserve(long userId,EntitlementService.UsageType type,String operationKey) {
        long id=ids.nextId();
        LocalDate date=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        var inserted=jdbc.sql("""
                INSERT INTO ai_usage_reservation(id,user_id,usage_type,operation_key,usage_date,status,expires_at)
                VALUES(:id,:uid,:type,:key,:date,'RESERVED',clock_timestamp()+(:seconds * interval '1 second'))
                ON CONFLICT(user_id,operation_key) WHERE status='RESERVED' DO NOTHING RETURNING id
                """).param("id",id).param("uid",userId).param("type",type.name()).param("key",operationKey)
                .param("date",date).param("seconds",leaseSeconds).query(Long.class).optional();
        if (inserted.isEmpty()) throw new ApiException(HttpStatus.CONFLICT,"AI_OPERATION_IN_PROGRESS","同一内容正在处理中，请稍后重试");
        entitlements.consume(userId,type,date);
        // Admission can wait behind another quota transaction; start the lease after that wait.
        jdbc.sql("UPDATE ai_usage_reservation SET expires_at=clock_timestamp()+(:seconds * interval '1 second') WHERE id=:id")
                .param("seconds",leaseSeconds).param("id",id).update();
        return id;
    }
    /** Must join the transaction that saves the result. Never confirm before durable publication. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void commit(long id,long resultId) {
        int changed=jdbc.sql("""
                UPDATE ai_usage_reservation SET status='COMMITTED',result_id=:result,settled_at=clock_timestamp()
                WHERE id=:id AND status='RESERVED' AND expires_at>clock_timestamp()
                """).param("id",id).param("result",resultId).update();
        if (changed!=1) throw new ApiException(HttpStatus.CONFLICT,"AI_RESERVATION_EXPIRED","请求已失效，当前结果未保存，请重试");
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void release(long id) {
        var rows=jdbc.sql("SELECT * FROM ai_usage_reservation WHERE id=:id AND status='RESERVED' FOR UPDATE")
                .param("id",id).query().listOfRows();
        if (!rows.isEmpty()) releaseLocked(rows.getFirst());
    }
    @Scheduled(fixedDelayString="${app.ai.reservation-cleanup-ms:30000}")
    @Transactional
    public void recoverExpired() {
        var rows=jdbc.sql("""
                SELECT * FROM ai_usage_reservation WHERE status='RESERVED' AND expires_at<=clock_timestamp()
                ORDER BY expires_at LIMIT 100 FOR UPDATE SKIP LOCKED
                """).query().listOfRows();
        for (var row:rows) releaseLocked(row);
    }
    private void releaseLocked(Map<String,Object> row) {
        Object storedDate=row.get("usage_date");
        LocalDate date=storedDate instanceof LocalDate local?local:((java.sql.Date)storedDate).toLocalDate();
        entitlements.release(((Number)row.get("user_id")).longValue(),
                EntitlementService.UsageType.valueOf((String)row.get("usage_type")),date);
        jdbc.sql("UPDATE ai_usage_reservation SET status='RELEASED',settled_at=clock_timestamp() WHERE id=:id AND status='RESERVED'")
                .param("id",row.get("id")).update();
    }
}

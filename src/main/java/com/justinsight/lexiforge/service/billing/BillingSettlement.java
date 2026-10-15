package com.justinsight.lexiforge.service.billing;

import com.justinsight.lexiforge.common.ApiException;
import com.justinsight.lexiforge.model.dto.BillingDto;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import static com.justinsight.lexiforge.service.billing.LocalSandboxGateway.*;

/** Settlement is a separate transaction from the simulated provider mutation. */
@Service
public class BillingSettlement {
    private final JdbcClient jdbc;
    private final LocalSandboxGateway gateway;
    public BillingSettlement(JdbcClient jdbc,LocalSandboxGateway gateway) { this.jdbc=jdbc; this.gateway=gateway; }
    @Transactional
    public void accept(BillingDto.Event event,String signature) {
        gateway.requireEnabled(); gateway.verify(event,signature);
        long id=Long.parseLong(event.orderId());
        long uid=jdbc.sql("SELECT user_id FROM billing_order WHERE id=:id").param("id",id).query(Long.class).optional().orElseThrow(LocalSandboxGateway::missing);
        // All payments/renewals/refunds for one account serialize before order locks.
        jdbc.sql("SELECT id FROM app_user WHERE id=:uid FOR UPDATE").param("uid",uid).query(Long.class).single();
        var order=jdbc.sql("SELECT * FROM billing_order WHERE id=:id FOR UPDATE").param("id",id).query().singleRow();
        var canonical=gateway.snapshot(id).orElseThrow(()->conflict("渠道尚无此交易"));
        if (canonical.amountMinor()!=event.amountMinor() || !canonical.tradeNo().equals(event.tradeNo())
                || !canonical.channel().equals(event.channel()) || !canonical.currency().equals(event.currency())
                || event.amountMinor()!=((Number)order.get("amount_minor")).longValue()
                || !event.channel().equals(order.get("channel")) || !event.currency().equals(order.get("currency"))
                || (order.get("trade_no")!=null && !order.get("trade_no").equals(event.tradeNo()))
                || event.version()>canonical.version() || (event.version()==1 && !event.status().equals("PAID"))
                || (event.version()==2 && !event.status().equals("REFUNDED")) || event.version()>2)
            throw new ApiException(HttpStatus.BAD_REQUEST,"PAYMENT_EVENT_MISMATCH","支付通知与订单或渠道查询不一致");
        jdbc.sql("INSERT INTO billing_event(order_id,event_version,event_status) VALUES(:id,:version,:status) ON CONFLICT DO NOTHING")
                .param("id",id).param("version",event.version()).param("status",event.status()).update();
        if (((Number)order.get("last_event_version")).intValue()>=canonical.version()) return;
        if (order.get("status").equals("CANCELLED")) throw conflict("关闭订单不能结算");
        jdbc.sql("""
                INSERT INTO billing_account(user_id,base_starts_at,base_expires_at)
                SELECT user_id,CASE WHEN status='ACTIVE' AND expires_at>now() THEN starts_at END,
                       CASE WHEN status='ACTIVE' AND expires_at>now() THEN expires_at END
                FROM subscription WHERE user_id=:uid ON CONFLICT DO NOTHING
                """).param("uid",uid).update();
        jdbc.sql("""
                UPDATE billing_order SET status=:status,trade_no=:trade,last_event_version=:version,
                    paid_at=(SELECT paid_at FROM billing_sandbox_trade WHERE order_id=:id) WHERE id=:id
                """).param("status",canonical.status()).param("trade",canonical.tradeNo()).param("version",canonical.version()).param("id",id).update();
        rebuild(uid);
    }
    private void rebuild(long uid) {
        var base=jdbc.sql("SELECT * FROM billing_account WHERE user_id=:uid").param("uid",uid).query().singleRow();
        Instant cursor=base.get("base_expires_at")==null?null:instant(base.get("base_expires_at"));
        Instant starts=base.get("base_starts_at")==null?null:instant(base.get("base_starts_at"));
        jdbc.sql("UPDATE billing_grant SET revoked=true WHERE user_id=:uid").param("uid",uid).update();
        var orders=jdbc.sql("SELECT id,paid_at,duration_days FROM billing_order WHERE user_id=:uid AND status='PAID' ORDER BY paid_at,id")
                .param("uid",uid).query().listOfRows();
        for (var order:orders) {
            Instant paid=instant(order.get("paid_at"));
            Instant begin=cursor==null||paid.isAfter(cursor)?paid:cursor;
            cursor=begin.plus(((Number)order.get("duration_days")).intValue(),ChronoUnit.DAYS);
            if (starts==null) starts=begin;
            jdbc.sql("""
                    INSERT INTO billing_grant(order_id,user_id,starts_at,expires_at,revoked) VALUES(:id,:uid,:start,:end,false)
                    ON CONFLICT(order_id) DO UPDATE SET starts_at=excluded.starts_at,expires_at=excluded.expires_at,revoked=false
                    """).param("id",order.get("id")).param("uid",uid).param("start",Timestamp.from(begin)).param("end",Timestamp.from(cursor)).update();
        }
        String status=cursor==null?"FREE":cursor.isAfter(Instant.now())?"ACTIVE":"EXPIRED";
        jdbc.sql("UPDATE subscription SET plan_code=:plan,status=:status,starts_at=:start,expires_at=:end,updated_at=now() WHERE user_id=:uid")
                .param("plan",cursor==null?"FREE":"PRO").param("status",status)
                .param("start",starts==null?null:Timestamp.from(starts)).param("end",cursor==null?null:Timestamp.from(cursor)).param("uid",uid).update();
    }
}

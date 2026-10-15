package com.justinsight.lexiforge.service.billing;

import com.justinsight.lexiforge.common.id.SnowflakeIdGenerator;
import com.justinsight.lexiforge.model.dto.BillingDto;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import static com.justinsight.lexiforge.service.billing.LocalSandboxGateway.*;

@Service
public class BillingService {
    private final JdbcClient jdbc;
    private final SnowflakeIdGenerator ids;
    private final LocalSandboxGateway gateway;
    private final BillingSettlement settlement;
    public BillingService(JdbcClient jdbc,SnowflakeIdGenerator ids,LocalSandboxGateway gateway,BillingSettlement settlement) {
        this.jdbc=jdbc; this.ids=ids; this.gateway=gateway; this.settlement=settlement;
    }
    public BillingDto.Config config() { return new BillingDto.Config(gateway.enabled(),gateway.enabled()?"LOCAL_SANDBOX":"DISABLED",false); }
    @Transactional
    public BillingDto.Order create(long uid,BillingDto.Create request) {
        gateway.requireEnabled();
        jdbc.sql("SELECT id FROM app_user WHERE id=:uid FOR UPDATE").param("uid",uid).query(Long.class).single();
        var previous=jdbc.sql("SELECT id,cycle,channel FROM billing_order WHERE user_id=:uid AND request_id=:request")
                .param("uid",uid).param("request",request.requestId()).query().listOfRows();
        if (!previous.isEmpty()) {
            var prior=previous.getFirst();
            if (!prior.get("cycle").equals(request.cycle()) || !prior.get("channel").equals(request.channel())) throw conflict("请求编号已用于其他订单参数");
            return order(uid,((Number)prior.get("id")).longValue());
        }
        var plan=jdbc.sql("SELECT name,monthly_price,annual_price FROM plan_catalog WHERE code='PRO'").query().singleRow();
        boolean annual=request.cycle().equals("ANNUAL");
        long amount=((java.math.BigDecimal)plan.get(annual?"annual_price":"monthly_price")).movePointRight(2).longValueExact();
        long id=ids.nextId();
        jdbc.sql("""
                INSERT INTO billing_order(id,user_id,request_id,channel,cycle,plan_code,plan_name,amount_minor,duration_days)
                VALUES(:id,:uid,:request,:channel,:cycle,'PRO',:name,:amount,:days)
                """).param("id",id).param("uid",uid).param("request",request.requestId()).param("channel",request.channel())
                .param("cycle",request.cycle()).param("name",plan.get("name")).param("amount",amount).param("days",annual?365:30).update();
        return order(uid,id);
    }
    public List<BillingDto.Order> list(long uid) {
        return jdbc.sql("SELECT * FROM billing_order WHERE user_id=:uid ORDER BY created_at DESC,id DESC LIMIT 100")
                .param("uid",uid).query(this::view).list();
    }
    public BillingDto.Order order(long uid,long id) {
        return jdbc.sql("SELECT * FROM billing_order WHERE user_id=:uid AND id=:id").param("uid",uid).param("id",id)
                .query(this::view).optional().orElseThrow(LocalSandboxGateway::missing);
    }
    public BillingDto.Order query(long uid,long id) { order(uid,id); reconcile(id); return order(uid,id); }
    public BillingDto.Order pay(long uid,long id,boolean notify) {
        var event=gateway.pay(uid,id);
        if (notify) settlement.accept(event,gateway.sign(event));
        return order(uid,id);
    }
    public BillingDto.Order refund(long uid,long id,boolean notify) {
        var event=gateway.refund(uid,id);
        if (notify) settlement.accept(event,gateway.sign(event));
        return order(uid,id);
    }
    @Transactional
    public BillingDto.Order cancel(long uid,long id) {
        gateway.requireEnabled();
        var rows=jdbc.sql("SELECT status FROM billing_order WHERE user_id=:uid AND id=:id FOR UPDATE").param("uid",uid).param("id",id).query(String.class).optional();
        if (rows.isEmpty()) throw missing();
        if (rows.get().equals("CANCELLED")) return order(uid,id);
        if (!rows.get().equals("PENDING") || gateway.snapshot(id).isPresent()) throw conflict("已支付的订单不能取消，请申请退款");
        jdbc.sql("UPDATE billing_order SET status='CANCELLED' WHERE id=:id").param("id",id).update();
        return order(uid,id);
    }
    public void reconcile(long id) {
        gateway.requireEnabled(); gateway.snapshot(id).ifPresent(event->settlement.accept(event,gateway.sign(event)));
    }
    @Scheduled(fixedDelayString="${app.billing.reconcile-ms:30000}")
    public void reconcilePending() {
        if (!gateway.enabled()) return;
        var ids=jdbc.sql("SELECT o.id FROM billing_order o JOIN billing_sandbox_trade t ON t.order_id=o.id WHERE t.version>o.last_event_version ORDER BY o.created_at LIMIT 100")
                .query(Long.class).list();
        for (long id:ids) {
            try { reconcile(id); }
            catch (RuntimeException exception) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Sandbox settlement deferred for order {}",id); }
        }
    }
    private BillingDto.Order view(java.sql.ResultSet row,int n) throws java.sql.SQLException {
        return new BillingDto.Order(Long.toString(row.getLong("id")),row.getString("channel"),row.getString("cycle"),row.getString("plan_name"),
                row.getLong("amount_minor"),row.getString("currency"),row.getString("status"),row.getString("trade_no"),
                row.getTimestamp("created_at").toInstant().toString(),row.getTimestamp("expires_at").toInstant().toString(),
                row.getTimestamp("paid_at")==null?null:row.getTimestamp("paid_at").toInstant().toString());
    }
}

package com.justinsight.lexiforge.service.billing;

import com.justinsight.lexiforge.common.ApiException;
import com.justinsight.lexiforge.model.dto.BillingDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Durable local provider simulator. No network calls and no real funds. */
@Service
public class LocalSandboxGateway {
    private final JdbcClient jdbc;
    private final String secret;
    private final boolean enabled;
    public LocalSandboxGateway(JdbcClient jdbc, Environment environment,
            @Value("${app.billing.mode:disabled}") String mode,
            @Value("${app.billing.secret:}") String secret) {
        this.jdbc=jdbc; this.secret=secret;
        if (!Set.of("disabled","local-sandbox").contains(mode)) throw new IllegalArgumentException("支付仅支持 disabled/local-sandbox");
        enabled=mode.equals("local-sandbox");
        if (enabled && (!environment.acceptsProfiles(Profiles.of("dev","test")) || secret.length()<32))
            throw new IllegalStateException("本地支付模拟仅允许 dev/test，且需要至少32字符独立签名密钥");
    }
    public boolean enabled() { return enabled; }
    public void requireEnabled() {
        if (!enabled) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"BILLING_DISABLED","本地 Sandbox 支付未启用");
    }
    @Transactional
    public BillingDto.Event pay(long userId, long orderId) {
        requireEnabled();
        var order=ownedForUpdate(userId,orderId);
        var prior=snapshot(orderId);
        if (prior.isPresent()) return prior.get();
        if (!order.get("status").equals("PENDING") || instant(order.get("expires_at")).isBefore(Instant.now()))
            throw conflict("订单已关闭或过期");
        jdbc.sql("""
                INSERT INTO billing_sandbox_trade(order_id,trade_no,amount_minor,currency,channel,status,version)
                VALUES(:id,:trade,:amount,:currency,:channel,'PAID',1)
                """).param("id",orderId).param("trade","SIM-"+orderId).param("amount",order.get("amount_minor"))
                .param("currency",order.get("currency")).param("channel",order.get("channel")).update();
        return snapshot(orderId).orElseThrow();
    }
    @Transactional
    public BillingDto.Event refund(long userId,long orderId) {
        requireEnabled(); ownedForUpdate(userId,orderId);
        if (snapshot(orderId).isEmpty()) throw conflict("订单尚未支付");
        jdbc.sql("UPDATE billing_sandbox_trade SET status='REFUNDED',version=2,updated_at=now() WHERE order_id=:id AND status='PAID'")
                .param("id",orderId).update();
        return snapshot(orderId).orElseThrow();
    }
    public Optional<BillingDto.Event> snapshot(long orderId) {
        return jdbc.sql("SELECT * FROM billing_sandbox_trade WHERE order_id=:id").param("id",orderId)
                .query((r,n)->new BillingDto.Event(Long.toString(r.getLong("order_id")),r.getString("trade_no"),r.getLong("amount_minor"),
                        r.getString("currency"),r.getString("channel"),r.getString("status"),r.getInt("version"))).optional();
    }
    public String sign(BillingDto.Event event) {
        requireEnabled();
        try {
            var mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            // Every field is signed; the JSON representation preserves unambiguous string boundaries.
            String payload=new tools.jackson.databind.ObjectMapper().writeValueAsString(event);
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException("Sandbox 签名失败"); }
    }
    public void verify(BillingDto.Event event,String signature) {
        if (signature==null || !MessageDigest.isEqual(sign(event).getBytes(StandardCharsets.US_ASCII),signature.getBytes(StandardCharsets.US_ASCII)))
            throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_PAYMENT_SIGNATURE","支付通知签名无效");
    }
    private Map<String,Object> ownedForUpdate(long userId,long id) {
        return jdbc.sql("SELECT * FROM billing_order WHERE id=:id AND user_id=:uid FOR UPDATE").param("id",id).param("uid",userId)
                .query().listOfRows().stream().findFirst().orElseThrow(LocalSandboxGateway::missing);
    }
    public static Instant instant(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof java.time.OffsetDateTime offset) return offset.toInstant();
        throw new IllegalArgumentException("时间字段类型不支持");
    }
    public static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"ORDER_NOT_FOUND","订单不存在"); }
    public static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT,"ORDER_STATE_CONFLICT",message); }
}

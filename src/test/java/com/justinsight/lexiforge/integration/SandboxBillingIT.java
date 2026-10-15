package com.justinsight.lexiforge.integration;

import com.justinsight.lexiforge.model.dto.BillingDto;
import com.justinsight.lexiforge.service.*;
import com.justinsight.lexiforge.service.billing.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.*;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"server.address=127.0.0.1","spring.ai.model.chat=none","app.ai.enabled=false",
        "app.jwt.secret=test-only-secret-never-use-in-production-0123456789",
        "app.billing.mode=local-sandbox","app.billing.secret=fixture-only-billing-secret-0123456789012345","app.billing.reconcile-ms=3600000"})
@EnabledIfEnvironmentVariable(named="LEXIFORGE_TEST_DB_URL",matches=".+")
class SandboxBillingIT {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { ScenePersistenceIT.database(registry); }
    @Autowired AuthService auth;
    @Autowired BillingService billing;
    @Autowired LocalSandboxGateway gateway;
    @Autowired BillingSettlement settlement;
    @Autowired SubscriptionService subscriptions;
    @Autowired EntitlementService entitlements;
    @Autowired JdbcClient jdbc;
    @Autowired VocabularyService vocabulary;
    @Autowired org.springframework.core.env.Environment environment;
    private long uid;
    @BeforeEach void setup() { uid=Long.parseLong(auth.register("billing-"+UUID.randomUUID()+"@example.test","Acceptance123!","支付验收").user().id()); }
    @AfterEach void cleanup() { jdbc.sql("DELETE FROM app_user WHERE id=:id").param("id",uid).update(); }
    private BillingDto.Order create(String cycle) { return billing.create(uid,new BillingDto.Create(UUID.randomUUID().toString(),cycle,"ALIPAY_SIMULATOR")); }

    @Test void creationIsIdempotentAndSnapshotsServerPrice() {
        var request=new BillingDto.Create(UUID.randomUUID().toString(),"ANNUAL","WECHAT_SIMULATOR");
        var order=billing.create(uid,request);
        assertThat(billing.create(uid,request)).isEqualTo(order);
        assertThat(order.amountMinor()).isEqualTo(jdbc.sql("SELECT annual_price*100 FROM plan_catalog WHERE code='PRO'").query(Long.class).single());
        assertThatThrownBy(()->billing.create(uid,new BillingDto.Create(request.requestId(),"MONTH",request.channel()))).hasMessageContaining("其他订单参数");
        assertThat(billing.config().officialChannelConnected()).isFalse();
        jdbc.sql("UPDATE billing_order SET plan_name='历史快照' WHERE id=:id").param("id",Long.parseLong(order.id())).update();
        assertThat(billing.order(uid,Long.parseLong(order.id())).planName()).isEqualTo("历史快照");
    }
    @Test void lostNotificationIsRecoveredFromPersistentProviderStateAndRefundIsTerminal() {
        var order=create("MONTH"); long id=Long.parseLong(order.id());
        assertThat(billing.pay(uid,id,false).status()).isEqualTo("PENDING");
        assertThat(entitlements.plan(uid).code()).isEqualTo("FREE");
        billing.reconcilePending();
        assertThat(billing.order(uid,id).status()).isEqualTo("PAID");
        assertThat(entitlements.plan(uid).code()).isEqualTo("PRO");
        assertThat(billing.refund(uid,id,false).status()).isEqualTo("PAID");
        assertThat(billing.query(uid,id).status()).isEqualTo("REFUNDED");
        assertThat(entitlements.plan(uid).code()).isEqualTo("FREE");
        assertThat(billing.pay(uid,id,true).status()).isEqualTo("REFUNDED");
        assertThat(count("billing_grant","revoked=false")).isZero();
    }
    @Test void refundBeforePaymentNotificationAndLateOldCallbackNeverReactivate() {
        var order=create("MONTH"); long id=Long.parseLong(order.id());
        var paid=gateway.pay(uid,id); var refunded=gateway.refund(uid,id);
        settlement.accept(refunded,gateway.sign(refunded));
        settlement.accept(paid,gateway.sign(paid));
        settlement.accept(refunded,gateway.sign(refunded));
        assertThat(billing.order(uid,id).status()).isEqualTo("REFUNDED");
        assertThat(entitlements.plan(uid).code()).isEqualTo("FREE");
        assertThat(jdbc.sql("SELECT count(*) FROM billing_event WHERE order_id=:id").param("id",id).query(Long.class).single()).isEqualTo(2);
    }
    @Test void concurrentDuplicateNotificationsGrantOnlyOnce() throws Exception {
        var order=create("MONTH"); long id=Long.parseLong(order.id()); var event=gateway.pay(uid,id);
        parallel(()->settlement.accept(event,gateway.sign(event)),()->settlement.accept(event,gateway.sign(event)));
        assertThat(count("billing_grant","revoked=false")).isEqualTo(1);
        assertThat(Duration.between(Instant.parse(billing.order(uid,id).paidAt()),Instant.parse(subscriptions.current(uid).expiresAt()))).isEqualTo(Duration.ofDays(30));
    }
    @Test void concurrentRenewalsAndOneRefundPreserveOtherOrderAndLearningData() throws Exception {
        var learned=vocabulary.create(uid,new com.justinsight.lexiforge.model.dto.VocabularyDto.Request("address","v.","","解决","","","","MANUAL",null));
        var first=create("MONTH"); var second=create("ANNUAL");
        parallel(()->billing.pay(uid,Long.parseLong(first.id()),true),()->billing.pay(uid,Long.parseLong(second.id()),true));
        assertThat(count("billing_grant","revoked=false")).isEqualTo(2);
        var start=Instant.parse(subscriptions.current(uid).startsAt());
        var before=Instant.parse(subscriptions.current(uid).expiresAt());
        assertThat(Duration.between(start,before)).isEqualTo(Duration.ofDays(395));
        billing.refund(uid,Long.parseLong(first.id()),true);
        var after=Instant.parse(subscriptions.current(uid).expiresAt());
        assertThat(Duration.between(after,before).getSeconds()).isBetween(30L*86400-2,30L*86400+2);
        assertThat(billing.order(uid,Long.parseLong(second.id())).status()).isEqualTo("PAID");
        assertThat(entitlements.plan(uid).code()).isEqualTo("PRO");
        assertThat(count("billing_grant","revoked=false")).isEqualTo(1);
        assertThat(vocabulary.list(uid)).extracting(com.justinsight.lexiforge.model.dto.VocabularyDto.View::id).contains(learned.id());
    }
    @Test void refundPreservesAnExistingSubscriptionBaseline() {
        var baseline=subscriptions.activate(uid,5);
        long id=Long.parseLong(create("MONTH").id());
        billing.pay(uid,id,true); billing.refund(uid,id,true);
        assertThat(subscriptions.current(uid).expiresAt()).isEqualTo(baseline.expiresAt());
        assertThat(entitlements.plan(uid).code()).isEqualTo("PRO");
    }
    @Test void signedHttpCallbackWorksWithoutLoginButOrderQueriesRequireAuthentication() throws Exception {
        long id=Long.parseLong(create("MONTH").id()); var event=gateway.pay(uid,id);
        int port=environment.getRequiredProperty("local.server.port",Integer.class);
        var client=java.net.http.HttpClient.newHttpClient();
        var json=new tools.jackson.databind.ObjectMapper();
        String url="http://127.0.0.1:"+port+"/api/billing/sandbox-notify/v1";
        for (boolean valid:java.util.List.of(false,true)) {
            var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).header("Content-Type","application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json.writeValueAsString(new BillingDto.Notify(event,valid?gateway.sign(event):"invalid")))).build();
            assertThat(client.send(request,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(valid?200:400);
        }
        var unauthenticated=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+"/api/billing/orders/"+id+"/v1")).GET().build();
        assertThat(client.send(unauthenticated,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        assertThat(billing.order(uid,id).status()).isEqualTo("PAID");
    }
    @Test void invalidSignaturesAmountsAndOtherUserAreRejected() {
        var order=create("MONTH"); long id=Long.parseLong(order.id()); var event=gateway.pay(uid,id);
        assertThatThrownBy(()->settlement.accept(event,"invalid")).hasMessageContaining("签名无效");
        var altered=new BillingDto.Event(event.orderId(),event.tradeNo(),event.amountMinor()+1,event.currency(),event.channel(),event.status(),event.version());
        assertThatThrownBy(()->settlement.accept(altered,gateway.sign(altered))).hasMessageContaining("不一致");
        long outsider=Long.parseLong(auth.register("billing-other-"+UUID.randomUUID()+"@example.test","Acceptance123!","其他用户").user().id());
        try {
            assertThat(billing.list(outsider)).isEmpty();
            assertThatThrownBy(()->billing.query(outsider,id)).hasMessageContaining("不存在");
            assertThatThrownBy(()->billing.pay(outsider,id,true)).hasMessageContaining("不存在");
            assertThatThrownBy(()->billing.refund(outsider,id,true)).hasMessageContaining("不存在");
            assertThatThrownBy(()->billing.cancel(outsider,id)).hasMessageContaining("不存在");
        } finally { jdbc.sql("DELETE FROM app_user WHERE id=:id").param("id",outsider).update(); }
        assertThat(count("billing_grant","revoked=false")).isZero();
    }
    @Test void cancelledAndExpiredOrdersCannotBePaidAndPaidOrderCannotBeCancelled() {
        long cancelled=Long.parseLong(create("MONTH").id());
        assertThat(billing.cancel(uid,cancelled).status()).isEqualTo("CANCELLED");
        assertThat(billing.cancel(uid,cancelled).status()).isEqualTo("CANCELLED");
        assertThatThrownBy(()->billing.pay(uid,cancelled,true)).hasMessageContaining("关闭或过期");
        long expired=Long.parseLong(create("MONTH").id());
        jdbc.sql("UPDATE billing_order SET expires_at=now()-interval '1 second' WHERE id=:id").param("id",expired).update();
        assertThatThrownBy(()->billing.pay(uid,expired,true)).hasMessageContaining("关闭或过期");
        long paid=Long.parseLong(create("MONTH").id()); gateway.pay(uid,paid);
        assertThatThrownBy(()->billing.cancel(uid,paid)).hasMessageContaining("不能取消");
    }
    private long count(String table,String condition) {
        return jdbc.sql("SELECT count(*) FROM "+table+" WHERE user_id=:uid AND "+condition).param("uid",uid).query(Long.class).single();
    }
    private void parallel(Runnable left,Runnable right) throws Exception {
        var executor=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
        try {
            var a=executor.submit(()->{ start.await(); left.run(); return null; });
            var b=executor.submit(()->{ start.await(); right.run(); return null; }); start.countDown();
            a.get(15,TimeUnit.SECONDS); b.get(15,TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
    }
}

package com.justinsight.lexiforge.integration;

import com.justinsight.lexiforge.common.ApiException;
import com.justinsight.lexiforge.model.dto.TranslationDto;
import com.justinsight.lexiforge.model.dto.ai.LookupDto;
import com.justinsight.lexiforge.service.*;
import com.justinsight.lexiforge.service.ai.*;
import com.justinsight.lexiforge.service.ai.provider.AiGateway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.ai.model.chat=none","app.ai.enabled=false",
        "app.jwt.secret=test-only-secret-never-use-in-production-0123456789",
        "app.ai.reservation-cleanup-ms=3600000"})
@EnabledIfEnvironmentVariable(named="LEXIFORGE_TEST_DB_URL",matches=".+")
class AiUsageReservationIT {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { ScenePersistenceIT.database(registry); }
    @Autowired AuthService auth;
    @Autowired AiUsageReservations reservations;
    @Autowired EntitlementService entitlements;
    @Autowired TranslationService translations;
    @Autowired LookupService lookups;
    @Autowired AiRequestService requests;
    @Autowired JdbcClient jdbc;
    @MockitoBean AiGateway ai;
    private long uid;
    @BeforeEach void setup() {
        uid=Long.parseLong(auth.register("quota-"+UUID.randomUUID()+"@example.test","Acceptance123!","额度验收").user().id());
        when(ai.available()).thenReturn(true);
        when(ai.translateStream(any(),any())).thenReturn(new AiGateway.TranslationResult("EN","解决问题",List.of(),List.of()));
    }
    @AfterEach void cleanup() { jdbc.sql("DELETE FROM app_user WHERE id=:id").param("id",uid).update(); }
    private TranslationDto.Request translation() { return new TranslationDto.Request("address the issue","EN","ZH",null,null,false); }
    private long reserve(String key) { return reservations.reserve(uid,EntitlementService.UsageType.TRANSLATION,key); }

    @Test void concurrentSameOperationHasOneReservationAndOneCharge() throws Exception {
        var results=parallel(2,index->reserve("same-operation"));
        assertThat(results.stream().filter(Long.class::isInstance).count()).isEqualTo(1);
        assertThat(results.stream().filter(ApiException.class::isInstance).count()).isEqualTo(1);
        assertThat(entitlements.usage(uid).translationsUsed()).isEqualTo(1);
        long id=(Long)results.stream().filter(Long.class::isInstance).findFirst().orElseThrow();
        parallel(2,index->{ reservations.release(id); return null; });
        assertThat(entitlements.usage(uid).translationsUsed()).isZero();
        assertThat(count("ai_usage_reservation","status='RELEASED'")).isEqualTo(1);
    }
    @Test void twentyConcurrentReservationsRespectTheRealDailyLimit() throws Exception {
        int limit=entitlements.plan(uid).translationLimit();
        int capacity=Math.min(20,limit);
        jdbc.sql("INSERT INTO daily_usage(id,user_id,usage_date,translation_count) VALUES(:id,:uid,:day,:used)")
                .param("id",uid).param("uid",uid).param("day",LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))).param("used",limit-capacity).update();
        var results=parallel(20,index->reserve("parallel-"+index));
        assertThat(results.stream().filter(Long.class::isInstance).count()).isEqualTo(capacity);
        assertThat(results.stream().filter(ApiException.class::isInstance).count()).isEqualTo(20-capacity);
        assertThat(entitlements.usage(uid).translationsUsed()).isEqualTo(limit);
        assertThatThrownBy(()->reserve("over-limit")).isInstanceOf(ApiException.class).hasMessageContaining("额度已用完");
        assertThat(count("ai_usage_reservation","status='RESERVED'")).isEqualTo(capacity);
        for (var id:results) if(id instanceof Long reservation) reservations.release(reservation);
        assertThat(entitlements.usage(uid).translationsUsed()).isEqualTo(limit-capacity);
    }
    @Test void crashCompensationIsRepeatableAndCreditsTheOriginalDay() {
        long id=reserve("yesterday");
        LocalDate yesterday=LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).minusDays(1);
        jdbc.sql("UPDATE daily_usage SET usage_date=:day WHERE user_id=:uid").param("day",yesterday).param("uid",uid).update();
        jdbc.sql("UPDATE ai_usage_reservation SET usage_date=:day,expires_at=now()-interval '1 second' WHERE id=:id")
                .param("day",yesterday).param("id",id).update();
        long today=reserve("today");
        reservations.recoverExpired(); reservations.recoverExpired(); reservations.release(id);
        assertThat(jdbc.sql("SELECT translation_count FROM daily_usage WHERE user_id=:uid AND usage_date=:day")
                .param("uid",uid).param("day",yesterday).query(Integer.class).single()).isZero();
        assertThat(entitlements.usage(uid).translationsUsed()).isEqualTo(1);
        reservations.release(today);
    }
    @Test void successfulPublicationCommitsReservationAndCannotBeRefundedByCleanup() {
        var result=translations.translate(uid,translation());
        long id=jdbc.sql("SELECT id FROM ai_usage_reservation WHERE user_id=:uid").param("uid",uid).query(Long.class).single();
        assertThat(count("ai_usage_reservation","status='COMMITTED' AND result_id="+result.id())).isEqualTo(1);
        reservations.release(id); reservations.recoverExpired();
        assertThat(entitlements.usage(uid).translationsUsed()).isEqualTo(1);
        assertThat(translations.translate(uid,translation()).id()).isEqualTo(result.id());
        assertThat(count("ai_usage_reservation","status='COMMITTED'")).isEqualTo(1);
        verify(ai,times(1)).translateStream(any(),any());
    }
    @Test void expiredRecoveredReservationFencesLateModelResultAndRollsBackHistory() {
        when(ai.translateStream(any(),any())).thenAnswer(invocation->{
            jdbc.sql("UPDATE ai_usage_reservation SET expires_at=now()-interval '1 second' WHERE user_id=:uid AND status='RESERVED'")
                    .param("uid",uid).update();
            reservations.recoverExpired();
            return new AiGateway.TranslationResult("EN","迟到结果",List.of(),List.of());
        });
        assertThatThrownBy(()->translations.translate(uid,translation())).hasMessageContaining("请求已失效");
        assertThat(entitlements.usage(uid).translationsUsed()).isZero();
        assertThat(count("translation","1=1")).isZero();
        assertThat(count("ai_usage_reservation","status='RELEASED'")).isEqualTo(1);
    }
    @Test void lookupPersistenceFailureRefundsAndDetailsRetryCommitsExactlyOnce() {
        var result=translations.translate(uid,translation()); long tid=Long.parseLong(result.id());
        when(ai.lookup(any())).thenReturn(new AiGateway.LookupResult("解决","处理问题","解决","address","解决"));
        var lookup=lookups.lookup(uid,tid,new LookupDto.Request(UUID.randomUUID().toString(),"SOURCE",0,7));
        long lid=Long.parseLong(lookup.lookupId());
        when(ai.lookupDetails(any())).thenReturn(new AiGateway.LookupDetailsResult("","x".repeat(100),"example","例句"));
        assertThatThrownBy(()->lookups.details(uid,lid,UUID.randomUUID().toString())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(entitlements.usage(uid).lookupsUsed()).isEqualTo(1);
        assertThat(lookups.owned(uid,lid).detailsLoaded).isFalse();
        assertThat(count("ai_usage_reservation","status='RELEASED'")).isEqualTo(1);
        when(ai.lookupDetails(any())).thenReturn(new AiGateway.LookupDetailsResult("","v.","address the issue","解决问题"));
        lookups.details(uid,lid,UUID.randomUUID().toString());
        lookups.details(uid,lid,UUID.randomUUID().toString());
        assertThat(entitlements.usage(uid).lookupsUsed()).isEqualTo(2);
        assertThat(lookups.owned(uid,lid).detailsLoaded).isTrue();
    }
    @Test void concurrentRequestIdReturnsConflictWithoutAbortingTheWinningTransaction() throws Exception {
        var results=parallel(2,index->requests.begin(uid,"same-request","TRANSLATION","same input"));
        assertThat(results.stream().filter(AiRequestService.BeginResult.class::isInstance).count()).isEqualTo(1);
        assertThat(results.stream().filter(ApiException.class::isInstance).count()).isEqualTo(1);
        var begin=(AiRequestService.BeginResult)results.stream().filter(AiRequestService.BeginResult.class::isInstance).findFirst().orElseThrow();
        requests.succeed(begin.id(),123L,begin.startedAt());
        requests.fail(begin.id(),"LATE_FAILURE",begin.startedAt());
        assertThat(requests.begin(uid,"same-request","TRANSLATION","same input").replay()).isTrue();
        assertThat(count("ai_request","status='SUCCEEDED'")).isEqualTo(1);
    }
    private long count(String table,String condition) {
        return jdbc.sql("SELECT count(*) FROM "+table+" WHERE user_id=:uid AND "+condition).param("uid",uid).query(Long.class).single();
    }
    private List<Object> parallel(int count,java.util.function.IntFunction<Object> operation) throws Exception {
        var executor=Executors.newFixedThreadPool(count); var start=new CountDownLatch(1);
        try {
            var futures=new ArrayList<Future<Object>>();
            for(int i=0;i<count;i++) { int index=i; futures.add(executor.submit(()->{ start.await(); try { return operation.apply(index); } catch(RuntimeException error) { return error; } })); }
            start.countDown(); var values=new ArrayList<Object>();
            for(var future:futures) values.add(future.get(20,TimeUnit.SECONDS));
            return values;
        } finally { executor.shutdownNow(); }
    }
}

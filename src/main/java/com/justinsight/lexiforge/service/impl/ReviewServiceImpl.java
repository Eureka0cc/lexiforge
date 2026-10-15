package com.justinsight.lexiforge.service.impl;

import com.justinsight.lexiforge.service.ReviewService;
import com.justinsight.lexiforge.service.EntitlementService;
import com.justinsight.lexiforge.service.ai.provider.AiGateway;
import com.justinsight.lexiforge.model.dto.ReviewDto;
import com.justinsight.lexiforge.common.id.SnowflakeIdGenerator;
import com.justinsight.lexiforge.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

@Service
public class ReviewServiceImpl implements ReviewService {
    private final JdbcClient jdbc;
    private final SnowflakeIdGenerator ids;
    private final AiGateway ai;
    private final EntitlementService entitlements;
    private final com.justinsight.lexiforge.service.ai.AiTaskRunner tasks;
    private final tools.jackson.databind.ObjectMapper json = new tools.jackson.databind.ObjectMapper();

    public ReviewServiceImpl(JdbcClient jdbc, SnowflakeIdGenerator ids, AiGateway ai,
                             EntitlementService entitlements, com.justinsight.lexiforge.service.ai.AiTaskRunner tasks) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.ai = ai;
        this.entitlements = entitlements;
        this.tasks = tasks;
    }

    @Override
    @Transactional
    public ReviewDto.Session start(long userId, ReviewDto.StartRequest request) {
        long sessionId = ids.nextId();
        String direction = request.direction() == null ? "EN_TO_ZH" : request.direction();
        jdbc.sql("INSERT INTO review_session(id,user_id,direction,status,item_limit) " +
                        "VALUES(:id,:uid,:direction,'ACTIVE',:limit)")
                .param("id", sessionId).param("uid", userId).param("direction", direction)
                .param("limit", request.limit()).update();
        String sql = """
                SELECT v.id,rs.direction,v.word,v.chinese_meaning,v.part_of_speech,
                       v.ipa,v.example_sentence
                FROM review_schedule rs
                JOIN vocabulary_item v ON v.id=rs.vocabulary_id
                WHERE rs.user_id=:uid AND rs.due_at<=now()
                  AND (:direction='MIXED' OR rs.direction=:direction)
                ORDER BY rs.due_at LIMIT :limit
                """;
        List<ReviewDto.Item> items = jdbc.sql(sql).param("uid", userId)
                .param("direction", direction).param("limit", request.limit())
                .query((row, n) -> {
                    String itemDirection = row.getString("direction");
                    String prompt = itemDirection.equals("EN_TO_ZH")
                            ? row.getString("word") : row.getString("chinese_meaning");
                    return new ReviewDto.Item(Long.toString(row.getLong("id")), itemDirection,
                            prompt, row.getString("part_of_speech"), row.getString("ipa"),
                            row.getString("example_sentence"));
                }).list();
        for (var item : items) {
            jdbc.sql("""
                    INSERT INTO review_session_item(session_id,vocabulary_id,direction,word_text,expected_answer,part_of_speech,context)
                    SELECT :sid,id,:direction,word,CASE WHEN :direction='EN_TO_ZH' THEN chinese_meaning ELSE word END,part_of_speech,example_sentence
                    FROM vocabulary_item WHERE id=:vid AND user_id=:uid
                    """).param("sid", sessionId).param("direction", item.direction())
                    .param("vid", Long.parseLong(item.vocabularyId())).param("uid", userId).update();
        }
        return new ReviewDto.Session(Long.toString(sessionId), direction, items);
    }

    @Override
    @Transactional
    public ReviewDto.AnswerResult answer(long userId, long sessionId,
                                         ReviewDto.AnswerRequest request) {
        var session = jdbc.sql("SELECT id FROM review_session " +
                        "WHERE id=:id AND user_id=:uid AND status='ACTIVE' FOR UPDATE")
                .param("id", sessionId).param("uid", userId).query().listOfRows();
        if (session.isEmpty()) throw sessionMissing("复习会话不存在或已结束");

        long vocabularyId;
        try {
            vocabularyId = Long.parseLong(request.vocabularyId());
        } catch (NumberFormatException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "词条编号不正确");
        }
        var members = jdbc.sql("SELECT * FROM review_session_item WHERE session_id=:sid AND vocabulary_id=:vid AND direction=:direction")
                .param("sid", sessionId).param("vid", vocabularyId).param("direction", request.direction()).query().listOfRows();
        if (members.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "REVIEW_ITEM_NOT_FOUND", "词条不存在于本轮复习中");
        var word = members.getFirst();
        if (word.get("response_json") != null) {
            if (!request.answer().trim().equals(word.get("answer_text")))
                throw new ApiException(HttpStatus.CONFLICT, "REVIEW_ALREADY_ANSWERED", "本题已提交，请继续下一题");
            return json.readValue((String) word.get("response_json"), ReviewDto.AnswerResult.class);
        }
        String expected = (String) word.get("expected_answer");
        AiGateway.GradeResult grade = localGrade(request.direction(), expected, request.answer());
        if (grade == null) {
            ensureAiAvailable();
            entitlements.consume(userId, EntitlementService.UsageType.GRADING);
            grade = tasks.run(() -> ai.grade(new AiGateway.GradePrompt(request.direction(),
                    (String) word.get("word_text"), expected, request.answer(),
                    (String) word.get("part_of_speech"), (String) word.get("context"))));
        }

        int quality = switch (grade.result()) {
            case "CORRECT" -> 5;
            case "PARTIAL" -> 3;
            default -> 1;
        };
        var schedule = jdbc.sql("SELECT * FROM review_schedule " +
                        "WHERE user_id=:uid AND vocabulary_id=:vid AND direction=:direction FOR UPDATE")
                .param("uid", userId).param("vid", vocabularyId)
                .param("direction", request.direction()).query().singleRow();
        int repetitions = ((Number) schedule.get("repetitions")).intValue();
        int oldInterval = ((Number) schedule.get("interval_days")).intValue();
        double easiness = ((BigDecimal) schedule.get("easiness")).doubleValue();
        int interval;
        if (quality < 3) {
            repetitions = 0;
            interval = 1;
        } else {
            repetitions++;
            interval = repetitions == 1 ? 1 : repetitions == 2 ? 6
                    : Math.max(1, (int) Math.round(oldInterval * easiness));
        }
        easiness = Math.max(1.3, easiness +
                (0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02)));
        Instant due = Instant.now().plus(interval, ChronoUnit.DAYS);
        jdbc.sql("UPDATE review_schedule SET repetitions=:repetitions,interval_days=:days," +
                        "easiness=:easiness,due_at=:due,last_result=:result WHERE id=:id")
                .param("repetitions", repetitions).param("days", interval)
                .param("easiness", BigDecimal.valueOf(easiness)).param("due", java.sql.Timestamp.from(due))
                .param("result", grade.result()).param("id", schedule.get("id")).update();
        jdbc.sql("INSERT INTO review_attempt(id,session_id,user_id,vocabulary_id,direction," +
                        "answer,result,feedback,confused_with) " +
                        "VALUES(:id,:sid,:uid,:vid,:direction,:answer,:result,:feedback,:confused)")
                .param("id", ids.nextId()).param("sid", sessionId).param("uid", userId)
                .param("vid", vocabularyId).param("direction", request.direction())
                .param("answer", request.answer()).param("result", grade.result())
                .param("feedback", grade.feedback()).param("confused", grade.confusedWith()).update();
        var response = new ReviewDto.AnswerResult(grade.result(), grade.feedback(), expected,
                grade.confusedWith(), due.toString());
        jdbc.sql("UPDATE review_session_item SET answer_text=:answer,response_json=:response,answered_at=now() WHERE session_id=:sid AND vocabulary_id=:vid AND direction=:direction")
                .param("answer", request.answer().trim()).param("response", json.writeValueAsString(response))
                .param("sid", sessionId).param("vid", vocabularyId).param("direction", request.direction()).update();
        return response;
    }

    @Override
    @Transactional
    public void finish(long userId, long sessionId) {
        int updated = jdbc.sql("UPDATE review_session SET status='FINISHED',finished_at=now() " +
                        "WHERE id=:id AND user_id=:uid")
                .param("id", sessionId).param("uid", userId).update();
        if (updated == 0) throw sessionMissing("复习会话不存在");
    }

    private AiGateway.GradeResult localGrade(String direction, String expected, String answer) {
        String normalizedAnswer = normalize(answer);
        for (String candidate : expected.split("[；;，,、/]")) {
            if (normalizedAnswer.equals(normalize(candidate))) {
                return new AiGateway.GradeResult("CORRECT", "回答正确", "");
            }
        }
        if (direction.equals("ZH_TO_EN") && normalizedAnswer.matches("[a-z]+")
                && distance(normalizedAnswer, normalize(expected)) <= 1) {
            return new AiGateway.GradeResult("PARTIAL", "意思正确，请检查拼写", "");
        }
        return null;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}]", "");
    }

    private int distance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1));
            }
            previous = current;
        }
        return previous[right.length()];
    }

    private ApiException sessionMissing(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "REVIEW_SESSION_NOT_FOUND", message);
    }

    private void ensureAiAvailable() {
        if (!ai.available()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_NOT_CONFIGURED",
                    "AI 服务尚未配置，暂时无法进行语义判分");
        }
    }
}

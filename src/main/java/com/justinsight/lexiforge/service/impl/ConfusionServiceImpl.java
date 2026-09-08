package com.justinsight.lexiforge.service.impl;

import com.justinsight.lexiforge.service.ConfusionService;
import com.justinsight.lexiforge.service.EntitlementService;
import com.justinsight.lexiforge.service.ai.provider.AiGateway;
import com.justinsight.lexiforge.model.dto.ConfusionDto;
import com.justinsight.lexiforge.common.id.SnowflakeIdGenerator;
import com.justinsight.lexiforge.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ConfusionServiceImpl implements ConfusionService {
    private final JdbcClient jdbc;
    private final SnowflakeIdGenerator ids;
    private final AiGateway ai;
    private final EntitlementService entitlements;

    public ConfusionServiceImpl(JdbcClient jdbc, SnowflakeIdGenerator ids, AiGateway ai,
                                EntitlementService entitlements) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.ai = ai;
        this.entitlements = entitlements;
    }

    @Override
    public List<ConfusionDto.GroupView> list(long userId) {
        return jdbc.sql("SELECT id,name,note FROM confusion_group " +
                        "WHERE user_id=:uid ORDER BY created_at DESC")
                .param("uid", userId)
                .query((row, n) -> view(row.getLong(1), row.getString(2), row.getString(3)))
                .list();
    }

    @Override
    @Transactional
    public ConfusionDto.GroupView create(long userId, ConfusionDto.CreateRequest request) {
        long groupId = ids.nextId();
        jdbc.sql("INSERT INTO confusion_group(id,user_id,name,note) VALUES(:id,:uid,:name,:note)")
                .param("id", groupId).param("uid", userId).param("name", request.name())
                .param("note", request.note()).update();
        for (ConfusionDto.WordRequest word : request.words()) {
            jdbc.sql("INSERT INTO confusion_word(id,group_id,word,meaning,ipa,part_of_speech," +
                            "example_sentence) VALUES(:id,:gid,:word,:meaning,:ipa,:pos,:example)")
                    .param("id", ids.nextId()).param("gid", groupId).param("word", word.word())
                    .param("meaning", word.meaning()).param("ipa", word.ipa())
                    .param("pos", word.partOfSpeech()).param("example", word.exampleSentence()).update();
        }
        return view(groupId, request.name(), request.note());
    }

    @Override
    @Transactional
    public void delete(long userId, long id) {
        int deleted = jdbc.sql("DELETE FROM confusion_group WHERE id=:id AND user_id=:uid")
                .param("id", id).param("uid", userId).update();
        if (deleted == 0) throw missing();
    }

    @Override
    @Transactional
    public ConfusionDto.MnemonicView createMnemonic(long userId, long groupId,
                                                    ConfusionDto.MnemonicRequest request) {
        check(groupId, userId);
        ensureAiAvailable();
        int count = jdbc.sql("SELECT count(*) FROM mnemonic WHERE group_id=:gid AND user_id=:uid")
                .param("gid", groupId).param("uid", userId).query(Integer.class).single();
        if (count >= entitlements.plan(userId).mnemonicVersions()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "MNEMONIC_VERSION_LIMIT",
                    "该易混词组的记忆法版本已达套餐上限");
        }
        entitlements.consume(userId, EntitlementService.UsageType.MNEMONIC);
        List<String> words = jdbc.sql("SELECT word FROM confusion_word " +
                        "WHERE group_id=:gid ORDER BY word")
                .param("gid", groupId).query(String.class).list();
        String style = request.style() == null ? "DEFAULT" : request.style();
        var result = ai.mnemonic(new AiGateway.MnemonicPrompt(words, style,
                request.mistakeContext()));
        long id = ids.nextId();
        jdbc.sql("INSERT INTO mnemonic(id,group_id,user_id,style,slogan,visual_hook,it_analogy," +
                        "contrast_example,recall_hook) VALUES(:id,:gid,:uid,:style,:slogan,:visual," +
                        ":analogy,:contrast,:recall)")
                .param("id", id).param("gid", groupId).param("uid", userId)
                .param("style", style).param("slogan", result.slogan())
                .param("visual", result.visualHook()).param("analogy", result.itAnalogy())
                .param("contrast", result.contrastExample()).param("recall", result.recallHook())
                .update();
        return new ConfusionDto.MnemonicView(Long.toString(id), style, result.slogan(),
                result.visualHook(), result.itAnalogy(), result.contrastExample(),
                result.recallHook(), null, null);
    }

    @Override
    @Transactional
    public ConfusionDto.MnemonicView updateMnemonic(long userId, long groupId, long mnemonicId,
                                                    ConfusionDto.MnemonicUpdate request) {
        check(groupId, userId);
        int updated = jdbc.sql("UPDATE mnemonic SET user_note=:note,helpful=:help " +
                        "WHERE id=:id AND group_id=:gid AND user_id=:uid")
                .param("note", request.userNote()).param("help", request.helpful())
                .param("id", mnemonicId).param("gid", groupId).param("uid", userId).update();
        if (updated == 0) throw missing();
        return mnemonicView(mnemonicId);
    }

    private ConfusionDto.GroupView view(long id, String name, String note) {
        List<ConfusionDto.WordRequest> words = jdbc.sql("SELECT word,meaning,ipa,part_of_speech," +
                        "example_sentence FROM confusion_word WHERE group_id=:id ORDER BY word")
                .param("id", id)
                .query((row, n) -> new ConfusionDto.WordRequest(row.getString(1), row.getString(2),
                        row.getString(3), row.getString(4), row.getString(5))).list();
        List<ConfusionDto.MnemonicView> mnemonics = jdbc.sql("SELECT id FROM mnemonic " +
                        "WHERE group_id=:id ORDER BY created_at DESC")
                .param("id", id).query(Long.class).list().stream().map(this::mnemonicView).toList();
        return new ConfusionDto.GroupView(Long.toString(id), name, note, words, mnemonics);
    }

    private ConfusionDto.MnemonicView mnemonicView(long id) {
        return jdbc.sql("SELECT * FROM mnemonic WHERE id=:id").param("id", id)
                .query((row, n) -> new ConfusionDto.MnemonicView(Long.toString(row.getLong("id")),
                        row.getString("style"), row.getString("slogan"), row.getString("visual_hook"),
                        row.getString("it_analogy"), row.getString("contrast_example"),
                        row.getString("recall_hook"), row.getString("user_note"),
                        (Boolean) row.getObject("helpful"))).single();
    }

    private void check(long id, long userId) {
        int count = jdbc.sql("SELECT count(*) FROM confusion_group WHERE id=:id AND user_id=:uid")
                .param("id", id).param("uid", userId).query(Integer.class).single();
        if (count == 0) throw missing();
    }

    private ApiException missing() {
        return new ApiException(HttpStatus.NOT_FOUND, "CONFUSION_GROUP_NOT_FOUND", "易混词组不存在");
    }

    private void ensureAiAvailable() {
        if (!ai.available()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_NOT_CONFIGURED",
                    "AI 服务尚未配置，暂时无法生成记忆法");
        }
    }
}

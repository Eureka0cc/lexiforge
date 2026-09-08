package com.justinsight.lexiforge.service.impl;

import com.justinsight.lexiforge.service.WordPackService;
import com.justinsight.lexiforge.model.dto.WordPackDto;
import com.justinsight.lexiforge.common.id.SnowflakeIdGenerator;
import com.justinsight.lexiforge.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class WordPackServiceImpl implements WordPackService {
    private final JdbcClient jdbc;
    private final SnowflakeIdGenerator ids;

    public WordPackServiceImpl(JdbcClient jdbc, SnowflakeIdGenerator ids) {
        this.jdbc = jdbc;
        this.ids = ids;
    }

    @Override
    public List<WordPackDto.Pack> list(long userId) {
        return jdbc.sql("SELECT p.*,EXISTS(SELECT 1 FROM vocabulary_item v " +
                        "WHERE v.user_id=:uid AND v.source_type='WORD_PACK' AND v.source_id=p.id) imported " +
                        "FROM word_pack p ORDER BY p.name")
                .param("uid", userId)
                .query((row, n) -> new WordPackDto.Pack(Long.toString(row.getLong("id")),
                        row.getString("code"), row.getString("name"), row.getString("description"),
                        row.getInt("item_count"), row.getBoolean("pro_only"),
                        row.getBoolean("imported")))
                .list();
    }

    @Override
    @Transactional
    public WordPackDto.ImportResult importPack(long userId, long packId) {
        int exists = jdbc.sql("SELECT count(*) FROM word_pack WHERE id=:id")
                .param("id", packId).query(Integer.class).single();
        if (exists == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "WORD_PACK_NOT_FOUND", "词包不存在");
        }
        var rows = jdbc.sql("SELECT * FROM word_pack_entry WHERE pack_id=:id")
                .param("id", packId).query().listOfRows();
        int imported = 0;
        for (var row : rows) {
            String word = (String) row.get("word");
            int found = jdbc.sql("SELECT count(*) FROM vocabulary_item " +
                            "WHERE user_id=:uid AND lower(word)=lower(:word)")
                    .param("uid", userId).param("word", word).query(Integer.class).single();
            if (found > 0) continue;
            long vocabularyId = ids.nextId();
            jdbc.sql("INSERT INTO vocabulary_item(id,user_id,word,part_of_speech,ipa," +
                            "chinese_meaning,english_definition,example_sentence,source_type,source_id) " +
                            "VALUES(:id,:uid,:word,:pos,:ipa,:zh,:en,:example,'WORD_PACK',:packId)")
                    .param("id", vocabularyId).param("uid", userId).param("word", word)
                    .param("pos", row.get("part_of_speech")).param("ipa", row.get("ipa"))
                    .param("zh", row.get("chinese_meaning")).param("en", row.get("english_definition"))
                    .param("example", row.get("example_sentence")).param("packId", packId).update();
            for (String direction : List.of("EN_TO_ZH", "ZH_TO_EN")) {
                jdbc.sql("INSERT INTO review_schedule(id,user_id,vocabulary_id,direction) " +
                                "VALUES(:id,:uid,:vid,:direction)")
                        .param("id", ids.nextId()).param("uid", userId)
                        .param("vid", vocabularyId).param("direction", direction).update();
            }
            imported++;
        }
        return new WordPackDto.ImportResult(imported);
    }
}

package com.justinsight.lexiforge.service;

import com.justinsight.lexiforge.common.ApiException;
import com.justinsight.lexiforge.common.id.SnowflakeIdGenerator;
import com.justinsight.lexiforge.security.AuthMailCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class AuthMailDelivery {
    private final JdbcClient jdbc;
    private final SnowflakeIdGenerator ids;
    private final String mode, secret, origin;
    private final Path directory;
    private final ObjectMapper json = new ObjectMapper();
    public AuthMailDelivery(JdbcClient jdbc, SnowflakeIdGenerator ids, Environment environment,
                            @Value("${app.auth.mail.mode:disabled}") String mode,
                            @Value("${app.auth.mail.secret:}") String secret,
                            @Value("${app.frontend-origin}") String origin,
                            @Value("${app.auth.mail.directory:../tmp/auth-mail}") String directory) {
        this.jdbc = jdbc; this.ids = ids; this.mode = mode; this.secret = secret; this.origin = origin;
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        if (!mode.equals("disabled") && !mode.equals("file")) throw new IllegalArgumentException("本轮仅支持本地 file 邮件模式或 disabled");
        if (mode.equals("file") && !environment.acceptsProfiles(Profiles.of("dev", "test"))) {
            throw new IllegalStateException("文件邮件模式只允许用于本地开发/测试");
        }
    }
    public boolean enabled() { return mode.equals("file") && secret.length() >= 32; }
    @Transactional
    public void enqueue(long userId, String email, String purpose, String token) {
        if (!enabled()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_MAIL_UNAVAILABLE", "验证邮件服务尚未配置");
        String action = purpose.equals("VERIFY_EMAIL") ? "verify" : "reset";
        String link = origin + "/account-action#type=" + action + "&token=" + token;
        String encrypted = AuthMailCipher.encrypt(json.writeValueAsString(new Message(email, purpose, link)), secret);
        jdbc.sql("INSERT INTO auth_mail_outbox(id,user_id,purpose,payload_ciphertext) VALUES(:id,:uid,:purpose,:payload)")
                .param("id", ids.nextId()).param("uid", userId).param("purpose", purpose).param("payload", encrypted).update();
    }
    @Scheduled(fixedDelayString = "${app.auth.mail.dispatch-ms:2000}")
    @Transactional
    public void dispatch() {
        if (!enabled()) return;
        jdbc.sql("UPDATE auth_mail_outbox SET status='FAILED' WHERE status='PENDING' AND created_at<now()-interval '15 minutes'").update();
        var rows = jdbc.sql("SELECT id,payload_ciphertext,attempts FROM auth_mail_outbox WHERE status='PENDING' AND next_attempt_at<=now() ORDER BY created_at LIMIT 10 FOR UPDATE SKIP LOCKED")
                .query().listOfRows();
        for (var row : rows) {
            long id = ((Number) row.get("id")).longValue();
            try {
                var message = json.readValue(AuthMailCipher.decrypt((String) row.get("payload_ciphertext"), secret), Message.class);
                Files.createDirectories(directory);
                Path target = directory.resolve(id + ".txt");
                Files.writeString(target, "本地邮件测试模式\n收件人：" + message.email() + "\n用途：" + message.purpose()
                        + "\n链接（15 分钟内有效，只能使用一次）：\n" + message.link() + "\n");
                jdbc.sql("UPDATE auth_mail_outbox SET status='SENT',sent_at=now(),attempts=attempts+1 WHERE id=:id").param("id", id).update();
            } catch (Exception exception) {
                int attempts = ((Number) row.get("attempts")).intValue() + 1;
                jdbc.sql("UPDATE auth_mail_outbox SET attempts=:attempts,status=:status,next_attempt_at=now()+interval '30 seconds' WHERE id=:id")
                        .param("attempts", attempts).param("status", attempts >= 5 ? "FAILED" : "PENDING").param("id", id).update();
            }
        }
    }
    public record Message(String email, String purpose, String link) {}
}

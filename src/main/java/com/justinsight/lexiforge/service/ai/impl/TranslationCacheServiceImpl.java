package com.justinsight.lexiforge.service.ai.impl;

import com.justinsight.lexiforge.service.ai.TranslationCacheService;
import com.justinsight.lexiforge.config.ai.TranslationCacheConfiguration.Settings;
import com.justinsight.lexiforge.model.dto.TranslationDto;
import com.justinsight.lexiforge.model.entity.DomainEntity;
import com.justinsight.lexiforge.service.ai.provider.AiGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

@Service
public class TranslationCacheServiceImpl implements TranslationCacheService {
    private final ObjectMapper mapper;
    private final Settings settings;
    private final String model;
    private final List<String> providerIdentity;
    private final Clock clock;
    private final Map<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final ReentrantLock[] locks = new ReentrantLock[256];

    @Autowired
    public TranslationCacheServiceImpl(ObjectMapper mapper, Settings settings, Environment environment) {
        this(mapper, settings, environment, Clock.systemUTC());
    }

    TranslationCacheServiceImpl(ObjectMapper mapper, Settings settings, Environment environment, Clock clock) {
        this.mapper = mapper;
        this.settings = settings;
        this.clock = clock;
        this.model = environment.getProperty("spring.ai.openai.chat.options.model", "unspecified");
        this.providerIdentity = List.of(
                environment.getProperty("spring.ai.openai.base-url", ""), model,
                environment.getProperty("spring.ai.openai.chat.options.extra-body.thinking.type", "default"),
                environment.getProperty("spring.ai.openai.chat.options.reasoning-effort", "default"),
                settings.revision());
        for (int index = 0; index < locks.length; index++) locks[index] = new ReentrantLock();
    }

    @Override
    public String model() { return model; }

    @Override
    public String fingerprint(long userId, TranslationDto.Request request, DomainEntity domain,
                              List<AiGateway.GlossaryTerm> glossary) {
        String input = mapper.writeValueAsString(List.of(userId, request.sourceText(),
                request.sourceLanguage(), request.targetLanguage(), value(request.context()),
                domain.id == null ? 0L : domain.id, value(domain.name), value(domain.description),
                value(domain.configurationJson), domain.revision, value(domain.configurationVersion),
                domain.updatedAt == null ? "" : domain.updatedAt.toString(), glossary,
                providerIdentity, PROMPT_VERSION));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    // Bounded stripes serialize identical requests without an unbounded per-request lock registry.
    @Override
    public <T> T exclusive(String key, Supplier<T> action) {
        ReentrantLock lock = locks[Math.floorMod(key.hashCode(), locks.length)];
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    // Cache IDs, not detached entities. Callers must check existence and ownership on a hit.
    @Override
    public synchronized Long find(String key) {
        Entry entry = entries.get(key);
        if (entry == null) return null;
        if (!clock.instant().isBefore(entry.expiresAt())) {
            entries.remove(key);
            return null;
        }
        return entry.id();
    }

    @Override
    public synchronized void remember(String key, long id) {
        entries.put(key, new Entry(id, clock.instant().plus(settings.ttl())));
        while (entries.size() > settings.maximumSize()) entries.remove(entries.keySet().iterator().next());
    }

    @Override
    public synchronized void evict(long id) {
        entries.values().removeIf(entry -> entry.id() == id);
    }

    private static String value(String text) { return text == null ? "" : text; }
    private record Entry(long id, Instant expiresAt) {}
}

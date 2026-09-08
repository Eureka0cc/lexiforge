package com.justinsight.lexiforge.service.ai;

import java.time.Instant;

public interface AiRequestService {
    BeginResult begin(long userId, String requestId, String operation, String input);

    void succeed(long id, long resultId, Instant startedAt);

    void fail(long id, String code, Instant startedAt);

    void cancel(long id, Instant startedAt);

    record BeginResult(long id, boolean replay, Long resultId, Instant startedAt) {}
}

package com.justinsight.lexiforge.service;

import com.justinsight.lexiforge.model.dto.ReviewDto;

public interface ReviewService {
    ReviewDto.Session start(long userId, ReviewDto.StartRequest request);

    ReviewDto.AnswerResult answer(long userId, long sessionId, ReviewDto.AnswerRequest request);

    void finish(long userId, long sessionId);
}

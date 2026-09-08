package com.justinsight.lexiforge.service;

import com.justinsight.lexiforge.model.dto.CommercialDto;

public interface SubscriptionService {
    CommercialDto.SubscriptionView current(long userId);

    CommercialDto.SubscriptionView activate(long userId, int days);

    CommercialDto.SubscriptionView expire(long userId);
}

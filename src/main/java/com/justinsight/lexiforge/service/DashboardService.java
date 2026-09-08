package com.justinsight.lexiforge.service;

import com.justinsight.lexiforge.model.dto.CommercialDto;

public interface DashboardService {
    CommercialDto.Dashboard get(long userId);
}

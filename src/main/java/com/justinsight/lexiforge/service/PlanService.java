package com.justinsight.lexiforge.service;

import com.justinsight.lexiforge.model.dto.CommercialDto;

import java.util.List;

public interface PlanService {
    List<CommercialDto.PlanView> list();
}

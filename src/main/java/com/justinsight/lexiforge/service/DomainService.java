package com.justinsight.lexiforge.service;

import com.justinsight.lexiforge.model.dto.DomainDto;

import java.util.List;

public interface DomainService {
    List<DomainDto.DomainView> list(long userId);

    DomainDto.DomainView create(long userId, DomainDto.DomainRequest request);

    DomainDto.DomainView update(long userId, long id, DomainDto.DomainRequest request);

    void delete(long userId, long id);

    DomainDto.TermView addTerm(long userId, long domainId, DomainDto.TermRequest request);

    void removeTerm(long userId, long domainId, long termId);
}

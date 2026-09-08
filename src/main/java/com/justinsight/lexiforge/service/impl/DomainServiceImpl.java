package com.justinsight.lexiforge.service.impl;

import com.justinsight.lexiforge.service.DomainService;
import com.justinsight.lexiforge.service.EntitlementService;
import com.justinsight.lexiforge.model.entity.DomainEntity;
import com.justinsight.lexiforge.repository.DomainRepository;
import com.justinsight.lexiforge.model.entity.DomainTermEntity;
import com.justinsight.lexiforge.repository.DomainTermRepository;
import com.justinsight.lexiforge.model.dto.DomainDto;
import com.justinsight.lexiforge.common.id.SnowflakeIdGenerator;
import com.justinsight.lexiforge.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class DomainServiceImpl implements DomainService {
    private final DomainRepository domains;
    private final DomainTermRepository terms;
    private final SnowflakeIdGenerator ids;
    private final EntitlementService entitlements;

    public DomainServiceImpl(DomainRepository domains, DomainTermRepository terms,
                             SnowflakeIdGenerator ids, EntitlementService entitlements) {
        this.domains = domains;
        this.terms = terms;
        this.ids = ids;
        this.entitlements = entitlements;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DomainDto.DomainView> list(long userId) {
        return domains.findByBuiltInTrueOrOwnerIdOrderByBuiltInDescNameAsc(userId)
                .stream().map(this::view).toList();
    }

    @Override
    @Transactional
    public DomainDto.DomainView create(long userId, DomainDto.DomainRequest request) {
        if (domains.countByOwnerId(userId) >= entitlements.plan(userId).customDomainLimit()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DOMAIN_LIMIT_REACHED",
                    "自定义领域数量已达套餐上限");
        }
        DomainEntity entity = new DomainEntity();
        entity.id = ids.nextId();
        entity.ownerId = userId;
        entity.name = request.name();
        entity.description = request.description();
        return view(domains.save(entity));
    }

    @Override
    @Transactional
    public DomainDto.DomainView update(long userId, long id, DomainDto.DomainRequest request) {
        DomainEntity entity = owned(userId, id);
        entity.name = request.name();
        entity.description = request.description();
        entity.updatedAt = Instant.now();
        return view(domains.save(entity));
    }

    @Override
    @Transactional
    public void delete(long userId, long id) {
        DomainEntity entity = owned(userId, id);
        terms.deleteByDomainId(id);
        domains.delete(entity);
    }

    @Override
    @Transactional
    public DomainDto.TermView addTerm(long userId, long domainId, DomainDto.TermRequest request) {
        owned(userId, domainId);
        if (terms.countByDomainId(domainId) >= 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TERM_LIMIT_REACHED",
                    "每个领域最多 200 条术语");
        }
        DomainTermEntity entity = new DomainTermEntity();
        entity.id = ids.nextId();
        entity.domainId = domainId;
        entity.sourceTerm = request.sourceTerm();
        entity.targetTerm = request.targetTerm();
        entity.note = request.note();
        return term(terms.save(entity));
    }

    @Override
    @Transactional
    public void removeTerm(long userId, long domainId, long termId) {
        owned(userId, domainId);
        DomainTermEntity entity = terms.findById(termId)
                .filter(term -> term.domainId.equals(domainId))
                .orElseThrow(this::notFound);
        terms.delete(entity);
    }

    private DomainEntity owned(long userId, long id) {
        return domains.findByIdAndOwnerId(id, userId).orElseThrow(this::notFound);
    }

    private DomainDto.DomainView view(DomainEntity entity) {
        List<DomainDto.TermView> domainTerms = terms.findByDomainIdOrderBySourceTerm(entity.id)
                .stream().map(this::term).toList();
        return new DomainDto.DomainView(entity.id.toString(), entity.code, entity.name,
                entity.description, entity.builtIn, entity.medicalDisclaimer, domainTerms);
    }

    private DomainDto.TermView term(DomainTermEntity entity) {
        return new DomainDto.TermView(entity.id.toString(), entity.sourceTerm,
                entity.targetTerm, entity.note);
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "DOMAIN_NOT_FOUND", "领域不存在");
    }
}

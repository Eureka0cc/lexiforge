package com.justinsight.lexiforge.service;

import com.justinsight.lexiforge.model.dto.ConfusionDto;

import java.util.List;

public interface ConfusionService {
    List<ConfusionDto.GroupView> list(long userId);

    ConfusionDto.GroupView create(long userId, ConfusionDto.CreateRequest request);

    void delete(long userId, long id);

    ConfusionDto.MnemonicView createMnemonic(long userId, long groupId,
                                              ConfusionDto.MnemonicRequest request);

    ConfusionDto.MnemonicView updateMnemonic(long userId, long groupId, long mnemonicId,
                                              ConfusionDto.MnemonicUpdate request);
}

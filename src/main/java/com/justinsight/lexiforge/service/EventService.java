package com.justinsight.lexiforge.service;

public interface EventService {
    void track(Long userId, String name, String propertiesJson);
}

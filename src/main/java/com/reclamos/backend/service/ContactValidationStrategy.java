package com.reclamos.backend.service;

import com.reclamos.backend.entity.AnonymousContactChannel;

public interface ContactValidationStrategy {
    AnonymousContactChannel channel();

    void validate(String value);
}

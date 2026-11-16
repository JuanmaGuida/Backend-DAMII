package com.reclamos.backend.service;

import com.reclamos.backend.exception.InvalidTicketRequestException;
import com.reclamos.backend.exception.PreconditionFailedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class ResourcePreconditionsTest {
    @Test void acceptsAbsentWildcardAndStrongMemberOfList() {
        for (String value : new String[]{null, "*", "\"current\"", "W/\"current\", \"other,tag\", \"current\""})
            ResourcePreconditions.check(value, "\"current\"");
    }
    @ParameterizedTest @ValueSource(strings = {"W/\"current\"", "\"old\"", "\"CURRENT\"", "\"old\", W/\"current\""})
    void requiresStrongExactMatch(String value) {
        assertThatThrownBy(() -> ResourcePreconditions.check(value, "\"current\""))
                .isInstanceOf(PreconditionFailedException.class);
    }
    @ParameterizedTest @ValueSource(strings = {"", "current", "\"current\",", "*, \"current\"", "\"current\", broken", "\"current\" \"other\""})
    void rejectsMalformedEntireHeaderEvenAfterMatchingTag(String value) {
        assertThatThrownBy(() -> ResourcePreconditions.check(value, "\"current\""))
                .isInstanceOf(InvalidTicketRequestException.class);
    }
}

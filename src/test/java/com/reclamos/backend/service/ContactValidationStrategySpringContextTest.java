package com.reclamos.backend.service;

import com.reclamos.backend.entity.AnonymousContactChannel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@ActiveProfiles("dev")
class ContactValidationStrategySpringContextTest {
    @Autowired
    private List<ContactValidationStrategy> strategies;

    @Autowired
    private AnonymousContactValidator validator;

    @Autowired
    private TicketService ticketService;

    @Test
    void contextDiscoversBothStrategiesAndBuildsConsumers() {
        Set<AnonymousContactChannel> channels = strategies.stream()
                .map(ContactValidationStrategy::channel)
                .collect(Collectors.toSet());

        assertEquals(2, strategies.size());
        assertEquals(Set.of(AnonymousContactChannel.EMAIL, AnonymousContactChannel.PHONE), channels);
        assertNotNull(validator);
        assertNotNull(ticketService);
    }
}

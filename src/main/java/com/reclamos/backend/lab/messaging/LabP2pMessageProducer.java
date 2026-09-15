package com.reclamos.backend.lab.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
@Profile("p2p-lab")
public class LabP2pMessageProducer {
    private final JmsTemplate jmsTemplate;
    private final ObjectMapper objectMapper;
    private final String queue;

    public LabP2pMessageProducer(
            JmsTemplate jmsTemplate,
            ObjectMapper objectMapper,
            @Value("${lab.p2p.queue}") String queue
    ) {
        this.jmsTemplate = jmsTemplate;
        this.objectMapper = objectMapper;
        this.queue = queue;
    }

    public void send(LabP2pMessage message) throws JacksonException {
        String json = objectMapper.writeValueAsString(message);
        jmsTemplate.send(queue, session -> session.createTextMessage(json));
    }
}

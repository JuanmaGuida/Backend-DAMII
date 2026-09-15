package com.reclamos.backend.lab.messaging;

import jakarta.jms.JMSException;
import jakarta.jms.TextMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@Profile("p2p-lab")
public class LabP2pMessageConsumer {
    private static final Logger log = LoggerFactory.getLogger(LabP2pMessageConsumer.class);

    private final ObjectMapper objectMapper;

    public LabP2pMessageConsumer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @JmsListener(
            destination = "${lab.p2p.queue}",
            concurrency = "${lab.p2p.concurrency:2-2}"
    )
    public void consume(TextMessage textMessage) throws JMSException, JacksonException {
        LabP2pMessage message = objectMapper.readValue(textMessage.getText(), LabP2pMessage.class);

        log.info(
                "LAB-P2P consumed sequence={} publicId={} JMSMessageID={} thread={}",
                message.sequence(),
                message.publicId(),
                textMessage.getJMSMessageID(),
                Thread.currentThread().getName()
        );
    }
}

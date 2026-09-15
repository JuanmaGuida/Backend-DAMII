package com.reclamos.backend.lab.messaging;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;

@RestController
@RequestMapping("/api/lab/p2p/messages")
@Profile("p2p-lab")
public class LabP2pController {
    private final LabP2pMessageProducer producer;

    public LabP2pController(LabP2pMessageProducer producer) {
        this.producer = producer;
    }

    @PostMapping
    public ResponseEntity<Void> send(@RequestBody LabP2pMessage message) throws JacksonException {
        producer.send(message);
        return ResponseEntity.accepted().build();
    }
}

package com.reclamos.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reclamos.backend.inbox.CoreIncomingEventValidator;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class M3DocumentationContractTest {
    @Test void documentedExamplesMatchTheExistingSchemasWithoutInventedFields() throws Exception {
        Path examples=Path.of("docs/integrations/examples");
        ObjectMapper json=new ObjectMapper();
        Map<String,Object> routed=json.readValue(examples.resolve("routed.json").toFile(),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {});
        EventEnvelopeContractAssertions.assertFullEventValid(routed);
        var validator=new CoreIncomingEventValidator();
        for (String name: new String[]{"started","information-required","resolved"}) {
            var event=validator.decode(Files.readAllBytes(examples.resolve(name+".json")));
            assertThat(event.sourceModule()).isEqualTo("obras");
            assertThat(event.eventType()).isEqualTo("updateTicketStatus");
            var request=validator.statusRequest(event);
            if(name.equals("resolved")) assertThat(request.attachments()).hasSize(1);
        }
    }
}

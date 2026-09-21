package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.AttachmentUploadRequest;
import com.reclamos.backend.entity.ActorType;
import com.reclamos.backend.entity.Attachment;
import com.reclamos.backend.entity.MessageVisibility;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.repository.AttachmentRepository;
import com.reclamos.backend.storage.FileStorage;
import com.reclamos.backend.storage.StoredFile;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "ticket.information-request.expiration-scan-delay=3600000",
        "ticket.sla.milestone-scan-delay=3600000"
})
@ActiveProfiles("dev")
@Transactional
class TicketAttachmentUploadPostgresIntegrationTest {
    @Autowired private TicketAttachmentUploadService uploadService;
    @Autowired private AttachmentRepository attachmentRepository;
    @Autowired private JdbcTemplate database;
    @MockitoBean private FileStorage fileStorage;

    @Test
    void persistsTicketFkVisibilityActorMetadataAndSafeResponseOnPostgres() {
        UUID ticketId = UUID.randomUUID();
        UUID citizenId = UUID.randomUUID();
        database.update("INSERT INTO module_users "
                        + "(citizen_id,first_name,last_name,role,active,last_synced_at,created_at,updated_at) "
                        + "VALUES (?, 'HU28', 'Owner', 'CITIZEN', TRUE, CURRENT_TIMESTAMP, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", citizenId);
        database.update("INSERT INTO tickets "
                        + "(id,public_id,tracking_code_hash,citizen_id,is_anonymous,request_type_id,ticket_type,"
                        + "responsible_area_id,summary,description,form_data,current_status,current_priority,"
                        + "is_escalated,status_changed_at,created_at,updated_at) "
                        + "SELECT ?, ?, ?, ?, FALSE, id, ticket_type, 'M6', 'HU28', 'HU28', '{}'::jsonb, "
                        + "'CLOSED', 'LOW', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP "
                        + "FROM request_types WHERE code='INFORMAR_UN_BACHE'",
                ticketId, "HU28-" + ticketId.toString().substring(0, 20), "hash-" + ticketId, citizenId);
        when(fileStorage.store(eq(ticketId), any(InputStream.class)))
                .thenReturn(new StoredFile("tickets/" + ticketId + "/proof", 3));
        var identity = new AuthenticatedIdentity("subject", citizenId, "Owner", null, ModuleRole.CITIZEN);

        var response = uploadService.upload(ticketId,
                new AttachmentUploadRequest(MessageVisibility.PUBLIC),
                new MockMultipartFile[]{new MockMultipartFile(
                        "attachments", "proof.pdf", "application/pdf", new byte[]{1, 2, 3})}, identity);

        Attachment stored = attachmentRepository.findById(response.getFirst().getId()).orElseThrow();
        assertThat(stored.getTicket().getId()).isEqualTo(ticketId);
        assertThat(stored.getVisibility()).isEqualTo(MessageVisibility.PUBLIC);
        assertThat(stored.getUploadedByType()).isEqualTo(ActorType.CITIZEN);
        assertThat(stored.getUploadedById()).isEqualTo(citizenId.toString());
        assertThat(stored.getStorageKey()).isEqualTo("tickets/" + ticketId + "/proof");
        assertThat(response.getFirst().getDownloadUrl())
                .isEqualTo("http://localhost:8080/api/attachments/" + stored.getId() + "/content");
        assertThat(database.queryForObject("SELECT current_status FROM tickets WHERE id=?", String.class, ticketId))
                .isEqualTo("CLOSED");
    }
}

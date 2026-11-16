package com.reclamos.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;
import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TicketLabelId implements Serializable {
    @Column(name = "ticket_id") private UUID ticketId;
    @Column(name = "label_id") private UUID labelId;
}
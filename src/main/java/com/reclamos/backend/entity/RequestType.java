package com.reclamos.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "request_types",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_request_type_code",
                        columnNames = "code"
                )
        }
)
@Data
@NoArgsConstructor
public class RequestType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "subcategory_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_request_type_subcategory")
    )
    private Subcategory subcategory;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "ticket_type", nullable = false, length = 30)
    private TicketType ticketType;

    @Column(name = "responsible_area_id", nullable = false, length = 100)
    private String responsibleAreaId;

    @Enumerated(EnumType.STRING)
    @Column(name = "initial_priority", nullable = false, length = 20)
    private PriorityFactor initialPriority;

    @Enumerated(EnumType.STRING)
    @Column(name = "initial_risk", nullable = false, length = 20)
    private RiskFactor initialRisk;

    @Column(name = "allows_anonymous", nullable = false)
    private boolean allowsAnonymous;

    @Column(name = "requires_location", nullable = false)
    private boolean requiresLocation;

    @Column(nullable = false)
    private boolean active = true;
}
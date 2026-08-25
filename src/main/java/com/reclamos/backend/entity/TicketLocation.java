package com.reclamos.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Data
@Table(name = "ticket_locations",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_ticket_location_ticket",
                        columnNames = "ticket_id"
                )
        }
)
@Check(
        constraints = """
                (latitude IS NULL OR latitude BETWEEN -90 AND 90)
                AND
                (longitude IS NULL OR longitude BETWEEN -180 AND 180)
                """
)
@NoArgsConstructor
public class TicketLocation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "ticket_id",
            nullable = false,
            unique = true,
            foreignKey = @ForeignKey(
                    name = "fk_ticket_location_ticket"
            )
    )
    private Ticket ticket;

    @Column(name = "address_line", length = 300)
    private String addressLine;

    @Column(length = 150)
    private String street;

    @Column(name = "street_number", length = 30)
    private String streetNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "neighborhood_id",
            foreignKey = @ForeignKey(
                    name = "fk_ticket_location_neighborhood"
            )
    )
    private Neighborhood neighborhood;

    @Column(name = "postal_code", length = 20)
    private String postalCode;

    @Column(
            precision = 9,
            scale = 6
    )
    private BigDecimal latitude; // Cambiar a PostGIS

    @Column(
            precision = 9,
            scale = 6
    )
    private BigDecimal longitude; // Cambiar a PostGIS

    @CreationTimestamp
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    @UpdateTimestamp
    @Column(
            name = "updated_at",
            nullable = false
    )
    private Instant updatedAt;
}

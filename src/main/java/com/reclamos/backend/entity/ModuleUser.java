package com.reclamos.backend.entity;

import com.reclamos.backend.identity.ModuleRole;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Data
@Entity
@NoArgsConstructor
@Table(name = "module_users",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_module_user_citizen_id",
                columnNames = "citizen_id"),
        indexes = @Index(name = "idx_module_user_role_area", columnList = "role,area_id"))
public class ModuleUser {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "citizen_id", nullable = false, updatable = false, unique = true)
    private UUID citizenId;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(length = 254)
    private String email;

    @Column(length = 50)
    private String phone;

    @Column(name = "profile_image_url", length = 500)
    private String profileImageUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ModuleRole role;

    @Column(name = "area_id", length = 100)
    private String areaId;

    @Column(name = "preferred_notification_channel", length = 30)
    private String preferredNotificationChannel;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "last_synced_at", nullable = false)
    private Instant lastSyncedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void validateAuthorization() {
        if (citizenId == null) {
            throw new IllegalArgumentException("El citizenId del usuario es obligatorio");
        }
        if (firstName == null || firstName.isBlank() || lastName == null || lastName.isBlank()) {
            throw new IllegalArgumentException("El nombre y apellido del usuario son obligatorios");
        }
        if (role == null) {
            throw new IllegalArgumentException("El role del usuario es obligatorio");
        }
        if (role == ModuleRole.AREA_RESPONSIBLE && areaId == null) {
            throw new IllegalArgumentException("AREA_RESPONSIBLE requiere un areaId");
        }
        if (areaId != null && !areaId.matches("M[1-9]")) {
            throw new IllegalArgumentException("El areaId debe pertenecer al namespace M1..M9");
        }
    }
}

package com.reclamos.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

// La unicidad de name es case-insensitive (uk_category_name_ci, V36 — un
// índice único funcional sobre LOWER(name), no una @UniqueConstraint de
// tabla: Postgres no admite UNIQUE de tabla sobre una expresión). No se
// declara acá porque @UniqueConstraint sólo puede referenciar columnas
// literales, no expresiones; spring.jpa.hibernate.ddl-auto=validate no
// exige que la anotación coincida con el índice real.
@Entity
@Table(name = "categories")
@Data
@NoArgsConstructor
public class Category {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}

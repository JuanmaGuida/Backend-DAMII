package com.reclamos.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

// Unicidad case-insensitive de (category_id, name) vía
// uk_subcategory_category_name_ci (V36, índice único funcional sobre
// LOWER(name)) — no representable como @UniqueConstraint de tabla. Ver el
// comentario equivalente en Category.
@Entity
@Table(name = "subcategories")
@Data
@NoArgsConstructor
public class Subcategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "category_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_subcategory_category")
    )
    private Category category;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private boolean active = true;
}

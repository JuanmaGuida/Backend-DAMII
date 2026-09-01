package com.reclamos.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Check;

import java.util.UUID;

@Entity
@Table(name="neighborhood")
@Data
@Check(constraints = "population >= 0")
public class Neighborhood {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 150)
    private String name;

    @Column(nullable = false)
    private Integer population;
}

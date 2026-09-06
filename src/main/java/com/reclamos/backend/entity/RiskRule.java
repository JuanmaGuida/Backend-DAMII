package com.reclamos.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;

@Entity
@Table(name = "risk_rules", indexes = @Index(
        name = "idx_risk_rule_form_field_active",
        columnList = "form_field_id,active"
))
@Data
@NoArgsConstructor
public class RiskRule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "form_field_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_risk_rule_form_field"))
    private FormField formField;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RiskOperator operator;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "expected_value", columnDefinition = "jsonb")
    private Object expectedValue;

    @Column(name = "value_from")
    private BigDecimal valueFrom;

    @Column(name = "value_to")
    private BigDecimal valueTo;

    @Column(name = "risk_increment", nullable = false)
    private short riskIncrement;

    @Column(nullable = false)
    private boolean active = true;
}

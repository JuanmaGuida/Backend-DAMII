package com.reclamos.backend.dto.request;

import com.reclamos.backend.entity.Priority;
import com.reclamos.backend.entity.Risk;
import com.reclamos.backend.entity.TicketType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QA (FAIL de "validaciones de datos y relaciones jerárquicas del
 * catálogo"): affectedPopulationFactor sólo tenía piso (@DecimalMin(0)), sin
 * techo — un valor > 1 pasaba Bean Validation y recién lo frenaba
 * ck_request_type_affected_population_factor en la base, como un 500 en vez
 * de un 400 controlado. Entidades V1.49 §7.3 es explícito: "Valor 0..1".
 * Este test valida la anotación directamente contra un {@link Validator},
 * sin pasar por el service (que ya asume que el DTO llegó válido).
 *
 * <p>DDA2-190 (QA FAIL): mismo patrón para responsibleAreaId — sólo se
 * validaba obligatoriedad/longitud, sin exigir el namespace M1..M9
 * (Decisiones y adaptaciones M2 §1: "Los identificadores técnicos de
 * módulo/área usan un único namespace M1…M9 acordado para el proyecto...
 * responsibleAreaId... usan estos códigos y no nombres humanos libres"), así
 * que un valor fuera de rango pasaba Bean Validation y recién lo frenaba
 * ck_request_type_responsible_area_namespace en la base, como un 409 en vez
 * de un 400 controlado.
 */
class RequestTypeAdminRequestValidationTest {
    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void rejectsAffectedPopulationFactorGreaterThanOne() {
        Set<ConstraintViolation<RequestTypeAdminRequest>> violations =
                validator.validate(request(BigDecimal.valueOf(1.5)));

        assertTrue(violations.stream()
                .anyMatch(violation -> "affectedPopulationFactor".equals(
                        violation.getPropertyPath().toString())));
    }

    @Test
    void acceptsAffectedPopulationFactorEqualToOne() {
        Set<ConstraintViolation<RequestTypeAdminRequest>> violations =
                validator.validate(request(BigDecimal.ONE));

        assertFalse(violations.stream()
                .anyMatch(violation -> "affectedPopulationFactor".equals(
                        violation.getPropertyPath().toString())));
    }

    @Test
    void acceptsAffectedPopulationFactorEqualToZero() {
        Set<ConstraintViolation<RequestTypeAdminRequest>> violations =
                validator.validate(request(BigDecimal.ZERO));

        assertFalse(violations.stream()
                .anyMatch(violation -> "affectedPopulationFactor".equals(
                        violation.getPropertyPath().toString())));
    }

    @Test
    void rejectsNegativeAffectedPopulationFactor() {
        Set<ConstraintViolation<RequestTypeAdminRequest>> violations =
                validator.validate(request(BigDecimal.valueOf(-0.1)));

        assertTrue(violations.stream()
                .anyMatch(violation -> "affectedPopulationFactor".equals(
                        violation.getPropertyPath().toString())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"M1", "M2", "M3", "M4", "M5", "M6", "M7", "M8", "M9"})
    void acceptsResponsibleAreaIdWithinM1ToM9Namespace(String responsibleAreaId) {
        Set<ConstraintViolation<RequestTypeAdminRequest>> violations =
                validator.validate(request(responsibleAreaId));

        assertFalse(violations.stream()
                .anyMatch(violation -> "responsibleAreaId".equals(
                        violation.getPropertyPath().toString())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"area-1", "M0", "M10", "m6", "Obras Viales"})
    void rejectsResponsibleAreaIdOutsideTheM1ToM9Namespace(String responsibleAreaId) {
        Set<ConstraintViolation<RequestTypeAdminRequest>> violations =
                validator.validate(request(responsibleAreaId));

        assertTrue(violations.stream()
                .anyMatch(violation -> "responsibleAreaId".equals(
                        violation.getPropertyPath().toString())));
    }

    private RequestTypeAdminRequest request(BigDecimal affectedPopulationFactor) {
        RequestTypeAdminRequest request = new RequestTypeAdminRequest();
        request.setSubcategoryId(1L);
        request.setCode("BACHE");
        request.setName("Informar bache");
        request.setDescription("desc");
        request.setTicketType(TicketType.COMPLAINT);
        request.setResponsibleAreaId("M3");
        request.setMinimumPriority(Priority.LOW);
        request.setBaseRisk(Risk.LOW);
        request.setAffectedPopulationFactor(affectedPopulationFactor);
        return request;
    }

    private RequestTypeAdminRequest request(String responsibleAreaId) {
        RequestTypeAdminRequest request = new RequestTypeAdminRequest();
        request.setSubcategoryId(1L);
        request.setCode("BACHE");
        request.setName("Informar bache");
        request.setDescription("desc");
        request.setTicketType(TicketType.COMPLAINT);
        request.setResponsibleAreaId(responsibleAreaId);
        request.setMinimumPriority(Priority.LOW);
        request.setBaseRisk(Risk.LOW);
        request.setAffectedPopulationFactor(BigDecimal.valueOf(0.1));
        return request;
    }
}

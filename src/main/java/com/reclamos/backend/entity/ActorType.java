package com.reclamos.backend.entity;

/**
 * ActorType (Entidades §21 / Eventos §5.2) tiene 6 valores. Los CHECK
 * constraints de la base se validan en
 * V8__fix_actor_type_check_constraints.sql.
 * <p>
 * Semántica (Guía funcional complementaria M2): para acciones
 * realizadas directamente dentro de M2, el valor refleja la CAPACIDAD
 * efectiva con la que se actuó (ej. un agente que actúa sobre su propio
 * ticket usando funciones de ciudadano se registra como CITIZEN).
 * EXTERNAL_USER representa a una persona que actúa desde otro módulo vía
 * integración (independientemente de su rol en ese módulo). SYSTEM
 * representa un proceso automático (actorId normalmente null).
 */
public enum ActorType {
    CITIZEN,
    AGENT,
    AREA_RESPONSIBLE,
    ADMIN,
    EXTERNAL_USER,
    SYSTEM
}

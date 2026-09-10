package com.reclamos.backend.entity;

/**
 * Revisión post-actualización de documentación (Entidades V1.49 §21 /
 * Eventos V1.69 §5.2): este enum había sido renombrado a
 * CITIZEN/AGENT/AREA_USER/SYSTEM en una corrección anterior, asumiendo que
 * los CHECK constraints ya desplegados en V1__create_initial_schema.sql
 * (que sólo permitían esos 4 valores) reflejaban el contrato correcto y que
 * el enum original (con AREA_RESPONSIBLE/ADMIN) estaba mal.
 * <p>
 * Esa asunción era incorrecta: la documentación actualizada confirma que
 * ActorType tiene 6 valores — CITIZEN, AGENT, AREA_RESPONSIBLE, ADMIN,
 * EXTERNAL_USER, SYSTEM — y que era el CHECK constraint de la base el que
 * estaba desactualizado, no el enum original. Se revierte acá el
 * renombrado (se restauran AREA_RESPONSIBLE y ADMIN) y se agregan
 * EXTERNAL_USER y SYSTEM. Los CHECK constraints se corrigen aparte con una
 * migración nueva (V8__fix_actor_type_check_constraints.sql) en lugar de
 * tocar V1, que puede ya estar aplicada.
 * <p>
 * Semántica (Guía funcional complementaria M2 V1.09): para acciones
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

-- Chat de tickets (POST/PATCH/DELETE /tickets/{id}/messages): agrega
-- updated_at para poder distinguir un mensaje editado de uno nunca tocado.
-- Nullable en filas existentes (nunca editadas); a partir de ahora la
-- entidad lo setea siempre (igual que created_at) vía @UpdateTimestamp, así
-- que en un mensaje nuevo queda igual a created_at y sólo difiere tras un
-- PATCH real.
ALTER TABLE ticket_messages
    ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE;

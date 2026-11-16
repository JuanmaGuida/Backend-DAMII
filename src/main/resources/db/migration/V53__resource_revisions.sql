-- Revisions are database-owned, including writes from Core and native SQL.
ALTER TABLE tickets ADD COLUMN resource_version BIGINT NOT NULL DEFAULT 0 CHECK (resource_version >= 0);
ALTER TABLE ticket_messages ADD COLUMN resource_version BIGINT NOT NULL DEFAULT 0 CHECK (resource_version >= 0);

CREATE FUNCTION advance_resource_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.resource_version := OLD.resource_version + 1;
    RETURN NEW;
END;
$$;
CREATE TRIGGER tickets_resource_version BEFORE UPDATE ON tickets
    FOR EACH ROW EXECUTE FUNCTION advance_resource_version();
CREATE TRIGGER messages_resource_version BEFORE UPDATE ON ticket_messages
    FOR EACH ROW EXECUTE FUNCTION advance_resource_version();

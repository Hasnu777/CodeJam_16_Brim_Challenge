-- Runs after Hibernate creates/updates the schema. Statements are separated by ^^^ (see application.yml)
-- because the function body contains semicolons. Every statement here must be idempotent.

-- Append-only audit log: reject UPDATE and DELETE at the database level.
-- TRUNCATE (used only by the demo reset) does not fire row-level triggers.
CREATE OR REPLACE FUNCTION audit_events_append_only() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'audit_events is append-only (% rejected)', TG_OP;
END;
$$ LANGUAGE plpgsql
^^^
DROP TRIGGER IF EXISTS audit_events_no_update ON audit_events
^^^
CREATE TRIGGER audit_events_no_update BEFORE UPDATE OR DELETE ON audit_events
  FOR EACH ROW EXECUTE FUNCTION audit_events_append_only()
^^^
-- Invoice ids for new bills continue the dataset sequence (AP-00427 onward).
CREATE SEQUENCE IF NOT EXISTS invoice_number_seq START 427
^^^
CREATE SEQUENCE IF NOT EXISTS payment_number_seq START 416
^^^
CREATE SEQUENCE IF NOT EXISTS vendor_number_seq START 57
^^^
CREATE SEQUENCE IF NOT EXISTS bank_change_number_seq START 10
^^^
CREATE SEQUENCE IF NOT EXISTS batch_number_seq START 1

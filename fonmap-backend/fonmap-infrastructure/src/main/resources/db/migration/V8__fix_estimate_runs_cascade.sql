-- =====================================================================================
-- V8__fix_estimate_runs_cascade.sql
-- Snapshot silindiğinde (KAP raporu silme operasyonları) estimate_runs ve ilişkili
-- estimate_details kayıtlarının foreign key kısıtına takılmadan cascade olarak silinmesini sağlar.
-- =====================================================================================

ALTER TABLE estimate_runs
    DROP CONSTRAINT IF EXISTS estimate_runs_snapshot_id_fkey;

ALTER TABLE estimate_runs
    ADD CONSTRAINT estimate_runs_snapshot_id_fkey
    FOREIGN KEY (snapshot_id)
    REFERENCES fund_snapshots(id)
    ON DELETE CASCADE;

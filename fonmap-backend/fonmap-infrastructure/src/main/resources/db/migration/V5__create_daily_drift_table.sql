-- V5__create_daily_drift_table.sql
-- Sabah 09:50 kalibre edilen portföy ağırlık kayması (Weight Drift) verilerini
-- kalıcılaştırmak ve seans içi canlı hesaplamada dondurulmuş ağırlık olarak kullanmak için tablo:

CREATE TABLE daily_holding_drifts (
    id BIGSERIAL PRIMARY KEY,
    fund_id UUID NOT NULL REFERENCES funds(id) ON DELETE CASCADE,
    snapshot_id UUID NOT NULL REFERENCES fund_snapshots(id) ON DELETE CASCADE,
    holding_id UUID NOT NULL REFERENCES holdings(id) ON DELETE CASCADE,
    drift_date DATE NOT NULL,
    initial_weight DECIMAL(8,6) NOT NULL,
    growth_factor DECIMAL(10,6) NOT NULL,
    effective_weight DECIMAL(8,6) NOT NULL,
    previous_close DECIMAL(18,6),
    created_at TIMESTAMP NOT NULL,
    UNIQUE (holding_id, drift_date)
);

CREATE INDEX idx_daily_drift_fund_date ON daily_holding_drifts (fund_id, drift_date);
CREATE INDEX idx_daily_drift_snapshot_date ON daily_holding_drifts (snapshot_id, drift_date);

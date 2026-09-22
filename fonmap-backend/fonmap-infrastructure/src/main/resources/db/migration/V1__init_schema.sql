-- V1__init_schema.sql
-- Proje Kılavuzu baz alınarak hazırlanmış Fonmap Veritabanı Şeması

-- ==========================================
-- 1. BAĞIMSIZ TABLOLAR (Hiçbir yere FK vermeyenler)
-- ==========================================

CREATE TABLE admin_users (
    id UUID PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL,
    last_login_at TIMESTAMP,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);

CREATE TABLE audit_logs (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL,
    action VARCHAR(100) NOT NULL,
    entity_name VARCHAR(100) NOT NULL,
    entity_id VARCHAR(100) NOT NULL,
    old_values JSONB,
    new_values JSONB,
    ip_address VARCHAR(45) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE market_holidays (
    id BIGSERIAL PRIMARY KEY,
    holiday_date DATE NOT NULL UNIQUE,
    description VARCHAR(100) NOT NULL,
    is_half_day BOOLEAN NOT NULL DEFAULT false
);

CREATE TABLE instruments (
    id BIGSERIAL PRIMARY KEY,
    ticker VARCHAR(20) UNIQUE,
    isin_code VARCHAR(12) UNIQUE,
    title VARCHAR(255) NOT NULL,
    asset_class VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);

CREATE TABLE funds (
    id UUID PRIMARY KEY,
    code VARCHAR(10) NOT NULL UNIQUE,
    title VARCHAR(255) NOT NULL,
    manager VARCHAR(255) NOT NULL,
    annual_fee_ratio DECIMAL(6,4) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT true,
    display_order INT NOT NULL DEFAULT 999,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);

-- ==========================================
-- 2. BİRİNCİ SEVİYE BAĞIMLI TABLOLAR (1 FK içerenler)
-- ==========================================

CREATE TABLE instrument_aliases (
    id BIGSERIAL PRIMARY KEY,
    raw_name VARCHAR(255) NOT NULL UNIQUE,
    instrument_id BIGINT REFERENCES instruments(id) ON DELETE SET NULL,
    is_approved BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);

CREATE TABLE fund_reports (
    id UUID PRIMARY KEY,
    fund_id UUID NOT NULL REFERENCES funds(id) ON DELETE CASCADE,
    report_date DATE NOT NULL,
    publish_date TIMESTAMP NOT NULL,
    pdf_url VARCHAR(500) NOT NULL,
    pdf_sha256 VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    UNIQUE (fund_id, report_date)
);

CREATE TABLE price_quotes (
    id BIGSERIAL PRIMARY KEY,
    instrument_id BIGINT NOT NULL REFERENCES instruments(id) ON DELETE CASCADE,
    current_price DECIMAL(18,6) NOT NULL,
    previous_close DECIMAL(18,6) NOT NULL,
    daily_change_ratio DECIMAL(8,6) NOT NULL,
    quote_time TIMESTAMP NOT NULL,
    source VARCHAR(50) NOT NULL
);
-- Performans notu: Composite index
CREATE INDEX idx_price_instrument_time ON price_quotes (instrument_id, quote_time DESC);

CREATE TABLE reconciliation_results (
    id BIGSERIAL PRIMARY KEY,
    fund_id UUID NOT NULL REFERENCES funds(id) ON DELETE CASCADE,
    date DATE NOT NULL,
    predicted_return DECIMAL(8,6) NOT NULL,
    actual_return DECIMAL(8,6),
    error_diff DECIMAL(8,6),
    absolute_error DECIMAL(8,6),
    is_verified BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    UNIQUE (fund_id, date)
);

-- ==========================================
-- 3. İKİNCİ SEVİYE BAĞIMLI TABLOLAR (2+ FK içerenler)
-- ==========================================

CREATE TABLE fund_snapshots (
    id UUID PRIMARY KEY,
    fund_id UUID NOT NULL REFERENCES funds(id) ON DELETE CASCADE,
    report_id UUID NOT NULL REFERENCES fund_reports(id) ON DELETE CASCADE UNIQUE,
    snapshot_date DATE NOT NULL,
    total_net_asset_value DECIMAL(18,2) NOT NULL,
    stock_ratio DECIMAL(6,4) NOT NULL,
    viop_cash_ratio DECIMAL(6,4) NOT NULL,
    is_suspicious BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);

CREATE TABLE holdings (
    id UUID PRIMARY KEY,
    snapshot_id UUID NOT NULL REFERENCES fund_snapshots(id) ON DELETE CASCADE,
    instrument_id BIGINT NOT NULL REFERENCES instruments(id) ON DELETE RESTRICT,
    nominal_amount DECIMAL(18,4),
    unit_cost DECIMAL(18,6),
    report_price DECIMAL(18,6),
    total_value DECIMAL(18,2) NOT NULL,
    weight_ratio DECIMAL(6,4) NOT NULL,
    is_short BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);
CREATE INDEX idx_holding_snapshot ON holdings (snapshot_id);

CREATE TABLE estimate_runs (
    id UUID PRIMARY KEY,
    fund_id UUID NOT NULL REFERENCES funds(id) ON DELETE CASCADE,
    snapshot_id UUID NOT NULL REFERENCES fund_snapshots(id) ON DELETE RESTRICT,
    estimated_return DECIMAL(8,6) NOT NULL,
    coverage_ratio DECIMAL(6,4) NOT NULL,
    confidence_level VARCHAR(20) NOT NULL,
    calculated_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);
CREATE INDEX idx_estimate_fund_time ON estimate_runs (fund_id, calculated_at DESC);

CREATE TABLE estimate_details (
    id BIGSERIAL PRIMARY KEY,
    estimate_run_id UUID NOT NULL REFERENCES estimate_runs(id) ON DELETE CASCADE,
    instrument_id BIGINT NOT NULL REFERENCES instruments(id) ON DELETE RESTRICT,
    effective_weight DECIMAL(6,4) NOT NULL,
    asset_return DECIMAL(8,6) NOT NULL,
    weighted_contribution DECIMAL(8,6) NOT NULL
);
CREATE INDEX idx_estimate_detail_run ON estimate_details (estimate_run_id);

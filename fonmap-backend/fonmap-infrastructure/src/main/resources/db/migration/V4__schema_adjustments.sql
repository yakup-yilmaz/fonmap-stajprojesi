-- V4__schema_adjustments.sql
-- Veritabanı şema güncellemeleri ve alan optimizasyonları:

-- 1. instruments tablosuna para birimi (currency) sütunu eklenir
ALTER TABLE instruments ADD COLUMN IF NOT EXISTS currency VARCHAR(3) NOT NULL DEFAULT 'TRY';

-- 2. instrument_aliases tablosuna benzerlik güven skoru (match_confidence) eklenir
ALTER TABLE instrument_aliases ADD COLUMN IF NOT EXISTS match_confidence DECIMAL(3,2);

-- 3. market_holidays tablosundan gereksiz description sütunu kaldırılır
ALTER TABLE market_holidays DROP COLUMN IF EXISTS description;

-- =====================================================================================
-- V9__seed_showcase_funds.sql
-- =====================================================================================
-- Demo & Showroom Fonları: TTE (İş Portföy Teknoloji), KHA (Pardus Portföy Hisse) ve
-- ICZ (Ak Portföy Teknoloji) fonlarını ve güncel portföy snapshot/holding kayıtlarını tohumlar.
-- Böylece sistem sıfırdan ayağa kalktığında harici PDF gerekmeksizin vitrin dolu ve canlı başlar.
-- =====================================================================================

-- 1. FON TANIMLARI
INSERT INTO funds (id, code, title, manager, annual_fee_ratio, is_active, display_order, created_at)
VALUES 
    ('11111111-1111-1111-1111-111111111111', 'TTE', 'İş Portföy BİST Teknoloji Ağırlıklı Sınırlandırılmış Endeksi Hisse Senedi Fonu (Hisse Senedi Yoğun Fon)', 'İş Portföy Yönetimi A.Ş.', 0.0200, true, 1, CURRENT_TIMESTAMP),
    ('22222222-2222-2222-2222-222222222222', 'KHA', 'Pardus Portföy Hisse Senedi Fonu (Hisse Senedi Yoğun Fon)', 'Pardus Portföy Yönetimi A.Ş.', 0.0195, true, 2, CURRENT_TIMESTAMP),
    ('33333333-3333-3333-3333-333333333333', 'ICZ', 'Ak Portföy Teknoloji Şirketleri Hisse Senedi Fonu (Hisse Senedi Yoğun Fon)', 'Ak Portföy Yönetimi A.Ş.', 0.0250, true, 3, CURRENT_TIMESTAMP)
ON CONFLICT (code) DO UPDATE SET 
    title = EXCLUDED.title,
    manager = EXCLUDED.manager,
    annual_fee_ratio = EXCLUDED.annual_fee_ratio,
    is_active = true,
    display_order = EXCLUDED.display_order;

-- Yalnızca bu 3 vitrin fonunun aktif kalmasını garanti altına al (Varsa diğer eski fonları pasife çek)
UPDATE funds SET is_active = false WHERE code NOT IN ('TTE', 'KHA', 'ICZ');

-- 2. ENSTRÜMANLAR (Instruments)
INSERT INTO instruments (ticker, isin_code, title, asset_class, created_at)
VALUES 
    ('LOGO', 'TRALOGO00012', 'Logo Yazılım Sanayi ve Ticaret A.Ş.', 'EQUITY', CURRENT_TIMESTAMP),
    ('ASELS', 'TRAASELS91H2', 'Aselsan Elektronik Sanayi ve Ticaret A.Ş.', 'EQUITY', CURRENT_TIMESTAMP),
    ('MIATK', 'TREMIAT00012', 'Mia Teknoloji A.Ş.', 'EQUITY', CURRENT_TIMESTAMP),
    ('NETAS', 'TRANETAS91H6', 'Netaş Telekomünikasyon A.Ş.', 'EQUITY', CURRENT_TIMESTAMP),
    ('ARDYZ', 'TREARDY00015', 'Ardyz Bilişim Teknolojileri A.Ş.', 'EQUITY', CURRENT_TIMESTAMP),
    ('VBTYZ', 'TREVBTY00018', 'VBT Yazılım A.Ş.', 'EQUITY', CURRENT_TIMESTAMP),
    ('KFEIN', 'TREKFEI00019', 'Kafein Yazılım Hizmetleri A.Ş.', 'EQUITY', CURRENT_TIMESTAMP),
    ('PAPIL', 'TREPAPI00013', 'Papilon Savunma Güvenlik Sistemleri A.Ş.', 'EQUITY', CURRENT_TIMESTAMP),
    ('INDES', 'TRAINDES91Q1', 'İndeks Bilgisayar Sistemleri Müh. San. ve Tic. A.Ş.', 'EQUITY', CURRENT_TIMESTAMP),
    ('ALCTL', 'TRAALCTL91F7', 'Alcatel Lucent Teletaş Telekomünikasyon A.Ş.', 'EQUITY', CURRENT_TIMESTAMP),
    ('SDTTR', 'TRESDTT00015', 'SDT Uzay ve Savunma Teknolojileri A.Ş.', 'EQUITY', CURRENT_TIMESTAMP),
    ('HTTBT', 'TREHTTB00011', 'Hitit Bilgisayar Hizmetleri A.Ş.', 'EQUITY', CURRENT_TIMESTAMP),
    ('BIMAS', 'TREBIMM00018', 'BİM Birleşik Mağazalar A.Ş.', 'EQUITY', CURRENT_TIMESTAMP),
    ('THYAO', 'TRATHYAO91M5', 'Türk Hava Yolları A.O.', 'EQUITY', CURRENT_TIMESTAMP),
    ('MAVI', 'TREMAVI00037', 'Mavi Giyim Sanayi ve Ticaret A.Ş.', 'EQUITY', CURRENT_TIMESTAMP),
    ('KCAER', 'TREKCAE00014', 'Kocaer Çelik Sanayi ve Ticaret A.Ş.', 'EQUITY', CURRENT_TIMESTAMP),
    ('BRISA', 'TRABRISA91E3', 'Brisa Bridgestone Sabancı Lastik San. ve Tic. A.Ş.', 'EQUITY', CURRENT_TIMESTAMP),
    ('CWENE', 'TRECWEN00014', 'CW Enerji Mühendislik Tic. ve San. A.Ş.', 'EQUITY', CURRENT_TIMESTAMP),
    ('ALFAS', 'TREALFA00016', 'Alfa Solar Enerji Sanayi ve Ticaret A.Ş.', 'EQUITY', CURRENT_TIMESTAMP),
    ('GESAN', 'TREGESN00011', 'Girişim Elektrik Sanayi Taahhüt ve Tic. A.Ş.', 'EQUITY', CURRENT_TIMESTAMP),
    ('OYAKC', 'TRAOYAKC91X7', 'Oyak Çimento Fabrikaları A.Ş.', 'EQUITY', CURRENT_TIMESTAMP),
    ('TUPRS', 'TRATUPRS91E8', 'Tüpraş Türkiye Petrol Rafinerileri A.Ş.', 'EQUITY', CURRENT_TIMESTAMP),
    ('KORDS', 'TRAKORDS91A5', 'Kordsa Teknik Tekstil A.Ş.', 'EQUITY', CURRENT_TIMESTAMP),
    ('REEDR', 'TREREED00018', 'Reeder Teknoloji Sanayi ve Ticaret A.Ş.', 'EQUITY', CURRENT_TIMESTAMP),
    ('PATEK', 'TREPATK00014', 'Pasifik Donanım ve Yazılım Bilgi Teknolojileri A.Ş.', 'EQUITY', CURRENT_TIMESTAMP),
    ('FONET', 'TREFONE00013', 'Fonet Bilgi Teknolojileri A.Ş.', 'EQUITY', CURRENT_TIMESTAMP),
    ('TREPO', 'TRTREPO00001', 'Takasbank Ters Repo / Para Piyasası', 'DEPOSIT', CURRENT_TIMESTAMP),
    ('VIOP_NAKIT', 'TRVIOPN00001', 'VİOP Nakit Teminatı (Takasbank)', 'DEPOSIT', CURRENT_TIMESTAMP)
ON CONFLICT (ticker) DO NOTHING;

-- 3. KAP RAPORLARI (Fund Reports)
INSERT INTO fund_reports (id, fund_id, report_date, publish_date, pdf_url, pdf_sha256, status, created_at)
VALUES
    ('11111111-aaaa-1111-1111-111111111111', '11111111-1111-1111-1111-111111111111', '2026-09-30', CURRENT_TIMESTAMP, 'https://www.kap.org.tr/tr/Bildirim/TTE_2026_09', 'sha256-seed-tte-2026-09-showcase', 'PARSED', CURRENT_TIMESTAMP),
    ('22222222-aaaa-2222-2222-222222222222', '22222222-2222-2222-2222-222222222222', '2026-08-31', CURRENT_TIMESTAMP, 'https://www.kap.org.tr/tr/Bildirim/KHA_2026_08', 'sha256-seed-kha-2026-08-showcase', 'PARSED', CURRENT_TIMESTAMP),
    ('33333333-aaaa-3333-3333-333333333333', '33333333-3333-3333-3333-333333333333', '2026-09-30', CURRENT_TIMESTAMP, 'https://www.kap.org.tr/tr/Bildirim/ICZ_2026_09', 'sha256-seed-icz-2026-09-showcase', 'PARSED', CURRENT_TIMESTAMP)
ON CONFLICT (pdf_sha256) DO NOTHING;

-- 4. PORTFÖY SNAPSHOTLARI (Fund Snapshots)
INSERT INTO fund_snapshots (id, fund_id, report_id, snapshot_date, total_net_asset_value, stock_ratio, viop_cash_ratio, is_suspicious, created_at)
VALUES
    ('11111111-bbbb-1111-1111-111111111111', '11111111-1111-1111-1111-111111111111', '11111111-aaaa-1111-1111-111111111111', '2026-09-30', 2199694018.44, 0.9650, 0.0000, false, CURRENT_TIMESTAMP),
    ('22222222-bbbb-2222-2222-222222222222', '22222222-2222-2222-2222-222222222222', '22222222-aaaa-2222-2222-222222222222', '2026-08-31', 8516865701.54, 0.9420, 0.0000, false, CURRENT_TIMESTAMP),
    ('33333333-bbbb-3333-3333-333333333333', '33333333-3333-3333-3333-333333333333', '33333333-aaaa-3333-3333-333333333333', '2026-09-30', 845889734.08, 0.8724, 0.0119, false, CURRENT_TIMESTAMP)
ON CONFLICT (report_id) DO NOTHING;

-- 5. PORTFÖY VARLIKLARI (Holdings)

-- 5.1 TTE Holdings (İş Portföy Teknoloji)
INSERT INTO holdings (id, snapshot_id, instrument_id, nominal_amount, unit_cost, report_price, total_value, weight_ratio, is_short, created_at)
SELECT gen_random_uuid(), '11111111-bbbb-1111-1111-111111111111', i.id, h.nom, h.cost, h.r_price, h.tot, h.w, false, CURRENT_TIMESTAMP
FROM (VALUES
    ('LOGO', 1250000.0, 92.50, 98.40, 318955632.67, 0.1450),
    ('ASELS', 2850000.0, 56.40, 61.20, 334353490.80, 0.1520),
    ('MIATK', 3400000.0, 68.20, 74.50, 263963282.21, 0.1200),
    ('NETAS', 4100000.0, 45.10, 48.70, 208970931.75, 0.0950),
    ('REEDR', 5200000.0, 32.40, 35.10, 178175215.49, 0.0810),
    ('KFEIN', 1100000.0, 142.00, 153.20, 171576133.44, 0.0780),
    ('ARDYZ', 2900000.0, 44.50, 49.30, 140780417.18, 0.0640),
    ('PATEK', 850000.0, 134.00, 145.00, 123182865.03, 0.0560),
    ('FONET', 3100000.0, 28.10, 30.50, 92387148.77, 0.0420),
    ('VBTYZ', 950000.0, 81.20, 87.60, 83588372.70, 0.0380),
    ('TREPO', 1.0, 1.0, 1.0, 76989290.65, 0.0350)
) AS h(ticker, nom, cost, r_price, tot, w)
JOIN instruments i ON i.ticker = h.ticker;

-- 5.2 KHA Holdings (Pardus Portföy Hisse)
INSERT INTO holdings (id, snapshot_id, instrument_id, nominal_amount, unit_cost, report_price, total_value, weight_ratio, is_short, created_at)
SELECT gen_random_uuid(), '22222222-bbbb-2222-2222-222222222222', i.id, h.nom, h.cost, h.r_price, h.tot, h.w, false, CURRENT_TIMESTAMP
FROM (VALUES
    ('BIMAS', 2500000.0, 470.00, 510.00, 1209394929.62, 0.1420),
    ('THYAO', 3600000.0, 285.00, 312.00, 1090158809.80, 0.1280),
    ('MAVI', 4800000.0, 145.00, 161.00, 758000947.44, 0.0890),
    ('CWENE', 2100000.0, 280.00, 305.00, 630248061.91, 0.0740),
    ('KCAER', 9500000.0, 52.00, 58.50, 553596270.60, 0.0650),
    ('ALFAS', 5800000.0, 84.00, 92.50, 528045673.49, 0.0620),
    ('GESAN', 7200000.0, 61.00, 67.40, 476944479.29, 0.0560),
    ('BRISA', 3900000.0, 105.00, 116.00, 451393882.18, 0.0530),
    ('OYAKC', 6400000.0, 58.00, 63.80, 400292687.97, 0.0470),
    ('KORDS', 4100000.0, 74.00, 81.20, 332157762.36, 0.0390),
    ('TUPRS', 1900000.0, 162.00, 175.50, 153303582.63, 0.0180),
    ('TREPO', 1.0, 1.0, 1.0, 493978210.69, 0.0580)
) AS h(ticker, nom, cost, r_price, tot, w)
JOIN instruments i ON i.ticker = h.ticker;

-- 5.3 ICZ Holdings (Ak Portföy Teknoloji)
INSERT INTO holdings (id, snapshot_id, instrument_id, nominal_amount, unit_cost, report_price, total_value, weight_ratio, is_short, created_at)
SELECT gen_random_uuid(), '33333333-bbbb-3333-3333-333333333333', i.id, h.nom, h.cost, h.r_price, h.tot, h.w, false, CURRENT_TIMESTAMP
FROM (VALUES
    ('ASELS', 1800000.0, 58.20, 62.40, 126883460.11, 0.1500),
    ('INDES', 9500000.0, 8.40, 9.20, 101506768.09, 0.1200),
    ('LOGO', 850000.0, 94.00, 101.50, 93047870.75, 0.1100),
    ('VBTYZ', 920000.0, 82.50, 89.10, 76130076.07, 0.0900),
    ('ARDYZ', 1450000.0, 45.20, 49.00, 67671178.73, 0.0800),
    ('PAPIL', 680000.0, 85.00, 92.00, 59212281.39, 0.0700),
    ('SDTTR', 170000.0, 275.00, 298.00, 50753384.04, 0.0600),
    ('NETAS', 880000.0, 46.50, 50.10, 42294486.70, 0.0500),
    ('ALCTL', 310000.0, 118.00, 126.50, 33835589.36, 0.0400),
    ('KFEIN', 190000.0, 144.00, 155.00, 25376692.02, 0.0300),
    ('HTTBT', 320000.0, 31.00, 33.50, 21147243.35, 0.0250),
    ('TREPO', 1.0, 1.0, 1.0, 68517068.46, 0.0810),
    ('VIOP_NAKIT', 1.0, 1.0, 1.0, 10066087.84, 0.0119)
) AS h(ticker, nom, cost, r_price, tot, w)
JOIN instruments i ON i.ticker = h.ticker;

-- 6. CANLI İLK TAHMİN ÇIKTILARI (Estimate Runs & Details)
-- Sistem açıldığında /api/v1/estimates/latest anında dolu dönsün

-- 6.1 TTE Tahmini (+%2.34)
INSERT INTO estimate_runs (id, fund_id, snapshot_id, estimated_return, coverage_ratio, confidence_level, calculated_at, created_at)
VALUES ('11111111-cccc-1111-1111-111111111111', '11111111-1111-1111-1111-111111111111', '11111111-bbbb-1111-1111-111111111111', 0.023400, 0.9920, 'HIGH', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

INSERT INTO estimate_details (estimate_run_id, instrument_id, effective_weight, asset_return, weighted_contribution)
SELECT '11111111-cccc-1111-1111-111111111111', i.id, d.w, d.r, d.c
FROM (VALUES
    ('LOGO', 0.1450, 0.0320, 0.004640),
    ('MIATK', 0.1200, 0.0280, 0.003360),
    ('NETAS', 0.0950, 0.0210, 0.002000),
    ('KFEIN', 0.0780, 0.0190, 0.001480),
    ('ARDYZ', 0.0640, 0.0150, 0.000960),
    ('ASELS', 0.1520, -0.0120, -0.001824),
    ('REEDR', 0.0810, -0.0210, -0.001701),
    ('PATEK', 0.0560, -0.0150, -0.000840),
    ('FONET', 0.0420, -0.0110, -0.000462),
    ('VBTYZ', 0.0380, -0.0090, -0.000342),
    ('TREPO', 0.0350, 0.0010, 0.000035)
) AS d(ticker, w, r, c)
JOIN instruments i ON i.ticker = d.ticker;

-- 6.2 KHA Tahmini (+%1.89)
INSERT INTO estimate_runs (id, fund_id, snapshot_id, estimated_return, coverage_ratio, confidence_level, calculated_at, created_at)
VALUES ('22222222-cccc-2222-2222-222222222222', '22222222-2222-2222-2222-222222222222', '22222222-bbbb-2222-2222-222222222222', 0.018900, 0.9750, 'HIGH', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

INSERT INTO estimate_details (estimate_run_id, instrument_id, effective_weight, asset_return, weighted_contribution)
SELECT '22222222-cccc-2222-2222-222222222222', i.id, d.w, d.r, d.c
FROM (VALUES
    ('BIMAS', 0.1420, 0.0200, 0.002840),
    ('THYAO', 0.1280, 0.0175, 0.002240),
    ('MAVI', 0.0890, 0.0160, 0.001420),
    ('KCAER', 0.0650, 0.0140, 0.000910),
    ('BRISA', 0.0530, 0.0110, 0.000583),
    ('CWENE', 0.0740, -0.0230, -0.001702),
    ('ALFAS', 0.0620, -0.0190, -0.001178),
    ('GESAN', 0.0560, -0.0150, -0.000840),
    ('OYAKC', 0.0470, -0.0120, -0.000564),
    ('KORDS', 0.0390, -0.0090, -0.000351),
    ('TREPO', 0.0580, 0.0010, 0.000058)
) AS d(ticker, w, r, c)
JOIN instruments i ON i.ticker = d.ticker;

-- 6.3 ICZ Tahmini (+%1.45)
INSERT INTO estimate_runs (id, fund_id, snapshot_id, estimated_return, coverage_ratio, confidence_level, calculated_at, created_at)
VALUES ('33333333-cccc-3333-3333-333333333333', '33333333-3333-3333-3333-333333333333', '33333333-bbbb-3333-3333-333333333333', 0.014500, 0.9810, 'HIGH', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

INSERT INTO estimate_details (estimate_run_id, instrument_id, effective_weight, asset_return, weighted_contribution)
SELECT '33333333-cccc-3333-3333-333333333333', i.id, d.w, d.r, d.c
FROM (VALUES
    ('ASELS', 0.1500, 0.0220, 0.003300),
    ('INDES', 0.1200, 0.0180, 0.002160),
    ('LOGO', 0.1100, 0.0160, 0.001760),
    ('VBTYZ', 0.0900, 0.0140, 0.001260),
    ('ARDYZ', 0.0800, 0.0120, 0.000960),
    ('PAPIL', 0.0700, -0.0080, -0.000560),
    ('SDTTR', 0.0600, -0.0110, -0.000660),
    ('NETAS', 0.0500, -0.0150, -0.000750),
    ('ALCTL', 0.0400, -0.0120, -0.000480),
    ('KFEIN', 0.0300, -0.0100, -0.000300),
    ('HTTBT', 0.0250, 0.0000, 0.000000),
    ('TREPO', 0.0810, 0.0010, 0.000081),
    ('VIOP_NAKIT', 0.0119, 0.0010, 0.000012)
) AS d(ticker, w, r, c)
JOIN instruments i ON i.ticker = d.ticker;

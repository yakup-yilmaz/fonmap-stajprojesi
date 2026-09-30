-- V3__seed_market_holidays.sql
-- Borsa İstanbul Resmi Tatil ve Yarım Gün Seans Takvimi (2026 Yılı)
-- Bu kayıtlar sayesinde seans içi hesaplama motoru, cron worker'lar ve
-- mutabakat servisleri borsanın kapalı olduğu günlerde gereksiz çalışmaz.

INSERT INTO market_holidays (holiday_date, description, is_half_day)
VALUES
    ('2026-01-01', 'Yılbaşı Tatili', false),
    ('2026-03-19', 'Ramazan Bayramı Arifesi (13:00 Kapanış)', true),
    ('2026-03-20', 'Ramazan Bayramı 1. Gün', false),
    ('2026-04-23', 'Ulusal Egemenlik ve Çocuk Bayramı', false),
    ('2026-05-01', 'Emek ve Dayanışma Günü', false),
    ('2026-05-19', 'Atatürk''ü Anma, Gençlik ve Spor Bayramı', false),
    ('2026-05-26', 'Kurban Bayramı Arifesi (13:00 Kapanış)', true),
    ('2026-05-27', 'Kurban Bayramı 1. Gün', false),
    ('2026-05-28', 'Kurban Bayramı 2. Gün', false),
    ('2026-05-29', 'Kurban Bayramı 3. Gün', false),
    ('2026-07-15', 'Demokrasi ve Milli Birlik Günü', false),
    ('2026-08-30', 'Zafer Bayramı', false),
    ('2026-10-28', 'Cumhuriyet Bayramı Arifesi (13:00 Kapanış)', true),
    ('2026-10-29', 'Cumhuriyet Bayramı', false)
ON CONFLICT (holiday_date) DO NOTHING;

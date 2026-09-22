-- V2__seed_initial_funds.sql
-- Uygulama ilk ayağa kalktığında test edebilmek için temel fonları ve admin'i ekler.

-- 1. Default Admin Kullanıcısı (Şifre bcrypt ile hashlenmiş: "admin123")
INSERT INTO admin_users (id, username, password_hash, role, created_at)
VALUES (
    '550e8400-e29b-41d4-a716-446655440000', 
    'admin', 
    '$2a$10$X.aHh3eJ0Vf1Y.9U8Z4Rje/0T/6R7B.8N9Z7P/0Q.0T0Z9Z0T0Z9Z', 
    'ROLE_ADMIN', 
    CURRENT_TIMESTAMP
);

-- 2. Temel Fonlar
INSERT INTO funds (id, code, title, manager, annual_fee_ratio, is_active, display_order, created_at)
VALUES 
    (gen_random_uuid(), 'THF', 'Tera Portföy Hisse Senedi Fonu (Hisse Senedi Yoğun Fon)', 'Tera Portföy Yönetimi A.Ş.', 0.0250, true, 1, CURRENT_TIMESTAMP),
    (gen_random_uuid(), 'TLY', 'Tera Portföy Birinci Hisse Senedi Fonu (Hisse Senedi Yoğun Fon)', 'Tera Portföy Yönetimi A.Ş.', 0.0300, true, 2, CURRENT_TIMESTAMP),
    (gen_random_uuid(), 'TMV', 'Tera Portföy Temettü Ödeyen Şirketler Hisse Senedi Fonu (Hisse Senedi Yoğun Fon)', 'Tera Portföy Yönetimi A.Ş.', 0.0200, true, 3, CURRENT_TIMESTAMP),
    (gen_random_uuid(), 'DOH', 'Deniz Portföy Hisse Senedi Fonu (Hisse Senedi Yoğun Fon)', 'Deniz Portföy Yönetimi A.Ş.', 0.0225, true, 4, CURRENT_TIMESTAMP),
    (gen_random_uuid(), 'DFI', 'Deniz Portföy İkinci Hisse Senedi Fonu (Hisse Senedi Yoğun Fon)', 'Deniz Portföy Yönetimi A.Ş.', 0.0250, true, 5, CURRENT_TIMESTAMP),
    (gen_random_uuid(), 'KHA', 'Kuveyt Türk Portföy Hisse Senedi Fonu (Hisse Senedi Yoğun Fon)', 'Kuveyt Türk Portföy Yönetimi A.Ş.', 0.0195, true, 6, CURRENT_TIMESTAMP),
    (gen_random_uuid(), 'TTE', 'İş Portföy BİST Teknoloji Ağırlıklı Sınırlandırılmış Endeksi Hisse Senedi Fonu (Hisse Senedi Yoğun Fon)', 'İş Portföy Yönetimi A.Ş.', 0.0200, true, 7, CURRENT_TIMESTAMP);

-- 3. Örnek Bir Kaç Temel Enstrüman (Borsa İstanbul Demirbaşları)
-- Asset_Class "EQUITY" hisseler için
INSERT INTO instruments (ticker, isin_code, title, asset_class, created_at)
VALUES 
    ('THYAO', 'TRATHYAO91M5', 'Türk Hava Yolları A.O.', 'EQUITY', CURRENT_TIMESTAMP),
    ('ASELS', 'TRAASELS91H2', 'Aselsan Elektronik Sanayi ve Ticaret A.Ş.', 'EQUITY', CURRENT_TIMESTAMP),
    ('BIMAS', 'TREBIMM00018', 'BİM Birleşik Mağazalar A.Ş.', 'EQUITY', CURRENT_TIMESTAMP),
    ('TUPRS', 'TRATUPRS91E8', 'Tüpraş Türkiye Petrol Rafinerileri A.Ş.', 'EQUITY', CURRENT_TIMESTAMP),
    ('ISCTR', 'TRAISCTR91N2', 'Türkiye İş Bankası A.Ş. (C Grubu)', 'EQUITY', CURRENT_TIMESTAMP);

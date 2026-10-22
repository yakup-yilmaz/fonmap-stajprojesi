-- V6: KHA Fonunun Resmi Unvan ve Kurucu Sirket Bilgisinin Guncellenmesi
-- KHA fonu Pardus Portfoy Yonetimi A.S.'ye aittir.

UPDATE funds 
SET title = 'Pardus Portföy Hisse Senedi Fonu (Hisse Senedi Yoğun Fon)', 
    manager = 'Pardus Portföy Yönetimi A.Ş.' 
WHERE code = 'KHA';

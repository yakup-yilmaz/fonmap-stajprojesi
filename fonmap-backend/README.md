# FONMAP — BIST Seans İçi Canlı Yatırım Fonu Getiri & Portföy Analitiği Platformu

![Java 21](https://img.shields.io/badge/Java-21-orange.svg?style=flat-square&logo=openjdk)
![Spring Boot 3.3](https://img.shields.io/badge/Spring%20Boot-3.3.4-brightgreen.svg?style=flat-square&logo=springboot)
![Next.js 16](https://img.shields.io/badge/Next.js-16.3-black.svg?style=flat-square&logo=nextdotjs)
![TypeScript](https://img.shields.io/badge/TypeScript-5.x-blue.svg?style=flat-square&logo=typescript)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15%2B-blue.svg?style=flat-square&logo=postgresql)
![Apache PDFBox](https://img.shields.io/badge/PDFBox-3.0-red.svg?style=flat-square)
![Tailwind CSS](https://img.shields.io/badge/Tailwind-CSS-38B2AC.svg?style=flat-square&logo=tailwind-css)

> **"Yatırım fonlarında 18 saatlik seans belirsizliğine son verin."**  
> FONMAP; Kamuyu Aydınlatma Platformu'nda (KAP) yayımlanan aylık portföy dağılım raporlarını yapay zeka ve finansal tasarım desenleriyle ayrıştıran, sabah açılış ağırlık kaymalarını (Weight Drift) kalibre eden ve Borsa İstanbul (BIST) seans içi canlı tahta verileriyle fonların net getirisini ve birim pay değerini anlık olarak hesaplayan kurumsal fon analitiği platformudur.

> [!NOTE]
> 📖 **Ana Dokümantasyon:** Projenin sistem mimarisi, veri akış şemaları, 3 vitrin fonu ve açılış telafi motoru hakkındaki güncel ve tek yetkili ana dokümantasyon için lütfen projenin kök dizinindeki [README.md](../README.md) dosyasını inceleyiniz.

---

## 📌 İçindekiler
1. [Projenin Amacı ve Çözdüğü Problem](#-projenin-amacı-ve-çözdüğü-problem)
2. [Temel Yetenekler ve Özellikler](#-temel-yetenekler-ve-özellikler)
3. [Matematiksel Modeller ve Finansal Formüller](#-matematiksel-modeller-ve-finansal-formüller)
4. [Kritik Seans Zaman Çizelgesi](#-kritik-seans-zaman-çizelgesi-günlük-yaşam-döngüsü)
5. [Sistem Mimarisi ve Tasarım Desenleri](#-sistem-mimarisi-ve-tasarım-desenleri)
6. [Modül Hiyerarşisi](#-modül-hiyerarşisi)
7. [Kurulum ve Çalıştırma Rehberi](#-kurulum-ve-çalıştırma-rehberi)
8. [REST API Uç Noktaları](#-rest-api-uç-noktaları)
9. [Test Stratejisi ve Doğrulama](#-test-stratejisi-ve-doğrulama)

---

## 🎯 Projenin Amacı ve Çözdüğü Problem

Türkiye fon piyasasında (TEFAS) yatırım fonlarının resmi birim katılma payı fiyatları **günde sadece bir kez**, piyasa kapandıktan sonra gece saat **23:00 civarında** ilan edilir. 

* **Problem (18 Saatlik Kör Uçuş):** Borsa İstanbul seansı (10:00 - 18:10) boyunca hisse senedi yoğun fonların (HSYF), teknoloji fonlarının veya serbest fonların değeri anlık olarak değişmesine rağmen, yatırımcılar ve fon yöneticileri fonlarının ne kadar kazandırdığını veya kaybettiğini seans anında bilemez.
* **FONMAP Çözümü:**
  1. **KAP Dağılımını Okur:** Ayda 1 yayımlanan resmi portföy dağılım PDF raporunu otomatik indirir ve içeriğindeki tüm hisse ve türev pozisyonlarını kuruşu kuruşuna ayıklar.
  2. **Ağırlık Kaymasını (Drift) Düzeltir:** Rapor tarihinden bugüne kadar hisselerin farklı oranlarda prim yapmasıyla değişen gerçek portföy ağırlıklarını her sabah seans öncesi (09:50) günceller.
  3. **Canlı Seans Fiyatlaması Yapar:** BIST canlı tahtasındaki dakikalık fiyatlarla her dakika fonun net getirisini ve nominal tahmini birim pay fiyatını (`1.6853 ₺ ➔ 1.7097 ₺`) hesaplar.
  4. **Akşam TEFAS Mutabakatı Yapar:** Gece 23:00'te TEFAS'ın açıkladığı resmi fiyatla modeli karşılaştırır, MAE ve RMSE hata puanlarını hesaplayarak geriye dönük karne üretir.

---

## 🚀 Temel Yetenekler ve Özellikler

### 1. Seans İçi Canlı Tahmin Motoru ($O(1)$ Optimization)
* Sabah 09:50'de kalibre edilen ağırlıklar diske mühürlenir (`SEALED`).
* Seans içinde (10:00 - 18:10) her dakika geçmişi sıfırdan hesaplamak yerine $O(1)$ hızında mühürlü ağırlıklar üzerinden net getiri ve yönetim gideri tenzilatı yapılır.
* Vitrin ve fon kartlarında dünkü resmi birim fiyat ile bugünkü canlı tahmini değer nominal TL olarak sunulur:
  $$\mathbf{Birim\ Pay:\ 1.6853\ ₺} \longrightarrow \mathbf{1.7097\text{ ₺}} \quad \text{🟢 (Canlı Sinyal)}$$

### 2. Akıllı KAP PDF Ayrıştırma Motoru (Strategy & Content Sniffing)
* **Strategy Pattern:** Farklı portföy yönetim şirketlerinin şablonları bağımsız sınıflara ayrılmıştır (`SpkLandscapePdfParser`, `IsPortfoyPdfParser`, `AtlasPdfParser`, `PardusPdfParser`, `GenericVerticalPdfParser`).
* **Content Sniffing (Olimpik Skorlama):** PDF'in içine bakarak en uygun parser'ı dinamik olarak puanlar.
* **Cascade Fallback:** Bir parser font bozulması nedeniyle başarısız olursa sistem çökmez; otomatik olarak sıradaki aday parser devreye girer.
* **Open/Closed Principle:** Yeni bir portföy şirketi formatı geldiğinde mevcut sınıflara dokunulmaz; yeni bir `@Component` strateji sınıfı eklenmesi yeterlidir.

### 3. Day-1 Canlı Performans Takibi (Yeni Eklenen Fonlar)
* Geriye dönük 60 aylık arşivi olmayan, sisteme **yalnızca o anki en güncel PDF ile yeni eklenen** fonlar için grafik ve metrikler kilitlenmez.
* Eklendiği 1. seans gününden itibaren TEFAS mutabakatları biriktikçe (1 gün, 2 gün, 5 gün, 14 gün) grafik anında çizilir ve o günlerin gerçekleşen verisi üzerinden MAE/RMSE hesaplanır.

### 4. Kayan Pencere (Sliding Window FIFO) Mimarisi
* **FIFO Kuralı:** En güncel seans günü pencereye girer, en eski gün pencereden düşer.
* **1 Hafta (`1W`):** Son 5 işlem günü (`slice(-5)`). 6. gün gelince 1. gün pencereden düşer.
* **1 Ay (`1M`):** Son 22 işlem günü / ~30 takvim günü (`slice(-22)`).
* **2 Ay (`2M`):** Son 44–60 işlem günü (`slice(-44)`).
* Butonlara tıklandığında yalnızca grafik filtrelenmez; kartın altındaki **MAE, RMSE ve Yönsel Doğruluk metrikleri de anında o kayan penceredeki günlerin verisiyle dinamik olarak yeniden hesaplanır.**

### 5. Çoklu Varlık Sınıfı ve VİOP Kaldıraç Esnekliği
* Portföydeki varlıklar otomatik olarak sınıflandırılır:
  * **`EQUITY` (Hisse Senetleri):** Canlı tahtadan anlık güncellenir.
  * **`VIOP` (Türev & Endeks Sözleşmeleri):** Kaldıraçlı getiri katkısı ve teminat oranı ayrıştırılır.
  * **`DEPOSIT` (Ters Repo / Takasbank Para Piyasası):** Tahtası yoktur; TCMB faizinden günlük $r = \frac{\text{Faiz}}{365}$ neması işletilir.
  * **`BOND` (Özel Sektör Bonosu / Tahvil):** Sabit kuponlu/nötr getiri ($r=0$) kabul edilir.

### 6. Admin Paneli & Güvenlik Kalkanı (Dry-Run Önizleme)
* KAP'tan inen bir PDF veritabanına yazılmadan önce Admin panelinde **Dry-Run Tablo Önizleme Modalı** ile test edilebilir.
* Hisseler, lotlar ve ağırlıklar incelenip Sanity Check onaylandıktan sonra kalıcı kayda alınır.
* Standart dışı serbest metinli şirket isimleri `instrument_aliases` öğrenen sözlük tablosuna düşer; admin tek tıkla onaylayabilir.

---

## 📐 Matematiksel Modeller ve Finansal Formüller

### 1. Seans İçi Net Tahmini Getiri
$$R_{fon}(t) = \sum_{i=1}^{N} \left( w_i(t) \times r_i(t) \right) - \frac{\text{Yıllık Fon Gider Oranı}}{252}$$
* $w_i(t)$: Varlığın sabah 09:50'de dondurulan mühürlü açılış ağırlığı.
* $r_i(t) = \frac{P_{i,t}}{P_{i,0}} - 1$: Hissenin dünkü borsa kapanışına ($P_{i,0}$) göre anlık borsa getirisi.
* $252$: Türkiye sermaye piyasalarındaki yıllık borsa iş günü sayısı.

### 2. Ağırlık Kayması (Weight Drift)
KAP raporu ayda 1 kez yayımlanır. Rapor tarihinden ($t_0$) bugüne ($t-1$) hisselerin fiyatı farklı oranlarda arttığı için portföy içindeki payları kayar:
$$w_i(t) = \frac{w_i(t_0) \times \frac{P_i(t-1)}{P_i(t_0)}}{\sum_{j} \left[ w_j(t_0) \times \frac{P_j(t-1)}{P_j(t_0)} \right]}$$
* Paydada toplam daima tam **%100 (1.0000)** olarak normalize edilir.

### 3. Kapsama Oranı (Coverage Ratio) & Güven Skoru
Canlı piyasa fiyatına erişilebilen varlıkların toplam portföydeki payını ölçer:
$$\text{Kapsama Oranı} = \frac{\sum_{i \in \text{Canlı Fiyat}} w_i}{\sum_{j \in \text{Tüm Portföy}} w_j} \times 100$$
* $\ge \%85$ $\rightarrow$ 🟢 **YÜKSEK GÜVEN (HIGH)** (Tam tahta fiyatlaması)
* $\%70 - \%85$ $\rightarrow$ 🟡 **ORTA GÜVEN (MEDIUM)** (Repo/Mevduat yürütülür)
* $< \%70$ $\rightarrow$ 🔴 **DÜŞÜK GÜVEN (LOW)**

### 4. Ekonometrik Hata Doğrulama Metrikleri (Backtest)
* **MAE (Ortalama Mutlak Hata):**
  $$\text{MAE} = \frac{1}{N} \sum_{k=1}^N |R_{\text{tahmin}, k} - R_{\text{TEFAS}, k}|$$
* **RMSE (Kök Ortalama Kare Hata / Volatilite Cezası):**
  $$\text{RMSE} = \sqrt{\frac{1}{N} \sum_{k=1}^N (R_{\text{tahmin}, k} - R_{\text{TEFAS}, k})^2}$$
* **Yönsel Doğruluk (Directional Accuracy):**
  Modelin fonun prim yaptığı günlerde pozitif (+), değer kaybettiği günlerde negatif (-) tahmin etme oranının yüzdesidir.

---

## ⏰ Kritik Seans Zaman Çizelgesi (Günlük Yaşam Döngüsü)

```
09:50 ────────────────► 10:00 ────────────────► 18:10 ────────────────► 23:00 ────────────────► 00:00
Seans Öncesi Drift       BIST Seansı Başlar      BIST Seansı Biter       TEFAS Resmi Fiyat        Veritabanı
Ağırlık Mühürleme        Dakikalık Canlı         Son Model Tahmini       Mutabakat Motoru ve      Yedekleme ve
(daily_holding_drifts)   Getiri Hesaplama        Dondurulur              Backtest Karnesi         Bakım
```

1. **Sabah 09:50 (Seans Öncesi):** `WeightDriftService` dün akşamki borsa kapanışlarına göre her hissenin güncel ağırlığını hesaplar ve `SEALED` olarak mühürler.
2. **10:00 - 18:10 (Seans İçi):** `LiveEstimateScheduler` her 60 saniyede bir mühürlü ağırlıklar üzerinden $O(1)$ hızında anlık getiri ve hisse katkı dökümü üretir.
3. **18:10 (Seans Kapanışı):** BIST kapanış seansı tamamlanır. Günün son tahmini `estimate_runs` tablosuna kesin seans sonu tahmini olarak işlenir.
4. **23:00 (TEFAS Resmi Mutabakatı):** Takasbank TEFAS resmi kapanış fiyatını ilan eder. `ReconciliationService` resmi getiri ile 18:10 model tahminini karşılaştırır, MAE farkını hesaplar ve backtest karnesine yazar.
5. **Ertesi Sabah 09:50:** Bir önceki gece 23:00'te tescillenen TEFAS fiyatı, yeni seansın baz açılış fiyatı (`currentPrice`) olarak devralınır.

---

## 🏗️ Sistem Mimarisi ve Tasarım Desenleri

```
                                   ┌─────────────────────────────────┐
                                   │      KAP / BIST / TEFAS         │
                                   └──────────────┬──────────────────┘
                                                  │
                                                  ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                   FONMAP BACKEND                                       │
│                                                                                        │
│  ┌────────────────────────┐  Sniffing   ┌───────────────────────────────────────────┐  │
│  │    PdfParserFactory    ├────────────►│ PdfParserStrategy (Landscape, İş, Atlas..) │  │
│  └───────────┬────────────┘             └─────────────────────┬─────────────────────┘  │
│              │ Fallback                                       │                        │
│              ▼                                                ▼                        │
│  ┌────────────────────────┐             ┌───────────────────────────────────────────┐  │
│  │    PdfImportService    │◄────────────┤ Holding & FundSnapshot (PostgreSQL)       │  │
│  └───────────┬────────────┘             └─────────────────────┬─────────────────────┘  │
│              │                                                │                        │
│              ▼                                                ▼                        │
│  ┌────────────────────────┐             ┌───────────────────────────────────────────┐  │
│  │   WeightDriftService   │ (09:50) ───►│ DailyHoldingDrift (Mühürlü Ağırlıklar)    │  │
│  └───────────┬────────────┘             └─────────────────────┬─────────────────────┘  │
│              │                                                │                        │
│              ▼                                                ▼                        │
│  ┌────────────────────────┐             ┌───────────────────────────────────────────┐  │
│  │ReturnCalculationService│ (10:00-18:10)│ EstimateRun & EstimateDetail             │  │
│  └───────────┬────────────┘             └─────────────────────┬─────────────────────┘  │
│              │                                                │                        │
│              ▼                                                ▼                        │
│  ┌────────────────────────┐ (23:00)     ┌───────────────────────────────────────────┐  │
│  │ ReconciliationService  ├────────────►│ BacktestReport (1W, 1M, 2M Kayan Pencere) │  │
│  └────────────────────────┘             └─────────────────────┬─────────────────────┘  │
└───────────────────────────────────────────────────────────────┼────────────────────────┘
                                                                │ REST API
                                                                ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                   FONMAP FRONTEND                                      │
│                                                                                        │
│   ┌────────────────────┐    ┌────────────────────┐    ┌───────────────────────────┐    │
│   │  Ana Sayfa Vitrin  │    │  Canlı Takip Desk  │    │   Derin Analiz & Backtest │    │
│   │  (Nominal ₺ Payı)  │    │  (Hızlı Röntgen)   │    │   (Kayan Pencere Grafiği) │    │
│   └────────────────────┘    └────────────────────┘    └───────────────────────────┘    │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 📦 Modül Hiyerarşisi

Proje **Clean Architecture** prensiplerine göre 4 arka uç modülü ve 1 ön yüz uygulamasından oluşur:

```
fonmap-backend/
├── pom.xml                               # Root Maven POM (Java 21, Spring Boot 3.3.4)
├── proje_klavuz.md                       # Kapsamlı Proje & Finansal Mimari Klavuzu
│
├── fonmap-domain/                        # Katman 1: İş Modelleri ve Domain Kuralları
│   └── src/main/java/com/fonmap/domain/
│       ├── entity/                       # Fund, Instrument, Holding, Snapshot, EstimateRun...
│       ├── enums/                        # AssetClass, ConfidenceLevel, ReportStatus
│       └── repository/                   # Spring Data JPA Repository arayüzleri
│
├── fonmap-infrastructure/                # Katman 2: Dış Dünya Adaptörleri ve Entegrasyon
│   └── src/main/java/com/fonmap/infrastructure/
│       ├── client/kap/                   # KAP Otomasyon İstemcisi (KapClient)
│       ├── client/tefas/                 # TEFAS REST API İstemcisi (TefasClient)
│       ├── client/price/                 # Piyasa Fiyat Motoru (Yahoo Finance / Dual-feed)
│       ├── parser/strategy/              # PDF Stratejileri (SpkLandscape, IsPortfoy, Atlas..)
│       ├── parser/factory/               # PdfParserFactory (Sniffing & Fallback Engine)
│       └── parser/util/                  # PdfParsingUtils (Auto-decimal, Regex, Temizleme)
│
├── fonmap-application/                   # Katman 3: İş Mantığı ve Servisler
│   └── src/main/java/com/fonmap/application/
│       ├── service/estimate/             # ReturnCalculationService, WeightDriftService
│       ├── service/importing/            # PdfImportService, AliasMatchingService
│       ├── service/reconcile/            # ReconciliationService
│       └── service/backtest/             # BacktestService (Kayan Pencere & Ekonometri)
│
└── fonmap-api/                           # Katman 4: REST Denetleyicileri ve Zamanlayıcılar
    └── src/main/java/com/fonmap/api/
        ├── controller/                   # EstimateController, FundController, AdminController
        ├── scheduler/                    # LiveEstimateScheduler, DailyDriftScheduler
        └── FonmapApiApplication.java     # Spring Boot Uygulama Başlatıcı

fonmap-frontend/                          # Next.js 16 Web Uygulaması
├── src/
│   ├── app/                              # Next.js App Router (page, dashboard, admin, funds/[code])
│   ├── components/                       # FundCard, BacktestChart, PortfolioPieChart, Modals...
│   ├── lib/api.ts                        # Backend REST API istemcisi ve veri eşleyicisi
│   └── types/fonmap.ts                   # Katı tip-güvenliği (TypeScript DTO Sözleşmeleri)
```

---

## ⚙️ Kurulum ve Çalıştırma Rehberi

### Gereksinimler
* **Java:** JDK 21+
* **Maven:** 3.9+
* **Node.js:** 18.x veya 20.x+
* **PostgreSQL:** 15+ (Varsayılan port: 5432)

---

### 1. Veritabanı Hazırlığı
PostgreSQL'de `fonmap` adında bir veritabanı oluşturun:
```sql
CREATE DATABASE fonmap;
```

`fonmap-api/src/main/resources/application.yml` dosyasındaki veritabanı kullanıcı adı ve şifrenizi kontrol edin:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/fonmap
    username: postgres
    password: your_password
  jpa:
    hibernate:
      ddl-auto: update
```

---

### 2. Backend'i Başlatma
Projenin ana dizininde tüm modülleri derleyin ve API sunucusunu ayağa kaldırın:

```bash
# 1. Backend kök dizinine geçin
cd fonmap-backend

# 2. Testleri çalıştırarak projeyi derleyin
mvn clean install

# 3. API sunucusunu başlatın
mvn spring-boot:run -pl fonmap-api
```
Backend `http://localhost:8080` adresinde çalışmaya başlayacaktır.  
Swagger API arayüzüne `http://localhost:8080/swagger-ui.html` adresinden erişebilirsiniz.

---

### 3. Frontend'i Başlatma
Ayrı bir terminal penceresinde frontend dizinine geçin:

```bash
# 1. Frontend dizinine geçin
cd fonmap-frontend

# 2. Bağımlılıkları yükleyin
npm install

# 3. Geliştirme sunucusunu başlatın
npm run dev
```
Frontend `http://localhost:3000` adresinde açılacaktır.

---

## 🔌 REST API Uç Noktaları

| Metot | Uç Nokta | Açıklama |
|:---:|---|---|
| `GET` | `/api/v1/estimates/latest` | Tüm aktif fonların canlı tahmin kartları, yüzdeleri ve nominal TL birim pay fiyatları |
| `GET` | `/api/v1/estimates/{fundCode}` | Tek bir fonun detaylı tahmini, getiriye en çok omuz veren ilk 5 hisse (Liderler) ve baskılayanlar |
| `GET` | `/api/v1/estimates/{fundCode}/history` | Detay ekranı için gün içi seans grafik zaman serisi |
| `GET` | `/api/v1/funds` | Takip edilen tüm fonların listesi, kurucu yöneticileri ve rapor özetleri |
| `GET` | `/api/v1/admin/backtest/{code}` | Fonun 1W, 1M, 2M Kayan Pencere Ekonometrik Doğrulama Raporu (MAE, RMSE, Yön Doğruluğu) |
| `POST`| `/api/v1/admin/reports/upload` | KAP'tan indirilen PDF'i sisteme yükleme ve portföye işleme |
| `POST`| `/api/v1/admin/reports/preview` | **Dry-Run Önizleme:** PDF'i veritabanına yazmadan parse edip varlıkları tablo halinde önizleme |
| `GET` | `/api/v1/admin/aliases/pending` | Onay bekleyen kirli hisse eşleme sözlük kayıtları |

---

## 🧪 Test Stratejisi ve Doğrulama

Sistemimiz kurumsal finans standartlarında kapsamlı test paketleriyle donatılmıştır:

```bash
mvn test "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=BacktestServiceTest,PdfParserIntegrationTest,IczParserLiveTest"
```

### 1. 7 Fonluk Gerçek KAP PDF Sınavı (`PdfParserIntegrationTest`)
Disk üzerindeki gerçek KAP PDF dosyaları üzerinde tam ayrıştırma denetimi yapılır:
* **THF (Tera Portföy):** 133 varlık, 83.56 Milyar TL TNV, AEFES (%16.00), AKBNK (%2.09).
* **TTE (İş Portföy Teknoloji):** 159 varlık, 3.88 Milyar TL TNV, ALCTL, ALTNY, ARDYZ kuruşu kuruşuna doğrulandı.
* **TI2 (İş Portföy 2. Hisse):** SPK Tablo III yatay Türkçe düzeni, 63 holding, 2.917 Milyar TL TNV.
* **DFI (Atlas Portföy):** 3 sayfalık dikey düzen, 31.98 Milyar TL TNV, $P_0$ kuruş hassasiyetiyle türetildi.
* **KHA (Pardus Portföy):** Çok satırlı lookahead ISIN eşlemesi, 8.51 Milyar TL TNV, A1CAP (%39.00).

### 2. Ekonometrik Backtest Sınavı (`BacktestServiceTest`)
* Tarihsel fon fiyatları üzerinden model tahminleri ile TEFAS gerçekleşmeleri kıyaslanır; MAE, RMSE ve yönsel doğruluk formülleri matematiksel olarak tescillenir.

### 3. Frontend Derleme ve Tip Testi
* `npm run build` ile Next.js ve Turbopack derleyicisi tüm rotaları statik/dinamik olarak derler ve TypeScript tip güvenliğini %100 doğrular.

---

## 📄 Lisans & Telif Hakkı
Bu proje Staj ve Araştırma-Geliştirme amacıyla geliştirilmiştir.

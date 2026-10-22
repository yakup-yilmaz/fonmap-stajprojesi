'use client';

import React from 'react';
import { Calculator, TrendingUp, ShieldCheck, Scale, AlertCircle } from 'lucide-react';

interface FormulaDisplayProps {
  formulaId: number;
}

export default function FormulaDisplay({ formulaId }: FormulaDisplayProps) {
  if (formulaId === 0) {
    // 1. Seans İçi Net Tahmini Getiri
    return (
      <div className="space-y-6">
        {/* Formül Kartı */}
        <div className="rounded-2xl border border-emerald-500/30 bg-emerald-500/5 p-6 shadow-inner">
          <div className="flex items-center justify-between mb-4">
            <span className="text-[11px] font-mono font-bold uppercase tracking-wider text-emerald-400 flex items-center gap-1.5">
              <Calculator className="h-3.5 w-3.5" />
              <span>Akademik Finansal Bağıntı</span>
            </span>
            <span className="text-[10px] font-semibold text-emerald-400 bg-emerald-500/10 px-2 py-0.5 rounded-full border border-emerald-500/20">
              Canlı Seans Motoru
            </span>
          </div>

          {/* Okunabilir Tipografik Formül */}
          <div className="flex flex-wrap items-center justify-center gap-3 py-4 text-white font-mono text-base sm:text-xl font-bold bg-slate-950/80 rounded-xl border border-slate-800 p-4 overflow-x-auto">
            <span className="text-emerald-400 font-extrabold text-lg sm:text-2xl">
              R<sub className="text-xs text-emerald-300">fon</sub>(t)
            </span>

            <span className="text-slate-500 text-xl font-sans">=</span>

            <div className="flex items-center gap-1.5 bg-slate-900 px-3 py-1.5 rounded-lg border border-slate-800">
              <span className="text-2xl sm:text-3xl text-emerald-400 font-serif leading-none">
                ∑
              </span>
              <div className="flex flex-col text-[10px] text-slate-400 font-sans leading-none -ml-1">
                <span>N</span>
                <span>i=1</span>
              </div>
              <span className="text-white text-sm sm:text-base ml-1">
                ( w<sub className="text-emerald-400">i</sub>(t) × r<sub className="text-emerald-400">i</sub>(t) )
              </span>
            </div>

            <span className="text-rose-400 text-xl font-sans">−</span>

            {/* Kesir Yapısı (Gider Payı) */}
            <div className="inline-flex flex-col items-center justify-center text-xs sm:text-sm">
              <span className="text-rose-300 pb-0.5 px-2">
                Yıllık Yönetim Ücreti
              </span>
              <span className="w-full border-t-2 border-rose-400/80 my-0.5" />
              <span className="text-slate-300 pt-0.5 font-sans font-semibold">
                252 <span className="text-[10px] text-slate-400">(Borsa İş Günü)</span>
              </span>
            </div>
          </div>
        </div>

        {/* Değişken Açıklamaları */}
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
          <div className="rounded-xl border border-slate-800 bg-slate-950/60 p-3.5">
            <div className="flex items-center gap-2 mb-1">
              <span className="font-mono font-bold text-xs text-emerald-400 bg-emerald-500/10 px-1.5 py-0.5 rounded">
                w_i(t)
              </span>
              <span className="text-xs font-semibold text-white">Güncel Ağırlık</span>
            </div>
            <p className="text-[11px] text-slate-400 leading-relaxed">
              Varlığın seans başında sabah 09:50'de kilitlenen dünkü kapanışa göre düzeltilmiş portföy payı (Örn: %17.35 THYAO).
            </p>
          </div>

          <div className="rounded-xl border border-slate-800 bg-slate-950/60 p-3.5">
            <div className="flex items-center gap-2 mb-1">
              <span className="font-mono font-bold text-xs text-blue-400 bg-blue-500/10 px-1.5 py-0.5 rounded">
                r_i(t)
              </span>
              <span className="text-xs font-semibold text-white">Anlık Getiri</span>
            </div>
            <p className="text-[11px] text-slate-400 leading-relaxed">
              BIST tahtasındaki dünkü kapanış fiyatına göre hesaplanan canlı seans içi getiri yüzdesi (Örn: +%2.40).
            </p>
          </div>

          <div className="rounded-xl border border-slate-800 bg-slate-950/60 p-3.5">
            <div className="flex items-center gap-2 mb-1">
              <span className="font-mono font-bold text-xs text-rose-400 bg-rose-500/10 px-1.5 py-0.5 rounded">
                Gider / 252
              </span>
              <span className="text-xs font-semibold text-white">Günlük Fon Kesintisi</span>
            </div>
            <p className="text-[11px] text-slate-400 leading-relaxed">
              SPK ve TEFAS yönetmeliği gereğince yıllık yönetim ücretinin güne düşen payı seans başında bir kez net düşülür.
            </p>
          </div>
        </div>
      </div>
    );
  }

  if (formulaId === 1) {
    // 2. Ağırlık Kayması (Weight Drift)
    return (
      <div className="space-y-6">
        <div className="rounded-2xl border border-blue-500/30 bg-blue-500/5 p-6 shadow-inner">
          <div className="flex items-center justify-between mb-4">
            <span className="text-[11px] font-mono font-bold uppercase tracking-wider text-blue-400 flex items-center gap-1.5">
              <Scale className="h-3.5 w-3.5" />
              <span>Ağırlık Kayması (Weight Drift Formülü)</span>
            </span>
            <span className="text-[10px] font-semibold text-blue-400 bg-blue-500/10 px-2 py-0.5 rounded-full border border-blue-500/20">
              09:50 Seans Öncesi Otomasyonu
            </span>
          </div>

          {/* Okunabilir Tipografik Formül */}
          <div className="flex flex-wrap items-center justify-center gap-4 py-4 text-white font-mono text-base sm:text-xl font-bold bg-slate-950/80 rounded-xl border border-slate-800 p-4 overflow-x-auto">
            <span className="text-blue-400 font-extrabold text-lg sm:text-2xl">
              w<sub className="text-xs text-blue-300">i</sub>(t)
            </span>

            <span className="text-slate-500 text-xl font-sans">=</span>

            {/* Büyük Ana Kesir */}
            <div className="inline-flex flex-col items-center justify-center text-xs sm:text-sm">
              {/* Pay */}
              <div className="flex items-center gap-2 pb-1.5 px-3">
                <span className="text-white">
                  w<sub className="text-blue-400">i</sub>(t₀)
                </span>
                <span className="text-slate-400">×</span>
                <div className="inline-flex flex-col items-center text-[11px] bg-slate-900 px-2 py-0.5 rounded border border-slate-800">
                  <span className="text-emerald-400">P<sub className="text-[9px]">i</sub>(t−1)</span>
                  <span className="w-full border-t border-slate-700" />
                  <span className="text-slate-300">P<sub className="text-[9px]">i</sub>(t₀)</span>
                </div>
              </div>

              {/* Kesir Çizgisi */}
              <span className="w-full border-t-2 border-blue-400/80 my-1" />

              {/* Payda: Normalizasyon Toplamı */}
              <div className="flex items-center gap-1.5 pt-1.5 px-3 text-slate-300">
                <span className="text-xl sm:text-2xl text-blue-400 font-serif leading-none">
                  ∑
                </span>
                <span className="text-[10px] text-slate-400 -ml-1">j</span>
                <span className="text-xs text-slate-300">
                  [ w<sub className="text-blue-400">j</sub>(t₀) × ( P<sub className="text-[9px]">j</sub>(t−1) / P<sub className="text-[9px]">j</sub>(t₀) ) ]
                </span>
              </div>
            </div>
          </div>
        </div>

        {/* Değişken Açıklamaları */}
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
          <div className="rounded-xl border border-slate-800 bg-slate-950/60 p-3.5">
            <span className="font-mono font-bold text-xs text-blue-400 block mb-1">
              t₀ (Rapor Tarihi)
            </span>
            <p className="text-[11px] text-slate-400 leading-relaxed">
              KAP'ta yayımlanan son resmi portföy dağılım raporunun referans günüdür (Genellikle ayın son iş günü).
            </p>
          </div>

          <div className="rounded-xl border border-slate-800 bg-slate-950/60 p-3.5">
            <span className="font-mono font-bold text-xs text-emerald-400 block mb-1">
              P_i(t−1) (Dünkü Kapanış)
            </span>
            <p className="text-[11px] text-slate-400 leading-relaxed">
              Dün akşam borsa kapandığında oluşan resmi uzlaşma fiyatıdır. Değeri artan hissenin portföydeki payı kendiliğinden büyür.
            </p>
          </div>

          <div className="rounded-xl border border-slate-800 bg-slate-950/60 p-3.5">
            <span className="font-mono font-bold text-xs text-teal-400 block mb-1">
              Normalizasyon (= %100)
            </span>
            <p className="text-[11px] text-slate-400 leading-relaxed">
              Paydadaki toplam sayesinde tüm varlıkların ağırlık toplamı her sabah kuruşu kuruşuna tam 1.0000 (%100.0) değerine normalize edilir.
            </p>
          </div>
        </div>
      </div>
    );
  }

  if (formulaId === 2) {
    // 3. Kapsama Oranı & Güven Skoru
    return (
      <div className="space-y-6">
        <div className="rounded-2xl border border-amber-500/30 bg-amber-500/5 p-6 shadow-inner">
          <div className="flex items-center justify-between mb-4">
            <span className="text-[11px] font-mono font-bold uppercase tracking-wider text-amber-400 flex items-center gap-1.5">
              <ShieldCheck className="h-3.5 w-3.5" />
              <span>Kapsama Oranı & Şeffaflık Metriği</span>
            </span>
            <span className="text-[10px] font-semibold text-amber-400 bg-amber-500/10 px-2 py-0.5 rounded-full border border-amber-500/20">
              Risk & Kalite Kontrolü
            </span>
          </div>

          {/* Okunabilir Tipografik Formül */}
          <div className="flex flex-wrap items-center justify-center gap-4 py-4 text-white font-mono text-base sm:text-xl font-bold bg-slate-950/80 rounded-xl border border-slate-800 p-4 overflow-x-auto">
            <span className="text-amber-400 font-extrabold text-lg sm:text-2xl">
              Kapsama Oranı (%)
            </span>

            <span className="text-slate-500 text-xl font-sans">=</span>

            <div className="inline-flex flex-col items-center justify-center text-xs sm:text-sm">
              <span className="text-emerald-300 pb-0.5 px-3">
                ∑ w<sub className="text-emerald-400">i</sub> (Canlı BIST Fiyatı Olan Varlıklar)
              </span>
              <span className="w-full border-t-2 border-amber-400/80 my-0.5" />
              <span className="text-slate-300 pt-0.5 px-3">
                ∑ w<sub className="text-slate-400">j</sub> (Portföydeki Tüm Varlıklar + Nakit)
              </span>
            </div>

            <span className="text-amber-400 text-lg sm:text-xl">× 100</span>
          </div>
        </div>

        {/* 3 Kademeli Güven Skoru */}
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
          <div className="rounded-xl border border-emerald-500/30 bg-emerald-500/10 p-3.5">
            <div className="flex items-center gap-2 mb-1">
              <span className="h-2 w-2 rounded-full bg-emerald-400 animate-pulse" />
              <span className="text-xs font-bold text-emerald-300">
                🟢 %85 ve Üzeri: YÜKSEK
              </span>
            </div>
            <p className="text-[11px] text-slate-300 leading-relaxed">
              Hisse senedi payı baskındır; model tahmini ile akşamki TEFAS resmi fiyatı arasındaki sapma ihmal edilebilir düzeydedir.
            </p>
          </div>

          <div className="rounded-xl border border-amber-500/30 bg-amber-500/10 p-3.5">
            <div className="flex items-center gap-2 mb-1">
              <span className="h-2 w-2 rounded-full bg-amber-400" />
              <span className="text-xs font-bold text-amber-300">
                🟡 %70 - %85: ORTA
              </span>
            </div>
            <p className="text-[11px] text-slate-300 leading-relaxed">
              Portföyde devlet tahvili, ters repo veya Takasbank para piyasası (PP) mevcuttur; bu kısımlar günlük sabit faizle yürütülür.
            </p>
          </div>

          <div className="rounded-xl border border-rose-500/30 bg-rose-500/10 p-3.5">
            <div className="flex items-center gap-2 mb-1">
              <span className="h-2 w-2 rounded-full bg-rose-400" />
              <span className="text-xs font-bold text-rose-300">
                🔴 %70 Altı: DÜŞÜK
              </span>
            </div>
            <p className="text-[11px] text-slate-300 leading-relaxed">
              Borsa dışı opsiyon, kira sertifikası veya yabancı varlık oranı yüksektir. Tahmin kullanıcıya dikkat uyarısıyla sunulur.
            </p>
          </div>
        </div>
      </div>
    );
  }

  // 4. Ekonometrik Hata Doğrulama (MAE & RMSE)
  return (
    <div className="space-y-6">
      <div className="rounded-2xl border border-purple-500/30 bg-purple-500/5 p-6 shadow-inner">
        <div className="flex items-center justify-between mb-4">
          <span className="text-[11px] font-mono font-bold uppercase tracking-wider text-purple-400 flex items-center gap-1.5">
            <TrendingUp className="h-3.5 w-3.5" />
            <span>Ekonometrik Doğrulama & Denetim Formülleri</span>
          </span>
          <span className="text-[10px] font-semibold text-purple-400 bg-purple-500/10 px-2 py-0.5 rounded-full border border-purple-500/20">
            Gece 23:00 TEFAS Mutabakatı
          </span>
        </div>

        {/* İki Formül Yan Yana */}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {/* MAE Kutusu */}
          <div className="rounded-xl border border-slate-800 bg-slate-950/80 p-4 text-center">
            <span className="text-xs font-bold text-purple-300 block mb-2 font-mono">
              1. MAE (Mean Absolute Error)
            </span>
            <div className="flex items-center justify-center gap-2 py-2 text-white font-mono text-sm sm:text-base font-bold">
              <span className="text-purple-400">MAE =</span>
              <span className="text-xs text-slate-400 font-sans">1/N</span>
              <span className="text-xl text-purple-400 font-serif">∑</span>
              <span className="bg-purple-500/10 px-2 py-1 rounded text-xs text-purple-200 border border-purple-500/20">
                | R<sub className="text-[9px]">tahmin</sub> − R<sub className="text-[9px]">TEFAS</sub> |
              </span>
            </div>
            <p className="text-[11px] text-slate-400 mt-2">
              Modelin ortalama mutlak sapma puanıdır (THF için gerçekleşen: <strong>%0.168</strong>).
            </p>
          </div>

          {/* RMSE Kutusu */}
          <div className="rounded-xl border border-slate-800 bg-slate-950/80 p-4 text-center">
            <span className="text-xs font-bold text-teal-300 block mb-2 font-mono">
              2. RMSE (Root Mean Squared Error)
            </span>
            <div className="flex items-center justify-center gap-2 py-2 text-white font-mono text-sm sm:text-base font-bold">
              <span className="text-teal-400">RMSE =</span>
              <span className="text-lg text-teal-400 font-sans">√</span>
              <span className="text-xs text-slate-400 font-sans">(1/N</span>
              <span className="text-xl text-teal-400 font-serif">∑</span>
              <span className="bg-teal-500/10 px-2 py-1 rounded text-xs text-teal-200 border border-teal-500/20">
                (R<sub className="text-[9px]">tahmin</sub> − R<sub className="text-[9px]">TEFAS</sub>)² )
              </span>
            </div>
            <p className="text-[11px] text-slate-400 mt-2">
              Büyük sapmaları ve anomali günlerini daha sert cezalandıran volatilite metriği (THF: <strong>%0.184</strong>).
            </p>
          </div>
        </div>
      </div>

      {/* Şartname Başarı Kriterleri */}
      <div className="rounded-xl border border-slate-800 bg-slate-950/60 p-4 text-xs text-slate-300 flex items-start gap-3">
        <AlertCircle className="h-5 w-5 text-purple-400 shrink-0 mt-0.5" />
        <div>
          <span className="font-bold text-white block mb-0.5">
            Şartname Bölüm 10.6 Ekonometrik Başarı Kuralı:
          </span>
          <p className="text-slate-400 leading-relaxed text-[11px]">
            Hisse senedi fonlarında <strong>MAE ≤ %0.20 (20 baz puan)</strong> kusursuz, <strong>%0.50</strong> altı ise borsa seans içi dinamik ağırlık kaymaları ve kapanış karanlık oda eşleşmeleri sebebiyle istatistiki kabul edilebilir tolerans aralığı olarak değerlendirilir. Günlük tahmin sapmaları her seans TEFAS gerçekleşmeleriyle çapraz doğrulanarak backtest karnesine işlenir.
          </p>
        </div>
      </div>
    </div>
  );
}

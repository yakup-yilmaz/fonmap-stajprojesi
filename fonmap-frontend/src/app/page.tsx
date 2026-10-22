'use client';

import React, { useState, useEffect } from 'react';
import Link from 'next/link';
import {
  TrendingUp,
  Activity,
  ShieldCheck,
  Cpu,
  Layers,
  FileText,
  BarChart3,
  ArrowRight,
  Sparkles,
  Calculator,
  RefreshCw,
  Sliders,
  CheckCircle2,
} from 'lucide-react';
import { fetchLatestEstimates } from '@/lib/api';
import { EstimateResult } from '@/types/fonmap';
import FormulaDisplay from '@/components/FormulaDisplay';

export default function LandingPage() {
  const [estimates, setEstimates] = useState<EstimateResult[]>([]);
  const [activeFormulaTab, setActiveFormulaTab] = useState<number>(0);

  useEffect(() => {
    const loadEstimates = () => {
      fetchLatestEstimates()
        .then((data) => {
          if (data && data.length > 0) setEstimates(data);
        })
        .catch((err) => console.warn('Canlı tahminler alınamadı:', err));
    };
    loadEstimates();
    const interval = setInterval(loadEstimates, 30000);
    return () => clearInterval(interval);
  }, []);

  const formulas = [
    {
      id: 0,
      title: '1. Seans İçi Net Tahmini Getiri',
      desc: 'BIST tahtasındaki tüm varlıkların anlık getirileri, sabah hesaplanan güncel ağırlıklarıyla çarpılır ve yıllık yönetim ücretinin güne düşen payı tek seferde düşülür.',
      latex: 'R_{fon}(t) = \\sum_{i=1}^{N} \\left( w_i(t) \\times r_i(t) \\right) - \\frac{\\text{Yıllık Gider}}{252}',
      detail:
        'w_i(t): Varlığın seans başında sabitlenen güncel ağırlığı, r_i(t): Dünkü kapanışa göre anlık borsa getirisi, 252: Yıllık borsa iş günü sayısı.',
    },
    {
      id: 1,
      title: '2. Ağırlık Kayması (Weight Drift)',
      desc: 'KAP raporu ayda 1 kez yayımlanır; aradan geçen günlerde yükselen hissenin portföydeki payı kendiliğinden büyür. Seans başlamadan önce dün akşamki kapanışlara göre ağırlıklar güncellenir.',
      latex: 'w_i(t) = \\frac{w_i(t_0) \\times \\frac{P_i(t-1)}{P_i(t_0)}}{\\sum_{j} \\left[ w_j(t_0) \\times \\frac{P_j(t-1)}{P_j(t_0)} \\right]}',
      detail:
        'P_i(t_0): Rapor tarihindeki referans fiyat, P_i(t-1): Dün akşamki borsa kapanış fiyatı. Paydada toplam 1.0000 (tam %100) olarak normalize edilir.',
    },
    {
      id: 2,
      title: '3. Kapsama Oranı & Güven Skoru',
      desc: 'Portföyün toplam büyüklüğünün yüzde kaçının anlık canlı piyasa fiyatına ulaşılabildiğini ölçer. %85 ve üzeri YÜKSEK GÜVEN kabul edilir.',
      latex: '\\text{Kapsama Oranı} = \\frac{\\sum_{i \\in \\text{Canlı Fiyat}} w_i}{\\sum_{j \\in \\text{Tüm Varlıklar}} w_j} \\times 100',
      detail:
        'Kapsama ≥ %85 ➔ 🟢 YÜKSEK, %70 - %85 ➔ 🟡 ORTA (Bono/Repo sabit faizle yürütülür), < %70 ➔ 🔴 DÜŞÜK GÜVEN.',
    },
    {
      id: 3,
      title: '4. Ekonometrik Hata Doğrulama (MAE & RMSE)',
      desc: 'Seans sonundaki model tahmini ile gece 23:00\'te TEFAS\'ın açıkladığı resmi kapanış fiyatı arasındaki hata puanı geriye dönük 60 iş günü boyunca denetlenir.',
      latex: '\\text{MAE} = \\frac{1}{N} \\sum_{k=1}^{N} |R_{tahmin} - R_{TEFAS}|, \\quad \\text{RMSE} = \\sqrt{\\frac{1}{N} \\sum_{k=1}^{N} (R_{tahmin} - R_{TEFAS})^2}',
      detail:
        'Hisse fonlarında MAE ≤ %0.20 (20 bps) kusursuz kabul edilir. Modelimiz THF fonunda 16 günün 9\'unu kusursuz tamamlamıştır.',
    },
  ];

  return (
    <div className="flex flex-col min-h-screen">
      {/* 1. HERO BÖLÜMÜ */}
      <section className="relative overflow-hidden pt-20 pb-28 md:pt-28 md:pb-36 border-b border-slate-800/80 bg-gradient-to-b from-slate-950 via-[#0a0f1d] to-[#080c14]">
        {/* Arka Plan Glow Işıkları */}
        <div className="absolute top-1/4 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[700px] h-[350px] bg-emerald-500/10 blur-[130px] rounded-full pointer-events-none" />
        <div className="absolute top-1/3 right-10 w-[450px] h-[300px] bg-indigo-500/10 blur-[120px] rounded-full pointer-events-none" />

        <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 relative z-10 text-center">
          {/* Üst Rozet */}
          <div className="inline-flex items-center gap-2 rounded-full border border-emerald-500/30 bg-emerald-500/10 px-4 py-1.5 text-xs font-semibold text-emerald-400 mb-8 backdrop-blur-md">
            <Sparkles className="h-3.5 w-3.5" />
            <span>FONMAP v1.0 — Yeni Nesil Fon Analitiği</span>
          </div>

          {/* Ana Başlık */}
          <h1 className="text-4xl sm:text-6xl lg:text-7xl font-black tracking-tight text-white max-w-5xl mx-auto leading-[1.1]">
            Yatırım Fonlarında Gün İçi Belirsizliğe{' '}
            <span className="text-transparent bg-clip-text bg-gradient-to-r from-emerald-400 via-teal-300 to-indigo-400">
              Son Verin.
            </span>
          </h1>

          {/* Alt Açıklama */}
          <p className="mt-6 text-base sm:text-xl text-slate-300 max-w-3xl mx-auto font-normal leading-relaxed">
            KAP resmi portföy dağılım raporlarını yapay zeka ile ayrıştırıyor, Borsa
            İstanbul seans içi canlı fiyatlarıyla fonların getirisini anlık olarak
            ekranınıza getiriyoruz.
          </p>

          {/* CTA Butonları */}
          <div className="mt-10 flex flex-col sm:flex-row items-center justify-center gap-4">
            <Link
              href="/dashboard"
              className="flex w-full sm:w-auto items-center justify-center gap-2.5 rounded-xl bg-gradient-to-r from-emerald-500 to-teal-600 px-8 py-4 text-sm font-bold text-slate-950 shadow-xl shadow-emerald-500/25 transition-all hover:scale-105 hover:shadow-emerald-500/40"
            >
              <Activity className="h-4 w-4" />
              <span>Canlı Takip Masasını Aç</span>
              <ArrowRight className="h-4 w-4" />
            </Link>

            <a
              href="#formuller"
              className="flex w-full sm:w-auto items-center justify-center gap-2 rounded-xl border border-slate-700 bg-slate-900/80 px-8 py-4 text-sm font-semibold text-white backdrop-blur-md transition-all hover:bg-slate-800 hover:border-slate-600"
            >
              <Calculator className="h-4 w-4 text-emerald-400" />
              <span>Matematiksel Formüller</span>
            </a>
          </div>

          {/* Özet Metrikler */}
          <div className="mt-16 grid grid-cols-1 sm:grid-cols-3 gap-6 max-w-3xl mx-auto pt-8 border-t border-slate-800/80">
            <div>
              <p className="text-2xl sm:text-3xl font-mono font-black text-white">7+ Fon</p>
              <p className="text-xs text-slate-400 mt-1">Canlı Takip Edilen</p>
            </div>
            <div>
              <p className="text-2xl sm:text-3xl font-mono font-black text-emerald-400">%98.5</p>
              <p className="text-xs text-slate-400 mt-1">Ortalama Kapsama</p>
            </div>
            <div>
              <p className="text-2xl sm:text-3xl font-mono font-black text-indigo-400">60 Sn</p>
              <p className="text-xs text-slate-400 mt-1">Otonom Yenileme</p>
            </div>
          </div>
        </div>
      </section>

      {/* 2. CANLI ÖNİZLEME KARTLARI (TICKER PREVIEW) */}
      <section className="py-16 bg-[#080c14] border-b border-slate-800/80">
        <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
          <div className="flex flex-col md:flex-row md:items-end justify-between mb-8 gap-4">
            <div>
              <div className="flex items-center gap-2 text-emerald-400 text-xs font-semibold uppercase tracking-wider mb-2">
                <Activity className="h-4 w-4 animate-pulse" />
                <span>Piyasa Seans İçi Anlık Tahmin Vitrini</span>
              </div>
              <h2 className="text-2xl font-bold text-white tracking-tight">
                Takip Edilen Başlıca Fonlar
              </h2>
            </div>
            <Link
              href="/dashboard"
              className="text-xs font-bold text-emerald-400 hover:text-emerald-300 flex items-center gap-1.5 transition-colors self-start md:self-auto"
            >
              <span>Tüm Fonları İncele & Filtrele</span>
              <ArrowRight className="h-3.5 w-3.5" />
            </Link>
          </div>

          {/* Fon Kartları Şeridi */}
          <div className="grid grid-cols-1 md:grid-cols-3 lg:grid-cols-4 gap-4">
            {estimates.slice(0, 4).map((fund) => {
              const isPositive = fund.estimatedReturn >= 0;
              return (
                <div
                  key={fund.fundCode}
                  className="glass-card rounded-2xl p-5 border border-slate-800/90 relative overflow-hidden flex flex-col justify-between"
                >
                  <div>
                    <div className="flex items-center justify-between mb-3">
                      <span className="font-mono text-base font-extrabold text-white bg-slate-800/80 px-2.5 py-1 rounded-lg border border-slate-700">
                        {fund.fundCode}
                      </span>
                      <span className="rounded-full bg-emerald-500/10 px-2 py-0.5 text-[10px] font-semibold text-emerald-400 border border-emerald-500/20">
                        %{((fund.coverageRatio || 0.98) * 100).toFixed(0)} Kapsama
                      </span>
                    </div>

                    <h3 className="text-xs font-semibold text-slate-200 line-clamp-1">
                      {fund.fundTitle}
                    </h3>
                    <p className="text-[11px] text-slate-500 line-clamp-1 mb-4">
                      {fund.manager}
                    </p>

                    <div className="flex items-baseline justify-between pt-3 border-t border-slate-800">
                      <span className="text-[10px] uppercase font-semibold text-slate-400">
                        Tahmini Getiri:
                      </span>
                      <span
                        className={`font-mono text-2xl font-black tabular-nums ${isPositive ? 'text-emerald-400' : 'text-rose-400'
                          }`}
                      >
                        {isPositive
                          ? `+${(fund.estimatedReturn * 100).toFixed(2)}%`
                          : `${(fund.estimatedReturn * 100).toFixed(2)}%`}
                      </span>
                    </div>

                    {/* CANLI BİRİM PAY FİYATI (NOMİNAL ₺ DEĞİŞİMİ) */}
                    <div className="mt-2.5 flex items-center justify-between text-xs font-mono pt-2 border-t border-slate-800/60">
                      <span className="text-[10px] text-slate-400 font-sans uppercase">
                        Birim Pay:
                      </span>
                      {fund.currentPrice != null && fund.estimatedPrice != null ? (
                        <div className="flex items-center gap-1.5 tabular-nums">
                          <span className="text-slate-400">{Number(fund.currentPrice).toFixed(4)} ₺</span>
                          <span className="text-slate-500 font-bold">➔</span>
                          <span className={`font-bold ${isPositive ? 'text-emerald-400' : 'text-rose-400'}`}>
                            {Number(fund.estimatedPrice).toFixed(4)} ₺
                          </span>
                          <span className="h-1.5 w-1.5 rounded-full bg-emerald-400 animate-pulse ml-0.5" title="Canlı Seans Fiyatlaması" />
                        </div>
                      ) : (
                        <div className="flex items-center gap-1.5 text-slate-400 text-[11px]">
                          <span>Canlı Seans Değeri</span>
                          <span className="h-1.5 w-1.5 rounded-full bg-emerald-400 animate-pulse" />
                        </div>
                      )}
                    </div>
                  </div>

                  {/* KULLANICI İSTEĞİ: AÇIK TAHMİN UYARI ROZETİ */}
                  <div className="mt-4 pt-3 border-t border-slate-800/60 text-center">
                    <p className="text-[10px] text-amber-400 font-medium">
                      ⚠️ Ekonometrik model tahminidir; kesin getiri taahhüdü değildir.
                    </p>
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      </section>

      {/* 3. KULLANDIĞIMIZ FORMÜLLER VE METODOLOJİ (KULLANICI TALEBİ) */}
      <section id="formuller" className="py-20 bg-slate-950 border-b border-slate-800/80">
        <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
          <div className="text-center max-w-3xl mx-auto mb-14">
            <div className="inline-flex items-center gap-2 rounded-full border border-indigo-500/30 bg-indigo-500/10 px-3.5 py-1 text-xs font-semibold text-indigo-400 mb-3">
              <Calculator className="h-3.5 w-3.5" />
              <span>Şeffaf & Denetlenebilir Matematik</span>
            </div>
            <h2 className="text-3xl sm:text-4xl font-black text-white tracking-tight">
              Kullandığımız Finansal Formüller & Algoritmalar
            </h2>
            <p className="mt-3 text-sm text-slate-400">
              Sistemimiz kara kutu (black-box) değildir. Seans içi her hesaplama kuruşu
              kuruşuna aşağıdaki akademik ve ekonometrik formüllerle işletilir.
            </p>
          </div>

          {/* Formül Sekmeleri */}
          <div className="grid grid-cols-1 lg:grid-cols-12 gap-8 items-start">
            {/* Sol Sekme Butonları */}
            <div className="lg:col-span-4 space-y-2">
              {formulas.map((f) => (
                <button
                  key={f.id}
                  onClick={() => setActiveFormulaTab(f.id)}
                  className={`w-full text-left p-4 rounded-xl border transition-all ${activeFormulaTab === f.id
                      ? 'bg-slate-800/90 border-emerald-500/40 shadow-lg text-white'
                      : 'bg-slate-900/40 border-slate-800 text-slate-400 hover:bg-slate-900 hover:text-slate-200'
                    }`}
                >
                  <p className="text-xs font-bold">{f.title}</p>
                  <p className="text-[11px] text-slate-500 mt-1 line-clamp-1">
                    {f.desc}
                  </p>
                </button>
              ))}
            </div>

            {/* Sağ Formül Detay Kartı */}
            <div className="lg:col-span-8 glass-card rounded-2xl p-6 sm:p-8 border border-slate-700/80 bg-slate-900/80">
              <h3 className="text-lg font-bold text-white mb-2">
                {formulas[activeFormulaTab].title}
              </h3>
              <p className="text-xs sm:text-sm text-slate-300 leading-relaxed mb-6">
                {formulas[activeFormulaTab].desc}
              </p>

              {/* Okunabilir Tipografik Finansal Formül Gösterimi */}
              <FormulaDisplay formulaId={activeFormulaTab} />
            </div>
          </div>
        </div>
      </section>

      {/* 4. DÖRT AŞAMALI ÇALIŞMA SÜRECİ */}
      <section className="py-20 bg-[#080c14]">
        <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
          <div className="text-center max-w-2xl mx-auto mb-14">
            <h2 className="text-3xl font-black text-white tracking-tight">
              Nasıl Çalışır? (4 Kademeli Akış)
            </h2>
            <p className="mt-2 text-xs text-slate-400">
              KAP'tan indirilen PDF belgesinden gün içi seans sonu mutabakatına kadar
            </p>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-4 gap-6">
            <div className="glass-card rounded-2xl p-5 border border-slate-800">
              <div className="h-10 w-10 rounded-xl bg-blue-500/10 text-blue-400 flex items-center justify-center font-mono font-bold mb-4">
                01
              </div>
              <h3 className="text-sm font-bold text-white mb-1">KAP Raporunu İndir</h3>
              <p className="text-xs text-slate-400 leading-relaxed">
                Robotumuz KAP'taki en son resmi Portföy Dağılım Raporunu bulur, SHA-256
                parmak iziyle indirip Apache PDFBox ile ayrıştırır.
              </p>
            </div>

            <div className="glass-card rounded-2xl p-5 border border-slate-800">
              <div className="h-10 w-10 rounded-xl bg-purple-500/10 text-purple-400 flex items-center justify-center font-mono font-bold mb-4">
                02
              </div>
              <h3 className="text-sm font-bold text-white mb-1">Ağırlık Kayması Düzeltmesi</h3>
              <p className="text-xs text-slate-400 leading-relaxed">
                Seans öncesi (09:50) dün akşamki borsa kapanışlarına göre her hissenin güncel
                ağırlığını hesaplar ve gün boyunca kilitler.
              </p>
            </div>

            <div className="glass-card rounded-2xl p-5 border border-slate-800">
              <div className="h-10 w-10 rounded-xl bg-emerald-500/10 text-emerald-400 flex items-center justify-center font-mono font-bold mb-4">
                03
              </div>
              <h3 className="text-sm font-bold text-white mb-1">Dakikalık Canlı Getiri</h3>
              <p className="text-xs text-slate-400 leading-relaxed">
                Seans boyunca BIST canlı fiyatlarıyla varlık katkıları toplanır; günlük yönetim
                ücreti düşülerek net tahmin üretilir.
              </p>
            </div>

            <div className="glass-card rounded-2xl p-5 border border-slate-800">
              <div className="h-10 w-10 rounded-xl bg-amber-500/10 text-amber-400 flex items-center justify-center font-mono font-bold mb-4">
                04
              </div>
              <h3 className="text-sm font-bold text-white mb-1">Gece TEFAS Mutabakatı</h3>
              <p className="text-xs text-slate-400 leading-relaxed">
                Gece 23:00'te TEFAS resmi kapanış fiyatı açıklanınca model tahminiyle
                kıyaslanır; MAE, RMSE ve başarı karnesi üretilir.
              </p>
            </div>
          </div>
        </div>
      </section>
    </div>
  );
}

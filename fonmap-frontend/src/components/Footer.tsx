import React from 'react';
import Link from 'next/link';
import { ShieldAlert, Database, Cpu, Award } from 'lucide-react';

export default function Footer() {
  return (
    <footer className="w-full border-t border-slate-800/80 bg-slate-950 text-slate-400">
      <div className="mx-auto max-w-7xl px-4 py-12 sm:px-6 lg:px-8">
        {/* SPK YASAL UYARI KUTUSU */}
        <div className="mb-10 rounded-2xl border border-amber-500/20 bg-amber-500/5 p-5 text-xs leading-relaxed text-slate-300">
          <div className="flex items-start gap-3">
            <ShieldAlert className="h-5 w-5 shrink-0 text-amber-400 mt-0.5" />
            <div>
              <span className="font-semibold text-amber-300">SPK YASAL UYARI VE BİLGİLENDİRME:</span>{' '}
              Bu platformda yer alan yatırım fonu tahmini getiri oranları, portföy dağılımları ve
              modelleme analizleri yalnızca bilgilendirme, ekonometrik takip ve akademik/teknik
              araştırma amaçlıdır. Sermaye Piyasası Kurulu (SPK) mevzuatı kapsamında hiçbir surette{' '}
              <strong>yatırım danışmanlığı</strong> veya alım-satım tavsiyesi niteliği taşımaz.
              Yatırım kararlarınızı yetkili aracı kurumlar ve resmi TEFAS/KAP ilanları üzerinden
              almanız tavsiye edilir.
            </div>
          </div>
        </div>

        {/* ALT BİLGİ VE LİNKLER */}
        <div className="grid grid-cols-1 gap-8 md:grid-cols-4">
          <div className="space-y-3">
            <div className="flex items-center gap-2">
              <span className="text-lg font-bold text-white tracking-tight">
                FON<span className="text-emerald-400">MAP</span>
              </span>
            </div>
            <p className="text-xs text-slate-400 leading-normal">
              Yatırım fonlarında gün içi getiri belirsizliğini kaldıran, KAP portföy dağılımlarını
              canlı BIST tahtası ile eşleyen yeni nesil FinTech analitik platformu.
            </p>
          </div>

          <div>
            <h3 className="text-xs font-semibold uppercase tracking-wider text-slate-200 mb-3">
              Veri Kaynakları
            </h3>
            <ul className="space-y-2 text-xs">
              <li className="flex items-center gap-2">
                <Database className="h-3.5 w-3.5 text-emerald-400" />
                <span>KAP — Kamuoyu Aydınlatma Platformu</span>
              </li>
              <li className="flex items-center gap-2">
                <Cpu className="h-3.5 w-3.5 text-teal-400" />
                <span>Borsa İstanbul (BIST Pay & VİOP)</span>
              </li>
              <li className="flex items-center gap-2">
                <Award className="h-3.5 w-3.5 text-indigo-400" />
                <span>TEFAS — Takasbank Fon Platformu</span>
              </li>
            </ul>
          </div>

          <div>
            <h3 className="text-xs font-semibold uppercase tracking-wider text-slate-200 mb-3">
              Sayfalar & İşlemler
            </h3>
            <ul className="space-y-2 text-xs">
              <li>
                <Link href="/" className="hover:text-emerald-400 transition-colors">
                  Platform Tanıtım Vitrini
                </Link>
              </li>
              <li>
                <Link href="/dashboard" className="hover:text-emerald-400 transition-colors">
                  Canlı Takip Masası (Dashboard)
                </Link>
              </li>
              <li>
                <Link href="/admin" className="hover:text-emerald-400 transition-colors">
                  Yönetim & KAP Otomasyon Portalı
                </Link>
              </li>
            </ul>
          </div>

          <div>
            <h3 className="text-xs font-semibold uppercase tracking-wider text-slate-200 mb-3">
              Metodoloji
            </h3>
            <p className="text-xs text-slate-400 leading-relaxed">
              Model, en son resmi PDF portföy dağılımını ayıklar; seans öncesi dün akşamki kapanışlara
              göre <strong>Ağırlık Kayması (Weight Drift)</strong> düzeltmesini uygular ve seans boyu
              dakikalık olarak BIST canlı fiyatlarıyla ağırlıklı getiri üretir.
            </p>
          </div>
        </div>

        <div className="mt-8 border-t border-slate-800/60 pt-6 flex flex-col sm:flex-row items-center justify-between text-xs text-slate-400 gap-4">
          <p>© 2026 FONMAP. Tüm hakları saklıdır.</p>
        </div>
      </div>
    </footer>
  );
}

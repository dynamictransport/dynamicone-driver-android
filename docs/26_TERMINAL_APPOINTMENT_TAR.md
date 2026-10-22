# 26 — Liman terminali duraklarında TAR kodu (uygulama tarafı)

Durum: **backend canlıda hazır (22.10.2026, kullanıcı bildirimi); uygulama tarafı aynı gün combined çalışma ağacında
yazıldı** (ASSUMPTIONS T1–T9). Sözleşme Driver API (`/api/driver/v1`) üzerindedir; `backend/openapi.yaml` durak
şemasını içermediği için alanlar burada kayıt altındadır. Yeni uç, yeni push türü ve yeni yazma işlemi yoktur.

Kullanıcı isteği (22.10.2026): sefer detayındaki liman terminali duraklarında planlamacının kaydettiği TAR kodunu
(terminal randevu referansı) ve geçerli olduğu tarih/saat aralığını, durumunu ve geri sayımını göster. Şoför için
salt okunurdur: ekleme, düzenleme, silme yoktur. TAR planlamacının kaydıdır; Portbase rezervasyonu veya terminalin
onayı gibi sunulmaz.

## 1. API

Mevcut cevaplara nullable `tar` nesnesi eklendi (aynı Bearer token; şoför yalnız kendi seferlerini görür, yabancı
sefer `403` → oturum/erişim hatası olarak işlenir):

| Uç | Alan |
|---|---|
| `GET trips/{tripId}` | `stops[].tar` |
| `GET trips/{tripId}/stops` | `[].tar` |

```json
{
  "id": "11111111-1111-1111-1111-111111111111",
  "sequence": 1,
  "location": "Örnek terminal",
  "status": "Planned",
  "tar": {
    "code": "001234",
    "startUtc": "2026-10-22T08:00:00Z",
    "endUtc": "2026-10-22T10:00:00Z",
    "timeZone": "Europe/Amsterdam"
  }
}
```

| Alan | Anlam |
|---|---|
| `tar` | Kayıt varsa nesne, yoksa `null` (eski cevaplarda alan hiç yoktur; ikisi de "TAR yok"). |
| `code` | Metin; baştaki sıfırlar korunur, sayıya çevrilmez. |
| `startUtc` | Başlangıç **dahil**, UTC ISO 8601. |
| `endUtc` | Bitiş **hariç**, UTC ISO 8601. |
| `timeZone` | Terminalin IANA saat dilimi; ekrandaki tarih/saat bununla biçimlenir. |

Kod boşsa, `startUtc`/`endUtc` ayrıştırılamıyorsa veya `timeZone` tanınmıyorsa kayıt **TAR yok** sayılır
(`DriverApiMapping.terminalAppointment`); eksik parça için sahte kod, saat veya sayaç üretilmez.

## 2. Ekran

Sefer detayındaki *Duraklar* kartında, ilgili durağın (API `id` ile eşleşen) satırında bir TAR bloğu
(`TarBlock`, `stop_tar_{stopId}` test etiketi):

- Kod: büyük, eş aranlıklı düz metin; yanında 48 dp kopyala düğmesi (panoya düz metin).
- Aralık: terminal saat diliminde `22.10.2026 10:00 – 12:00`; gece yarısını aşarsa iki tarih:
  `22.10.2026 23:30 – 23.10.2026 01:00`. Altında *Terminal saati · Amsterdam* satırı (IANA kimliğinin şehir adı),
  telefonun saat dilimi farklı olsa da terminalin yerel saati gösterilir. Yaz/kış saati platform kurallarıyla.
- Durum (icon + metin + renk, yalnız renk değil), UTC anları üzerinden:

| Koşul | Durum | Ton |
|---|---|---|
| `now < startUtc` | *TAR henüz başlamadı* + başlangıca geri sayım | bilgi (mavi) |
| `startUtc ≤ now < endUtc` | *TAR geçerli* + bitişe geri sayım | olumlu (yeşil) |
| bitişe ≤ 30 dk | *TAR bitmek üzere* + bitişe geri sayım | uyarı (sarı/turuncu) |
| `now ≥ endUtc`, durak açık | *TAR süresi doldu* | tehlike (kırmızı) |
| durak `Completed`/`Skipped` | *Geçmiş TAR*, sayaç yok | nötr |

- Geri sayım `S:DD:SS` biçiminde cihazda saniyede bir ilerler; saniyede istek atılmaz.

## 3. Zaman

- Karşılaştırma UTC `Instant` ile; biçimleme `tar.timeZone` ile (`DriverFormats.tarWindow`).
- Cihaz saati sapması: her API cevabının HTTP `Date` başlığı `ServerTimeSync` ile okunur (gidiş-dönüşün orta
  noktasına göre; 1 sn çözünürlük nedeniyle 2 sn altı fark sıfır sayılır, 30 sn'den uzun süren cevap yok sayılır).
  Sayaçlar `cihaz saati + offset` ile hesaplanır. Cevap görülmeden önce offset sıfırdır.

## 4. Yenileme ve çevrimdışı

- Sefer ekranı açılınca, uygulama ön plana dönünce ve ekran açıkken her 60 sn'de sefer yeniden okunur
  (`TripDetailViewModel.onResume/onPause`, tek döngü; ekrandan çıkınca iptal). Açılıştaki okuma ilk `onResume`'u
  karşılar, iki kez okunmaz.
- Başarılı cevapta değişen veya kaldırılan TAR karta yansır (bellekteki sefer tamamen sunucu cevabıyla değişir).
- Başarısız yenilemede son başarılı veri ve *son güncelleme* saati korunur; ekranın üstünde sarı bant:
  *Bağlantı yok · son güncelleme 10:42* / *Güncellenemedi · son güncelleme 10:42* (+ *Tekrar dene*). Döngü
  yenilemeleri her dakika mesaj göstermez; yalnız bant.
- Backend'de TAR için push yoktur; mevcut plan yenilemesiyle yetinilir.

## 5. Kabul kontrolleri → testler

| Kontrol | Test |
|---|---|
| TAR yok / alan eksik / bozuk alan | `DriverApiMappingTest`, `RealApiRepositoriesTest`, `TarScreenTest` |
| Baştaki sıfırlı kod | `DriverApiMappingTest`, `TarScreenTest` |
| Başlangıç öncesi, aktif, son 30 dk, tam bitiş anı | `TerminalAppointmentTest`, `TarScreenTest` |
| Gece yarısını aşan aralık, farklı saat dilimi, kış saati | `TarFormatsTest`, `TerminalAppointmentTest`, `TarScreenTest` |
| Tamamlanmış/atlanmış durak | `TerminalAppointmentTest`, `TarScreenTest` |
| Aynı terminalde iki durak, TAR değişimi/kaldırılması | `RealApiRepositoriesTest` |
| Başarısız yenileme, bayat veri | `TripDetailViewModelTest`, `RealApiRepositoriesTest`, `TarScreenTest` |
| Arka plana gidip gelme, tek döngü | `TripDetailViewModelTest` |
| Sunucu saati düzeltmesi | `ServerTimeSyncTest`, `TripDetailViewModelTest` |
| Yabancı sefer (403) | mevcut `RealApiRepositoriesTest` (`SESSION_EXPIRED` eşlemesi) |

## 6. Bitişe kalan süre uyarıları (22.10.2026, ikinci istek)

Kullanıcı isteği: TAR bitişine **60, 30, 15 ve 5 dakika** kala sesli ve görsel uyarı.

- Uyarılar cihazın `AlarmManager`'ında kurulur (`TarWarningController`, `core/tar`), ağ olmadan da çalar. Her uyarı
  `tripId/stopId/lead` anahtarıyla Room `tar_warnings` tablosunda (v8) tutulur; yeniden başlatma, saat/dilim
  değişikliği ve uygulama güncellemesi sonrası `TarWarningRescheduleReceiver` kurulu uyarıları yeniden kurar.
- Kaynak bellekteki seferlerdir: her sefer okuması (ekran açılışı, 60 sn döngü, liste) uyarıları eşler. Taşınan pencere
  yeniden kurar; kaldırılan TAR, tamamlanan/atlanan durak, biten sefer veya listeden düşen sefer iptal eder. Yalnız
  listeden bilinen (durakları okunmamış) sefer ve boş liste kararsızdır: kayıtlı uyarılara dokunulmaz. Oturum
  kapanınca (`RealSessionData.clearServerData`) hepsi iptal edilir.
- Geçmiş eşikler sonradan çalmaz: 20 dk kala öğrenilen TAR için yalnız 15 ve 5 dk uyarıları kurulur.
- Alarm çalınca sefer yeniden kontrol edilir: durak hâlâ açık ve aynı pencereyse çalar; süreç yeniden başlamışsa
  sefer önce sunucudan okunur, sunucuya ulaşılamazsa kayıtlı veriyle çalar. Pencere bittiyse hiç çalmaz.
- Bildirim: kanal `tar_warning` (alarm sesi `USAGE_ALARM`, titreşim, yüksek önem), başlık *TAR 001234 · 30 dk kaldı*,
  metin *ECT Delta · bitiş 12:00 (Amsterdam)*, kilit ekranında görünür, dokununca sefer detayı açılır
  (`TYPE_TAR_WARNING`). Durak başına tek bildirim: 30 dk uyarısı 60 dk olanın yerine geçer. Tam ekran alarm ve
  ısrarlı çalma yoktur; ses bir kez çalar.
- Cihaz saati sapması: alarm anı = sunucu tetik anı − `ServerClock` offset'i.

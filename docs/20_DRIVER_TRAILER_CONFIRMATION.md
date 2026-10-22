# Sefer kabulünden sonra dorse onayı

> Kaynak: backend `dynamictransport/DynamicONE`, `docs/api/17_DRIVER_TRAILER_CONFIRMATION.md`
> (21.09.2026) ve `DriverOperationsApiController` / `DriverExecutionService.Trailer.cs`.
> Uygulamadaki kararlar: `ASSUMPTIONS.md` T1–T5.
> Numara bu depoda 20'dir; 17, 18 ve 19 başka özelliklerde kullanıldı.

Şoför seferi kabul ettikten sonra, sefere başlamadan önce çekicinin altındaki dorseyi
**ofisin aktif dorse listesinden seçerek** onaylar. Fotoğraf, OCR ve serbest plaka girişi
yoktur; uygulama yeni dorse yaratamaz.

## Akış

1. `POST /api/driver/v1/trips/{tripId}/accept` (mevcut kabul akışı).
2. `GET /api/driver/v1/trips/{tripId}/trailers` → `tripId`, `version`, `plannedTrailerId`,
   `confirmedTrailerId`, `confirmedAtUtc`, `canConfirm`, `trailers[{id, registrationNumber, companyCode}]`.
3. Şoför listeden **açıkça** seçer. Planlanan dorse yalnızca *Planlanan* etiketiyle işaretlenir
   ve listenin başına alınır; otomatik seçilmez.
4. `canConfirm=true` iken `POST /api/driver/v1/trips/{tripId}/trailer-confirmation`
   `{ "trailerId": "...", "expectedVersion": "GET yanıtındaki version" }`.
5. Başarıdan sonra hem `execution` hem dorse bilgisi yeniden okunur; **Sefere başladım**
   aksiyonu ancak yeni sürümle gönderilir.

`execution.trailerConfirmationRequired=true` veya `Start` aksiyonunun
`blockingReason="trailer_confirmation_required"` olması, sefer ekranında başlatma düğmesi
yerine **DORSEYİ SEÇ** adımını gösterir. Engellenen aksiyon hiçbir koşulda sunucuya gönderilmez.

## Uygulama tarafı

| Katman | Dosya |
| --- | --- |
| Uçlar | `core/network/DriverApi.kt` (`trailers`, `confirmTrailer`) |
| Sözleşme modelleri | `core/network/DriverOperationsDtos.kt` (`DriverTrailerStateDto`, `DriverTrailerOptionDto`, `DriverTrailerCommandDto`, `canExecute`/`blockingReason`, `trailerConfirmationRequired`) |
| Alan modeli | `domain/model/Trailer.kt`, `domain/model/Execution.kt` (`ActionBlock`) |
| Veri | `data/real/RealTripExecutionRepository.kt`, `data/real/DriverApiMapping.kt` |
| Ekran | `feature/trailer/ConfirmTrailerFeature.kt`, sefer ekranı `feature/tripdetail/` |
| Metinler | `res/values*/strings_trailer.xml` (en, tr, pl) |

Ekran kuralları (`CLAUDE.md` §7):

- Seçim büyük satırlarla yapılır (en az 64dp, plaka monospace), tek baskın aksiyon
  **DORSEYİ ONAYLA**'dır ve seçim yapılmadan etkin olmaz.
- Liste 8 kayıttan uzunsa plaka/şirket kodu üzerinde istemci içi arama alanı görünür;
  kısa listede yazı yazmak gerekmez.
- Onaylı dorse hem bu ekranda (yeşil şerit, saat) hem sefer detayında
  *Dorse (onaylandı)* satırında görünür. Planlanan dorse onaylanmış gibi gösterilmez.
- Liste boşsa onay kapalıdır: “Ofisi arayın. Uygulama yeni dorse ekleyemez.”
- Sefer başladıysa (`canConfirm=false`) ekran yalnızca kaydı gösterir, değiştirme sunulmaz.

## Hatalar ve belirsizlik

- Onay **çevrimdışı kuyruğa alınmaz**: istek, okunan listenin `version` değerini taşır.
  Bağlantı yoksa şoföre bağlantı hatası gösterilir, eski sürümle sonradan gönderim yapılmaz.
- 409 `conflict` → liste ve sefer yeniden okunur, şoför yeniden seçer; eski seçim otomatik
  tekrarlanmaz.
- 400 `invalid_trailer` (pasifleşmiş dorse) → liste yeniden okunur.
- Bağlantı belirsizse yeni bir yazma denenmez; `GET .../trailers` ile son onay kontrol edilir
  (sunucu aynı seçimi ikinci tarihçe kaydı yaratmadan kabul eder).
- Listede olmayan veya `canConfirm=false` olan bir seçim sunucuya hiç gönderilmez.

## Kapsam dışı

- Demo (simülatör) backend'i bu ucu taşımaz; demo modunda dorse onayı “bağlı değil” durumundadır.
- Sefer ortasında dorse değişimi bu uç ile yapılmaz.
- Migration (`20260921105414_DriverTrailerConfirmation`) ve backend yayını ayrı onay ister;
  uygulama migration uygulanmış bir backend'e karşı çalışır.

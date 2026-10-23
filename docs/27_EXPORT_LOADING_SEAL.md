# 27 — Export yüklemesinde mühür numarası ve mühür fotoğrafı (uygulama tarafı)

Durum: **backend sözleşmesi `main`'e birleşti (dynamictransport/DynamicONE PR #608, merge 453bcb33, 23.10.2026,
TH-2026-0519); uygulama tarafı aynı gün combined çalışma ağacında yazıldı** (ASSUMPTIONS S1–S12). Backend sözleşmesi:
`docs/api/18_TRIP_STEP_EVIDENCE.tr.md` §"Export yükleme ve mühür zorunluluğu" ve `docs/72_DriverAppOperations.md`
§"Export loading and seal number". Yeni uç, yeni push türü ve veritabanı migration'ı yoktur; mevcut `execution`,
`actions` ve `document-slots` uçları kullanılır.

Kullanıcı kararı (23.10.2026): Geçiş döneminde Export yükleme durağını **planlamacı kapatır**. Uygulama bu durakta
*Durağı tamamla* sunmaz ve hiçbir `CompleteStop` isteği göndermez; mühür metni ve fotoğrafı kaydedildiğinde
*Mühür bilgileri kaydedildi. Planlamacı onayı bekleniyor.* gösterir. Durak yalnız sunucudan tamamlandığı okununca
kapanmış sayılır. Backend'de şoförün `CompleteStop` yetkisi kaldırılmamıştır; mobildeki gizleme bir sunucu
yetkilendirme kuralı değildir.

## 1. API (mevcut uçlar, yeni alanlar)

`GET trips/{tripId}/execution` → `availableActions[]` iki yeni `code` taşır; `documents[]` `type="Seal"` slotları
zaten vardı.

| Eylem | Kapsam | Ne zaman | Ek alanlar |
|---|---|---|---|
| `ConfirmLoading` | `stopId`, `containerId=null` | Export `Load` durağında yükleme onayı yokken | `stopName` |
| `RecordSealNumber` | `stopId` + `containerId` | Onaydan sonra, tamamlanmamış her konteyner için (mühür kayıtlıyken de) | `sealNumber` (sunucudaki mevcut mühür), `containerNumber`, `stopName` |
| `CompleteStop` | her konteyner | her zaman listelenir, `canExecute=false` + `blockingReason` | `loading_confirmation_required` / `seal_number_required` |

`POST trips/{tripId}/actions` gövdesi mevcut `DriverActionCommandDto` + `sealNumber` (yalnız `RecordSealNumber`;
diğer eylemlerde alan hiç yazılmaz, eski komutların payload'u değişmez). `ConfirmLoading` gövdesinde `containerId`
null olduğu için alan yazılmaz (`explicitNulls=false`); backend `Guid?` için eşdeğerdir.

```json
{"commandId":"<uuid>","expectedVersion":"<execution.version>","action":"RecordSealNumber","stopId":"<stopId>","containerId":"<containerId>","sealNumber":"SEAL-12345"}
```

Mühür fotoğrafı: `POST trips/{tripId}/document-slots` (multipart `slot` + `document`, JPEG/PNG, ≤10 MiB) ile
`documents[]` içindeki `type="Seal"` ve aynı `stopId`/`containerId` slotuna. Mevcut belge akışı (docs/18) olduğu
gibi kullanılır; yükleme `version`'ı değiştirmek zorunda değildir, sonuç slotun `documentId`/`revision`/`state`
değerinden okunur.

Hata kodları (`ProblemDetails.code`): `400 invalid_seal_number` → *mühür numarası geçersiz* (komut silinir,
düzeltme yeni UUID ile); `409 seal_number_required` / `loading_confirmation_required` → mühür/yükleme onayı
bekleniyor (komut silinir, plan yeniden okunur); `409 conflict`, `required_documents_missing`,
`trailer_confirmation_required` mevcut davranış. 401/403/429 mevcut davranış.

## 2. Uygulama modeli

- `ExecutionActionCode.CONFIRM_LOADING`, `RECORD_SEAL_NUMBER`; `ExecutionAction.blockingReason: ExecutionBlock?`
  (`TRAILER_CONFIRMATION_REQUIRED`, `LOADING_CONFIRMATION_REQUIRED`, `SEAL_NUMBER_REQUIRED`, `UNKNOWN`),
  `sealNumber`, `containerNumber`, `stopName`.
- `TripExecution.exportLoadingStopId`: `ConfirmLoading`/`RecordSealNumber` sunulan ya da `CompleteStop`'u mühür
  kapısıyla engellenen durak. `loadingConfirmation`, `sealEntries` (konteyner başına `SealEntry` = eylem + `Seal`
  slotu). `blockingSlots` bu durağın mühür fotoğraflarını tekrar listelemez (kartlarda gösterilir).
- `SealEntry.sealNumberRecorded` (sunucu `sealNumber` dolu) ve `photoDone` (`PendingReview`/`Approved`/
  `NotRequired` veya slot yok) birbirinden bağımsızdır; `complete` ikisi birden.
- `PendingTripAction.sealNumber`: Room `pending_commands.payloadJson` içinde saklanır (şema değişmedi); aynı
  `commandId` ile aynı payload yeniden gönderilir.
- `RealTripExecutionRepository.recordSealNumber(tripId, action, sealNumber)`: `DriverInputRules.validateSealNumber`
  (kırpılmış 1–100 karakter, kontrol karakteri yok; harf büyüklüğü değişmez) → tek bekleyen komut kuralı →
  güncel plandaki eylem → komut kaydı → gönderim. `submit()` Export yükleme durağının `CompleteStop`'unu
  `NOT_ALLOWED` ile reddeder (UI zaten göstermez).

## 3. Ekran (sefer detayı)

1. **Yükleme onayı**: eylem çubuğunda *Yükleme fiziksel olarak bittiğinde onaylayın. Bu durağı planlamacı
   kapatır.* + basılı tutmalı **YÜKLEME TAMAMLANDI** (`primary_action`). Başarıda *Yükleme onaylandı. Her
   konteynerin mühür numarasını ve mühür fotoğrafını kaydedin.* Sunucu mühür eksikse hatırlatma mesajını ve
   `driver.message` push'unu kendisi oluşturur; uygulama tekrar göndermez.
2. **Mühür kartları** (`seal_card_{containerId}`), içerikte en üstte, konteyner başına:
   - başlık *Mühür · Konteyner <no>* + durak adı;
   - mühür numarası: *Kaydedildi: X* (yeşil) / *Mühür numarası eksik* (sarı); tek satır metin alanı
     (`seal_input_*`, 100 karakter sayacı) + **MÜHÜR NUMARASINI KAYDET** (`seal_save_*`, yalnız geçerli ve
     sunucudakinden farklı değerde etkin);
   - mühür fotoğrafı: *Fotoğraf eksik* / *Fotoğraf gerekli değil* / *Yüklendi, inceleme bekliyor* / *Onaylandı* /
     *Reddedildi — yeniden yükleyin* (+ `reviewNote`) / *Bu planda fotoğraf alanı yok*; düğme
     (`seal_photo_upload_*`) mevcut belge ekranını o slotla açar (kamera/galeri, önizleme ve yeniden çekme orada);
   - ikisi de tamamsa *Mühür bilgileri tamamlandı* rozeti; bekleyen (`OUTCOME_UNKNOWN`/`SENDING`) mühür komutu
     kendi kartında gösterilir, genel *Aynı işlemi tekrar gönder / Vazgeç* paneli olduğu gibi.
3. **Planlamacıya devir**: eylem çubuğunda tüm kartlar tamamsa *Mühür bilgileri kaydedildi. Planlamacı onayı
   bekleniyor.*; değilse *Her konteynerin mühür numarasını ve mühür fotoğrafını kaydedin.* Durağın `CompleteStop`
   düğmesi bu durakta hiç çizilmez. Mevcut 60 sn yenileme ve ön plana dönüş okuması durağın kapandığını
   sunucudan getirir; kapanınca sonraki durağın normal adımları görünür.
4. Diğer sefer türleri: `exportLoadingStopId` null ise hiçbir şey değişmez (mevcut testler aynen geçer).

## 4. Testler

- `SealNumberRulesTest`, `ExportSealMappingTest`, `ExportSealRepositoryTest` (MockWebServer: onay gövdesi,
  konteyner başına ayrı komut ve sürüm, `CompleteStop` hiç gönderilmez, geçersiz metin istek öncesi reddi,
  `invalid_seal_number`, 409 kodları, yanıt kaybında aynı UUID/payload ile tekrar, yeniden başlatma sonrası
  saklanan `sealNumber`), `ExportSealViewModelTest`, `ExportSealScreenTest` (Robolectric Compose; TR metinleri).
- Fiziksel cihaz kabulü: bkz. teslim raporu; mühür fotoğrafı ve push için gerçek cihaz gerekir.

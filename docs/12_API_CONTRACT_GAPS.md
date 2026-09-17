# API Contract Gaps (backend/openapi.yaml v0.1.0)

Bu doküman Sprint 1 sırasında `backend/openapi.yaml` ile uygulama ihtiyaçları arasında görülen boşlukları ve **önerileri** listeler.
Hiçbir endpoint uydurulmamıştır: uygulama yalnızca kontrattaki endpoint'leri çağırır. Öneri gerektiren yerler kodda `TODO(contract)` ile işaretlidir.

## 1. Auth

| Konu | Durum | Öneri |
|---|---|---|
| `POST /auth/refresh` | Request/response şeması yok | Request: `{ "refreshToken": string }`, response: `AuthResponse`. Uygulama bu şekli varsayıyor (`RefreshTokenRequestDto`). |
| `POST /auth/logout` | Yok | Refresh token'ı sunucuda iptal eden endpoint (05_SECURITY “server-side revocation”). |
| Hata modeli | Tanımsız | RFC 7807 `application/problem+json` + makine okunur `code` alanı. |

## 2. Ortak header'lar

`X-App-Version` ve `X-Platform: android` (06_BACKEND_INTEGRATION) gönderiliyor ama openapi'de tanımlı değil.
Uygulama ayrıca şunları gönderiyor (isimler öneri):
- `X-Correlation-Id`: istek başına UUID
- `X-Installation-Id`: kurulum başına UUID (donanım kimliği değil)

## 3. Idempotency ve çakışma

- Aynı `clientActionId` ikinci kez geldiğinde beklenen cevap tanımsız. **Öneri:** ilk cevabı (200 + güncel kaynak) aynen dön, 409 dönme.
- `409` gövdesi yok. **Öneri:** `{ "code": "ILLEGAL_TRANSITION", "currentState": TripState, "trip": TripDetail }`.

## 4. Shift

- `POST /shift/start` ve `/shift/end` cevap gövdesi yok → uygulama ardından `GET /shift/current` çağırıyor. **Öneri:** `Shift` dön.
- Araç/dorse seçimi ve checklist alanları yok (01_DRIVER_JOURNEY A).
- Mesai özeti (çalışma süresi, sefer sayısı) yok.

## 5. Trips

- `GET /trips?status=` serbest string; sekme eşlemesi yok, sayfalama ve `updatedSince` (delta sync) yok.
- `TripSummary` içinde `breakState` ve `nextAllowedActions` yok → uygulama her aktif sefer için ayrıca `GET /trips/{id}` çağırıyor.
- Timeline için olay geçmişi/zaman damgaları yok (uygulama yalnızca cihazda kaydedilen zamanları gösteriyor).
- Planlamacı iletişim bilgisi, koordinatlar, ETA ve mesafe yok.
- Reddetme için `reasonCode` enum'u yok. **Öneri:** `VEHICLE_PROBLEM | LEGAL_DRIVING_TIME | HEALTH | WRONG_ASSIGNMENT | OTHER`.
- Reddedilen seferin sonraki durumu tanımsız (`REJECTED` yok). Uygulama local olarak `CANCELLED` gösteriyor.
- Backend adımı engellediğinde (ör. zorunlu evrak eksik) neden alanı yok. **Öneri:** `blockedReasons: [{ action, code }]`.
- Mola backend entegrasyon dokümanında ayrı alan olarak geçiyor, openapi'de trip action; seferi olmayan mesaide mola desteklenmiyor.

## 6. Documents

- Upload akışı: signed URL için HTTP metodu ve zorunlu header'lar tanımsız (Azure Blob `PUT` + `x-ms-blob-type: BlockBlob`).
- `finalize` gövdesi yok. **Öneri:** `{ sizeBytes, sha256 }`.
- `TripDocument` içinde `clientActionId` ve red sebebi yok.
- Sefer için zorunlu evrak listesi yok (“Eksik” / “Teslimatta gerekli” gösterimi için).
- A1 şoförün kişisel belgesi; sefer evrakı mı personel belgesi mi netleşmeli.
- `thumbnailUrl` imzalı/kısa ömürlü mü belirtilmeli.

## 7. Eksik endpoint alanları

- Notifications: device register (FCM token), message list, read.
- Incident medya eki.
- Earnings detay.
- Profil: telefon, şirket, dil güncelleme, personel belge geçerlilikleri.

## 8. Şema biçimi

Dosya OpenAPI **3.1** ama `nullable: true` (3.0 sözdizimi) kullanıyor. 3.1'de `type: [string, "null"]` olmalı; kod üretim araçları yanlış yorumlayabilir. Uygulama DTO'ları elle yazıldı.

---

# Driver App operasyon sözleşmesi (TH-2026-0439) — açık konular

Uygulama `docs/api/Driver-App-Claude-Handoff.tr.md` kılavuzuna ve PR #83 kaynak koduna göre yazıldı (bkz. `ASSUMPTIONS.md` O1–O14). Hiçbir endpoint veya alan uydurulmadı. Aşağıdakiler backend ekibine öneri/soru olarak açıktır:

| # | Konu | Durum / öneri |
|---|---|---|
| 1 | Yayın | 14.09.2026: migration `20260913153636_DriverAppOperations` uygulandı; yerel geliştirme backend'i (üretim veritabanına bağlı) yeni uçlar ve TH-2026-0440 gelen kutusu düzeltmesiyle çalışıyor, gerçek cihazla sefer, adım planı ve mesaj okuması denendi. Üretim yayını ve güncel OpenAPI karşılaştırması açık. |
| 2 | `CompleteStop` etiketi | Yerelleştirme İngilizce `label` metnine bağlı. Öneri: makine okunur bir `labelCode` (ör. `PickupFull`, `LoadingCompleted`) alanı. Bilinmeyen etiket şimdilik olduğu gibi gösteriliyor. |
| 3 | `POST messages` yanıtı | Mesaj nesnesi dönmüyor. Client, backend kodundaki “mesaj id = `commandId`” davranışıyla yerel kaydı eşleştiriyor; sözleşmede açıkça yazılmalı veya yanıtta `messageId` dönmeli. |
| 4 | Evrak yükleme idempotency | `document-slots` için `commandId` yok; sonucu belirsiz yüklemede client listeyi yeniden okuyor, ancak şoför aynı dosyayı yeniden yüklerse yeni revizyon oluşur. Öneri: isteğe bağlı `commandId` alanı. |
| 5 | Bildirim | 15.09.2026: FCM cihaz kaydı ve mesaj push'u backend'de yayında (TH-2026-0444) ve uygulama cihazı kaydediyor (`docs/13_PUSH_NOTIFICATIONS.md`); üretimde gönderimin etkinleştirilmesi backend'de ayrı adım. WebSocket/SSE ve toplu okunmamış sayısı yok; açık sohbet ve sohbet listesi ekrandayken polling yapılıyor (120 istek/dk sınırı içinde). Okunmamış sayısı gelen kutusunun en yeni 100 mesajından hesaplanıyor. |
| 6 | Konum kanıtı | Sözleşme GPS alanlarını destekliyor; uygulama şimdilik konum izni istemiyor ve göndermiyor (ürün kararı gerekir). |
| 7 | `execution` yeni atamada | `Confirmed` atamada seçenekler boş döner; client kabulü ayrı uçla yapıp sonra planı okuyor. Kabul yanıtında güncel `execution` dönmesi bir istek azaltır. |
| 8 | Bordro 404 | Personel profili yoksa 404 `not_found`; client bunu genel hata olarak gösteriyor (“yayınlanmadı” değil). |
| 9 | 409 nedenleri | Client yalnızca HTTP durumu ve `code` kullanıyor. `conflict` altında birden çok neden var (plan değişti, başka sefer devam ediyor, önce kabul edin); ayrı `code` değerleri şoföre daha doğru mesaj verir. |
| 10 | Mesaj göndereni | Çözüldü. 15.09.2026: `DriverMessageItem.senderDisplayName` yayında (TH-2026-0450): mesajı yazanın adı-soyadı, Cockpit sohbetindeki `Sender` ile aynı kaynak (gelen mesajda yazan kullanıcının `DisplayName`'i, şoför mesajında sürücünün `FullName`'i); bilinmiyorsa `null`. Uygulama alanı okuyor: gelen mesajda ad-soyad, alan yok veya boşsa "Ofis"; şoförün kendi mesajı "Siz". |
| 11 | Net ücret | `me/payroll` yanıtında net maaş, bankaya ödenen tutar veya yasal kesinti kalemleri (vergi, sosyal sigorta) yok; `balanceBeforeStatutoryDeductions` net değildir. Uygulama son rakamı büyük ama "Yasal kesintiler öncesi kalan" adıyla gösteriyor. Öneri: HR yayınladığında `netPay` (şoförün eline geçen) ve/veya `paidAmount` (bankaya ödenen) ile yasal kesinti satırları. |
| 12 | Eski akışta takılan sefer | Eski durum bildirimleri olan ve hiç action receipt'i olmayan sefer `LegacyStatuses` kalıyor; beş eski adım (`DeliveryCompleted`) bitince tamamlanmamış duraklar olsa da ilerleme seçeneği kalmıyor (ör. DT-2026-000012-01, 14.09.2026). Öneri: eski akış bittiğinde ve tamamlanmamış durak varken `execution` `TripActions` dönsün ve sıradaki durak için `CompleteStop` sunsun — yeni endpoint yok, ayrıntı `docs/14_LEGACY_TRIP_CONTINUATION.md`. |

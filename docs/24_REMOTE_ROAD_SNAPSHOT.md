# 24 — Uzaktan yol görüntüsü (backend sözleşme önerisi)

Durum: **backend canlıda (21.10.2026, TH-2026-0504; backend `docs/RemoteRoadSnapshots.md`)**, alan ve kod adları
değişmedi. Uygulama tarafı aynı gün combined worktree'de yapıldı (ASSUMPTIONS S1–S8); Robolectric/MockWebServer ile test
edildi, tablette gerçek istekle doğrulama bekliyor. Backend'in eklediği kurallar: `GET` kapalı istekte `409`; fotoğraf
yalnızca EXIF'siz JPEG, ≤ 614400 bayt, uzun kenar ≤ 1600 px (`413 photo_too_large` / `415 unsupported_media_type`);
`capturedAt` UTC, `requestedAt` ile `expiresAt` arasında ve sunucu saatinden en fazla 30 sn ileride (`400 validation_failed`);
seferde dakikada 1 istek (`429`); push en fazla bir kez denenir (teslim edilemeyen istek zaman aşımına düşer).
Codex görevi §10'dadır; uygulama planı §8'dedir. Sözleşme Driver API (`/api/driver/v1`) üzerindedir.

Kullanıcı isteği (21.10.2026): planlamacı kokpitte seferin içinde *Yol görüntüsü al* düğmesine bastığında, o seferin
aracındaki tabletteki DynamicONE uygulaması kamerayı açıp o anki fotoğrafı çeksin ve planlamacı görsün.

Cihaz gerçeği (Webfleet PRO X, Android 13): tek yerleşik kamera vardır ve **arkadadır**; ön kamera yoktur. Dock'a bağlı
bir **harici** kamera girişi daha görünür (`android.lens.facing = EXTERNAL`, Webfleet video dock). Şoförün yüzü hiçbir
durumda çekilmez.

## 1. Android sınırı (tasarımı belirler)

Android 11 ve üstünde arka plandaki bir uygulama kameraya erişemez; push bildirimi uygulamayı uyandırır ama kamerayı
açamaz (istisna yalnızca cihaz sahibi / sistem uygulamalarıdır). Bu yüzden iki yol vardır:

| Tabletin durumu | Ne olur |
|---|---|
| DynamicONE öndedir (araçta olağan durum) | Push gelir gelmez fotoğraf **kendiliğinden** çekilir ve yüklenir; şoför yalnızca kısa bir "Planlamacı yol görüntüsü aldı" uyarısı görür, dokunması gerekmez. |
| Başka uygulama öndedir (ör. TomTom), ekran kilitli | Tam ekran bildirim: *Yol görüntüsü isteği*. Şoför **bir kez dokunur**, uygulama öne gelir ve fotoğraf o anda çekilir. Metin girişi yoktur. |
| Tablet çevrimdışı / kapalı | İstek bağlantı gelince düşer. `expiresAt` geçmişse çekilmez; planlamacı *zaman aşımı* görür. |

Planlamacıya "anında ve sessiz" söz verilemez; sözleşme bu yüzden bir **istek + sonuç** modelidir.

## 2. Akış

1. Planlamacı kokpitte sefer sayfasında *Yol görüntüsü al* der. Backend `SnapshotRequest` kaydı açar
   (`Pending`, `expiresAt = now + 10 dk`), seferin **o anki şoförünün araç tableti** cihazlarına push gönderir.
2. Uygulama push'u alır, isteği okur (`GET …/snapshot-requests/{requestId}`), süresi geçmemişse §1'e göre çeker.
3. Fotoğraf `POST …/snapshot-requests/{requestId}/photo` ile yüklenir (çevrimdışıysa kuyrukta bekler, `requestId`
   ile idempotent). Çekilemezse `POST …/snapshot-requests/{requestId}/result` ile neden bildirilir.
4. Kokpit isteğin durumunu gösterir: *Bekliyor* → *Çekildi* (fotoğraf, çekim zamanı, Webfleet konumu) / *Çekilemedi*
   (neden) / *Zaman aşımı*. Fotoğraf seferin evrakları arasında `RoadSnapshot` türüyle saklanır.

## 3. Cihaz hedefi: `POST /api/driver/v1/me/devices` eki (docs/13 §3.1)

Push yalnızca **araç tabletine** gitmelidir; şoförün cebindeki telefon yol fotoğrafı çekemez. Kayıt gövdesine isteğe
bağlı alanlar eklenir:

```json
{
  "installationId": "…",
  "platform": "Android",
  "pushToken": "…",
  "appVersion": "0.2.0",
  "locale": "tr",
  "deviceKind": "vehicleTablet",
  "deviceModel": "Bridgestone Mobility Solutions PRO_X",
  "hasCamera": true
}
```

| Alan | Kural |
| --- | --- |
| `deviceKind` | `vehicleTablet` veya `phone`. Uygulama en kısa kenarı ≥ 600 dp olan cihazı `vehicleTablet` sayar (docs: ASSUMPTIONS W1/W6). Alan gelmezse backend `phone` varsayar. |
| `deviceModel` | `Build.MANUFACTURER + " " + Build.MODEL`; yalnızca kokpitte göstermek için. |
| `hasCamera` | Kamera donanımı var mı. |

Backend isteği yalnızca `deviceKind = vehicleTablet` ve `hasCamera = true` kayıtlara gönderir. Böyle cihaz yoksa istek
hiç açılmaz: kokpit hemen *Araçta kayıtlı tablet yok* der.

Mevcut `me/devices` kayıtları alan olmadan `phone` kalır; migration yalnızca iki nullable sütun ekler.

## 4. Push: `trip.snapshot` (docs/13 biçimi)

```json
{
  "message": {
    "token": "<cihaz pushToken>",
    "android": { "priority": "HIGH", "ttl": "600s" },
    "data": {
      "type": "trip.snapshot",
      "tripId": "<tripId>",
      "requestId": "<requestId>",
      "expiresAt": "2026-10-21T13:10:00Z"
    }
  }
}
```

- Yalnızca `data`; sistem `notification` bloğu **yoktur** (uygulama öndeyse bildirim istemez). Bildirimi uygulama
  kendisi, yalnızca önde değilse gösterir.
- `ttl` = kalan geçerlilik; süresi geçmiş istek teslim edilmez.
- Aynı istek için push en fazla bir kez gönderilir; uygulama tekrar push'ları `requestId` ile yok sayar.

## 5. `GET /api/driver/v1/trips/{tripId}/snapshot-requests/{requestId}`

Push yalnızca tetikleyicidir (docs/13 §2); uygulama isteği okur.

```json
{
  "requestId": "9d3e…",
  "tripId": "trp-261245",
  "status": "Pending",
  "requestedAt": "2026-10-21T13:00:00Z",
  "expiresAt": "2026-10-21T13:10:00Z",
  "requestedByDisplayName": "Ayşe Yılmaz",
  "preferredCamera": "External"
}
```

| Alan | Kural |
| --- | --- |
| `status` | `Pending`, `Captured`, `Failed`, `Expired`, `Cancelled`. Uygulama yalnızca `Pending` iken çeker. |
| `requestedByDisplayName` | Şoföre gösterilen "… yol görüntüsü aldı" metni için; `null` olabilir. |
| `preferredCamera` | `External` (varsa dock kamerası, yoksa arka) veya `Back`. Varsayılan `External`. |

Hatalar: `404 snapshot_request_not_found` (istek yok veya bu şoförün seferi değil), `409 snapshot_request_closed`
(artık `Pending` değil), diğerleri Driver API ile aynı (`401`, `429 rate_limit_exceeded`).

## 6. `POST /api/driver/v1/trips/{tripId}/snapshot-requests/{requestId}/photo`

`multipart/form-data`, `vehicle-checks/{checkId}/photos` ile aynı biçim:

| Parça | Kural |
| --- | --- |
| `capturedAt` | ISO-8601 UTC, tabletin saatine göre çekim anı. |
| `camera` | `External` veya `Back` (gerçekten kullanılan). |
| `photo` | JPEG; uzun kenar ≤ 1600 px, ≤ 600 KB; EXIF yönü piksellere uygulanmış, diğer EXIF atılmış. |

- `200`: `{ "requestId", "status": "Captured", "documentId": "<RoadSnapshot evrak id>" }`.
- İdempotent: aynı `requestId` için ikinci yükleme `200` ve ilk sonucu döner (çevrimdışı kuyruk yeniden gönderebilir).
- `409 snapshot_request_closed`: istek artık `Pending` değil (örneğin süresi doldu, planlamacı iptal etti). Uygulama
  fotoğrafı siler, hata göstermez.
- `413 photo_too_large`, `415 unsupported_media_type`.

Backend fotoğrafı seferin evrakları arasına `documentType = RoadSnapshot` ile kaydeder; `capturedAt` anındaki Webfleet
konumunu (docs/19 kaynağı) isteğe iliştirir. Uygulama konum göndermez (docs/12 #6).

## 7. `POST /api/driver/v1/trips/{tripId}/snapshot-requests/{requestId}/result`

Fotoğraf çekilemediğinde:

```json
{ "status": "Failed", "reason": "camera_unavailable", "occurredAt": "2026-10-21T13:01:12Z" }
```

| `reason` | Anlamı |
| --- | --- |
| `camera_unavailable` | Kamera açılamadı (başka uygulama tutuyor, donanım hatası). |
| `permission_denied` | Kamera izni verilmemiş. |
| `expired` | Uygulama isteği `expiresAt` geçtikten sonra gördü (tablet geç bağlandı). |
| `driver_dismissed` | Şoför bildirimi kapattı (tam ekran bildirimde *Şimdi değil*). |

`204`. İdempotent. Zaten kapalı istekte `204` (bilgi amaçlıdır).

Backend `expiresAt` geçince `Pending` isteği kendisi `Expired` yapar; uygulamadan sonuç beklemez.

## 8. Uygulama planı (bilgi için)

Varsayılan kararlar (gerekirse ASSUMPTIONS.md'ye S1–S8 olarak girer):

- **S1 — Hedef cihaz:** yalnızca araç tableti (`deviceKind = vehicleTablet`); telefonlar push almaz.
- **S2 — Kamera seçimi:** harici kamera varsa o (dock kamerası yolu görür), yoksa arka kamera; ön kamera hiç kullanılmaz.
- **S3 — Önde/arkada:** uygulama önde ve ekran açıkken kendiliğinden çeker; değilse tam ekran bildirim, tek dokunuş.
  Şoföre her iki durumda da kimin istediği ve saat gösterilir (sefer ekranında ve ana sayfada bant).
- **S4 — Süre:** `expiresAt` geçmiş istek çekilmez, `expired` bildirilir. Bildirim de `expiresAt`'te kendiliğinden kalkar.
- **S5 — Çevrimdışı:** fotoğraf ve sonuç `pending_actions` kuyruğuna girer (`clientActionId = requestId`), WorkManager
  ile gönderilir; yüklenene kadar tabletin özel alanında kalır, sonra silinir. Çıkışta silinmez (CLAUDE.md §8).
- **S6 — İzin:** kamera izni yoksa otomatik yol `permission_denied` döner; bildirim yolunda izin o anda istenir.
- **S7 — Sürüş güvenliği:** otomatik çekimde ekran değişmez, yalnızca üstte 3 sn bant; bildirim yolunda tek büyük düğme
  (*Fotoğrafı çek*) ve küçük *Şimdi değil*. Metin girişi yoktur.
- **S8 — Gizlilik:** fotoğraf log'a yazılmaz; yalnızca sefer evrakı olarak saklanır; şoför çekildiğini görür.

Aşamalar (her biri lint + testler + assembleDebug ile biter):

1. **Cihaz kaydı ve push:** `me/devices` eki (`deviceKind`, `deviceModel`, `hasCamera`); `trip.snapshot` push ayrıştırma;
   `SnapshotRequest` modeli ve repository (Room'da istek + sonuç, `requestId` ile idempotent). Testler: push ayrıştırma,
   kayıt gövdesi, tekrar push'un yok sayılması.
2. **Çekim:** CameraX `ImageCapture` ile önizlemesiz çekim (mevcut `DocumentCamera` altyapısı, kamera seçimi S2), JPEG
   küçültme (`DocumentUploadPreparer` ile aynı yol). Önde/arkada kararı `ProcessLifecycleOwner` ile; arkadaysa
   tam ekran bildirim (mevcut `USE_FULL_SCREEN_INTENT`, uyandırma alarmı kanalı gibi ayrı kanal). Testler: önde →
   otomatik, arkada → bildirim, süresi geçmiş → `expired`, izin yok → `permission_denied`.
3. **Yükleme ve durum:** `photo` / `result` uçları, `pending_actions` kuyruğu, WorkManager yeniden deneme, `409`'da
   sessiz silme. Sefer ekranı ve ana sayfada bant: *Planlamacı (Ayşe Yılmaz) yol görüntüsü aldı · 13:01*, gönderilmediyse
   "gönderilmeyi bekliyor". Testler: idempotent yükleme, çevrimdışı kuyruk, uygulama yeniden başlatma, duplicate push.
4. **Cihazda doğrulama:** tabletle önde/arkada/kilitli/çevrimdışı senaryoları, dock kamerası takılıyken harici kamera
   seçimi. Demo backend bu ucu taşımaz; demo modunda özellik "sunucuya bağlı değil" durumundadır.

Backend/kokpit tarafı: sefer sayfasında düğme ve durum listesi, fotoğraf görüntüleme, istek başına denetim kaydı (kim,
ne zaman), seferde dakikada en fazla 1 istek, 10 dk zaman aşımı işi, `RoadSnapshot` evrak türü.

## 9. Kabul testleri (backend)

1. Araç tableti kayıtlı şoförün seferinde istek → `Pending`, yalnızca `vehicleTablet` cihazlara `trip.snapshot` push,
   `ttl` kalan süre.
2. Yalnızca `phone` cihazı olan şoför → istek açılmaz, kokpit *Araçta kayıtlı tablet yok*.
3. `GET snapshot-requests/{id}` başka şoförün seferinde `404 snapshot_request_not_found`.
4. `photo` yüklemesi → `Captured`, `RoadSnapshot` evrakı sefer evraklarında, `capturedAt` anındaki Webfleet konumu ekli;
   aynı `requestId` ile ikinci yükleme aynı `documentId` ile `200`.
5. Süresi geçmiş isteğe `photo` → `409 snapshot_request_closed`; backend `expiresAt` geçince `Expired` yapar.
6. `result` ile `Failed` + neden; kokpitte neden görünür; kapalı isteğe `result` `204`.
7. Dakikada 1'den fazla istek → `429` (kokpit düğmesi devre dışı).
8. Push içeriğinde fotoğraf, kişisel veri veya token yoktur (docs/13 §2).

## 10. Codex görevi (yapıştırılabilir)

> DynamicONE backend'ine planlamacının seferdeki araç tabletinden anlık yol fotoğrafı istemesini ekle. Sözleşme:
> Android deposundaki `docs/24_REMOTE_ROAD_SNAPSHOT.md` (§2–§7, §9). Driver API `me/devices` kaydına nullable
> `deviceKind` (`vehicleTablet|phone`), `deviceModel`, `hasCamera` alanları; `SnapshotRequest` varlığı
> (`Pending|Captured|Failed|Expired|Cancelled`, `expiresAt = +10 dk`); kokpit sefer sayfasında *Yol görüntüsü al*
> düğmesi ve durum listesi; yalnızca `vehicleTablet` + `hasCamera` cihazlara yalnızca-`data` FCM push
> (`type = trip.snapshot`, `tripId`, `requestId`, `expiresAt`, `ttl` = kalan süre; `notification` bloğu yok);
> `GET trips/{tripId}/snapshot-requests/{requestId}`, multipart `POST …/photo` (`capturedAt`, `camera`, `photo`;
> `requestId` ile idempotent; fotoğraf `RoadSnapshot` türünde sefer evrakı, `capturedAt` anındaki Webfleet konumu
> iliştirilir) ve `POST …/result` (`Failed` + `reason`). Süresi dolan istekleri `Expired` yapan arka plan işi,
> seferde dakikada en fazla 1 istek, denetim kaydı. Problem `code` değerleri: `snapshot_request_not_found`,
> `snapshot_request_closed`, `photo_too_large`. Mevcut yetki, hız sınırı ve problem+json davranışını koru; fotoğrafı ve
> push içeriğini log'a yazma. §9'daki testleri ekle, OpenAPI'de görünsün. Alan ve kod adlarını değiştirme; bir
> farklılık gerekiyorsa açıkça yaz.

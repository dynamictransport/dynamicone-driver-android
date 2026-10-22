# 25 — Şoförü sesli uyandırma (backend sözleşme önerisi)

Durum: **öneri (22.10.2026), backend'de yoktur.** Codex görevi §10'dadır; uygulama planı §8'dedir. Sözleşme Driver API
(`/api/driver/v1`) üzerindedir; `me/devices` kaydının docs/24 §3 eki (`deviceKind`) kullanılır. Farklılık olursa backend
dokümanı geçerlidir. **Uygulama tarafı (aşama 1–3) 22.10.2026'da combined çalışma ağacında yazıldı** (ASSUMPTIONS U1–U9);
yalnızca Robolectric/MockWebServer ile doğrulandı, cihaz doğrulaması (aşama 4) backend yayına girince yapılacaktır.

Kullanıcı isteği (22.10.2026): planlamacı, uyuyan veya telefona bakmayan şoförü kokpitten bir düğmeyle **tablet ve
telefon üzerinden sesli** uyarabilsin; şoförün uyandığını görsün.

Yeni altyapı gerekmez. Üç mevcut parça birleşir:

| Parça | Nereden | Burada ne yapar |
|---|---|---|
| Push ve cihaz kaydı | docs/13, docs/24 §3 | Şoförün kayıtlı tablet ve telefonlarına yüksek öncelikli `data` push gider. |
| Çalan alarm | docs/16 (dinlenme alarmı) | Kilit ekranının üstünde tam ekran, alarm sesiyle, cevap gelene kadar çalar. |
| İstek + sonuç modeli | docs/24 | Planlamacı istek açar, cihaz durum bildirir, kokpit sonucu gösterir. |

## 1. Android sınırı (tasarımı belirler)

- Push **en iyi çabadır**; teslim garantisi yoktur. Yüksek öncelikli `data` push Doze'daki telefonu kısa süre uyandırır,
  ancak işletim sistemi uygulamayı zorla durdurduysa (Samsung *Auto Blocker*, pil optimizasyonu) push gecikebilir veya
  düşebilir. Araç tabletinde uygulama olağan durumda öndedir; sorun telefondadır.
- Push'ta **`notification` bloğu olamaz**: uygulama arkadayken sistem bildirimi kendisi gösterir ve uygulamaya haber
  vermez; tam ekran ve sürekli çalma o zaman mümkün olmaz. Yalnızca `data` gönderilir, bildirimi uygulama kurar.
- **Sessiz mod:** alarm kanalı (`USAGE_ALARM`, `CATEGORY_ALARM`) sessiz modda da çalar. **Rahatsız Etmeyin:** Android'in
  varsayılan ayarı alarmlara izin verir; şoför *Tam sessiz* seçtiyse hiçbir şey çalmaz. Uygulama bunu okuyabilir
  (`currentInterruptionFilter`) ve ayar ekranında uyarır; DND erişim izni istemez (ilk sürüm).
- **Alarm sesi kısıksa** çağrı duyulmaz. Uygulama çağrı süresince alarm ses seviyesini yükseltir, bitince eski değere
  döner (§8 U4). *Tam sessiz* modunda sistem bunu reddedebilir; uygulama hatayı yutar.
- **Tam ekran bildirim** (`USE_FULL_SCREEN_INTENT`) ve kesin alarm izni docs/16 ile zaten ele alındı; Android 14+'ta
  `canUseFullScreenIntent()` kontrolü aynıdır.
- Cihaz kapalı, kapsama dışı veya uygulamadan çıkış yapılmışsa çağrı **ulaşmaz**. Kokpit bu yüzden *Yanıt yok* sonrasında
  telefonla aramayı önerir: uygulama telefon aramasının yerine değil, önüne geçer.

Bu yüzden sözleşme, docs/24 gibi, **istek + sonuç** modelidir; planlamacıya "kesin uyanır" sözü verilmez.

## 2. Akış

1. Planlamacı kokpitte şoför kartında veya sefer sayfasında *Şoförü uyandır* der, hazır bir sebep seçer (§5 `reason`).
   Backend `WakeUpCall` kaydı açar (`Pending`, `expiresAt = now + 5 dk`) ve şoförün **tüm** kayıtlı cihazlarına
   `driver.wakeup` push'u gönderir. Aynı şoför için açık (`Pending`/`Ringing`) çağrı varken yenisi açılmaz (`409`).
2. Uygulama push'u alır almaz çalmaya başlar (push içindeki `callId`, `expiresAt`, `reason` yeterlidir) ve aynı anda
   `GET …/wake-up-calls/{callId}` ile kaydı okur: kayıt `Pending`/`Ringing` değilse susar. Çalmaya başladığını
   `POST …/ringing` ile bildirir; kokpit *Çalıyor* görür.
3. Ekranda tek baskın düğme: **UYANDIM**. Dokunulunca çalma durur, `POST …/result` (`Acknowledged`) `clientActionId`
   ile gönderilir; çevrimdışıysa kuyrukta bekler.
4. Backend ilk `Acknowledged` ile çağrıyı kapatır ve şoförün diğer cihazlarına `driver.wakeup` + `event = stop` gönderir;
   öteki cihaz susar.
5. Cevap gelmeden `expiresAt` geçerse cihaz kendisi susar, backend çağrıyı `Expired` yapar; kokpit *Yanıt yok* ve
   *Telefonla ara* önerisi gösterir. Planlamacı isterse yeni çağrı açar.
6. Kokpit durumu: *Bekliyor* → *Çalıyor (tablet / telefon)* → *Uyandı 05:12 (telefon)* / *Yanıt yok* / *İptal* /
   *Gönderilemedi*. Her çağrı için denetim kaydı: kim, ne zaman, sebep, sonuç.

## 3. Cihaz hedefi

Çağrı `deviceKind` ayrımı yapmadan şoförün **tüm** `me/devices` kayıtlarına gider (docs/24 §3 alanı yalnızca kokpitte
"hangi cihaz cevapladı" için kullanılır). Kayıtlı cihaz yoksa çağrı açılmaz: `409 wake_up_call_no_device`, kokpit
*Şoförün kayıtlı cihazı yok* der.

Çağrı yalnızca `driver` modülü olan hesaplara açılabilir (ofis personeline uyandırma yoktur).

## 4. Push: `driver.wakeup` (docs/13 biçimi)

```json
{
  "message": {
    "token": "<cihaz pushToken>",
    "android": { "priority": "HIGH", "ttl": "300s" },
    "data": {
      "type": "driver.wakeup",
      "event": "ring",
      "callId": "<callId>",
      "reason": "call_office",
      "expiresAt": "2026-10-22T05:10:00Z"
    }
  }
}
```

| Alan | Kural |
| --- | --- |
| `event` | `ring` (çal) veya `stop` (sus: başka cihaz cevapladı, planlamacı iptal etti). `stop` push'unda `reason` ve `expiresAt` yoktur. |
| `reason` | §5'teki sebep kodu; uygulama kendi dilinde metne çevirir. Bilinmeyen kodda genel metin gösterilir. |
| `expiresAt` | Çalma penceresinin sonu; uygulama bu anda kendiliğinden susar. |

- Yalnızca `data`; `notification` bloğu **yoktur** (§1).
- `ttl` = kalan geçerlilik; süresi geçmiş çağrı teslim edilmez.
- Kişisel veri, isteyen kişinin adı veya serbest metin **taşınmaz** (docs/13 §2); ad `GET` ile gelir.
- Aynı `callId` için `ring` push'u en fazla bir kez gönderilir; uygulama tekrarı `callId` ile yok sayar.

## 5. `GET /api/driver/v1/me/wake-up-calls/{callId}`

```json
{
  "callId": "7c1a…",
  "status": "Ringing",
  "reason": "call_office",
  "requestedAt": "2026-10-22T05:05:00Z",
  "expiresAt": "2026-10-22T05:10:00Z",
  "requestedByDisplayName": "Ayşe Yılmaz",
  "tripId": null,
  "tripNo": null,
  "acknowledgedAt": null,
  "acknowledgedDeviceKind": null
}
```

| Alan | Kural |
| --- | --- |
| `status` | `Pending` (push gitti), `Ringing` (en az bir cihaz çaldığını bildirdi), `Acknowledged`, `Expired`, `Cancelled`, `Failed` (hiçbir cihaza push gönderilemedi). Uygulama yalnızca `Pending`/`Ringing` iken çalar. |
| `reason` | `call_office` (ofisi ara), `departure_time` (çıkış saati geldi), `loading_time_changed` (yükleme saati değişti), `trip_changed` (sefer değişti), `other`. Liste backend'de genişletilebilir; uygulama bilmediği kodu `other` gibi gösterir. |
| `requestedByDisplayName` | Şoföre gösterilen "… seni arıyor" metni için; `null` olabilir. |
| `tripId`, `tripNo` | Sefer sayfasından açıldıysa dolu; şoför ekranında sefer numarası gösterilir. |
| `acknowledgedDeviceKind` | `vehicleTablet` veya `phone`; kokpit için. |

Hatalar: `404 wake_up_call_not_found` (çağrı yok veya bu şoförün değil), diğerleri Driver API ile aynı (`401`,
`429 rate_limit_exceeded`).

## 6. `POST /api/driver/v1/me/wake-up-calls/{callId}/ringing`

Cihaz çalmaya başladığında, en iyi çabayla (kuyruğa girmez, bir kez denenir):

```json
{ "occurredAt": "2026-10-22T05:05:03Z", "installationId": "3f0c9f5e-…" }
```

`204`. Çağrı `Pending` ise `Ringing` olur; başka durumda yine `204`, değişiklik yoktur. Kokpit "çalıyor: tablet" veya
"çalıyor: telefon" gösterebilir (`installationId` → `deviceKind`).

## 7. `POST /api/driver/v1/me/wake-up-calls/{callId}/result`

```json
{
  "status": "Acknowledged",
  "occurredAt": "2026-10-22T05:06:40Z",
  "installationId": "3f0c9f5e-…",
  "clientActionId": "7c1a…-ack"
}
```

| `status` | Anlamı |
| --- | --- |
| `Acknowledged` | Şoför *UYANDIM*'a dokundu. Çağrıyı kapatır; diğer cihazlara `stop` push'u gider. |
| `Expired` | Cihaz `expiresAt`'e kadar çaldı, cevap yok; veya çağrıyı süresi geçtikten sonra gördü (geç bağlandı). Bilgi amaçlıdır. |

- `204`. `clientActionId` ile idempotent: çevrimdışı kuyruk aynı sonucu tekrar gönderebilir.
- Zaten kapalı çağrıda `204`; ilk `Acknowledged` kazanır, ikinci cihazın `Acknowledged`'ı kaydı değiştirmez.
- `400 validation_failed`: `occurredAt` `requestedAt`'ten önce veya sunucu saatinden 30 sn'den fazla ileride (docs/24 ile
  aynı kural).

Backend `expiresAt` geçince `Pending`/`Ringing` çağrıyı kendisi `Expired` yapar; uygulamadan sonuç beklemez.

## 8. Uygulama planı (bilgi için)

Varsayılan kararlar (gerekirse ASSUMPTIONS.md'ye U1–U9 olarak girer):

- **U1 — Hedef cihaz:** şoförün her cihazı çalar; ilki cevaplayınca diğeri susar (`stop` push'u gelmezse `GET` ile
  kapandığını görünce de susar, en geç `expiresAt`'te).
- **U2 — Çalma biçimi:** dinlenme alarmıyla aynı yapı, ayrı kanal `wake_up_call` (alarm sesi, titreşim, `IMPORTANCE_HIGH`,
  `CATEGORY_ALARM`, `FLAG_INSISTENT`, tam ekran, `setTimeoutAfter = expiresAt`). Tek baskın düğme **UYANDIM** (en az
  72 dp); kaydırarak kapatılamaz (`ongoing`). İkinci bir düğme yoktur; *ertele* yoktur (amaç uyandırmak).
- **U3 — Ekran metni:** "Ayşe Yılmaz seni arıyor" + sebep metni + saat; sefer varsa sefer numarası. Serbest metin yoktur;
  metin girişi yoktur. Kilit ekranında da okunabilir (`VISIBILITY_PUBLIC`; kişisel veri yalnızca isteyenin adı).
- **U4 — Ses seviyesi:** çalma başlarken alarm akışı en az %70'e çıkarılır, susunca önceki değere döner; sistem
  reddederse (Tam sessiz) sessizce geçilir. Şoför uygulamada çağrı sesini kapatamaz (ürün sahibi kararı gerekir).
- **U5 — İzinler:** docs/16 alarm izin ekranı paylaşılır (bildirim, kesin alarm, tam ekran); telefonda ayrıca pil
  optimizasyonu muafiyeti (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) bir kez istenir, Samsung'da *Auto Blocker*
  için yönlendirme metni gösterilir. DND *Tam sessiz* ise ayar ekranında uyarı.
- **U6 — Çevrimdışı:** `Acknowledged` sonucu `pending_actions` kuyruğuna girer (`clientActionId = callId + "-ack"`),
  WorkManager ile gönderilir; `ringing` kuyruğa girmez. Çıkışta kuyruk silinmez (CLAUDE.md §8).
- **U7 — Ana sayfa bandı:** "Ofis seni aradı · 05:05 · Uyandım (gönderilmeyi bekliyor)" / "… · iletildi". Senkron durumu
  docs/04 ile aynı üç durumdur; başarısız gönderim başarılı gösterilmez.
- **U8 — Sürüş güvenliği:** araç hareket hâlindeyken de çalar (uyandırma araç dururken anlamlıdır, hareket bilgisi
  uygulamada yoktur); düğme büyük, tek dokunuş, onay sorusu yok.
- **U9 — Kapsam:** ayrı build ayarı yoktur; push yapılandırılmış ve demo modu kapalı her `driver` oturumunda çalışır.
  Demo modunda yoktur. İlk sürümde sesli okuma (TTS) yoktur; sonraki sürümde "Ayşe Yılmaz seni arıyor" cümlesi cihaz
  dilinde okunabilir (Android `TextToSpeech`, çevrimdışı).

Aşamalar (her biri lint + testler + assembleDebug ile biter):

1. **Push ve model:** `driver.wakeup` ayrıştırma (`event`, `callId`, `reason`, `expiresAt`) `DriverMessagingService`'te;
   `WakeUpCall` domain modeli; Room `wake_up_calls` tablosu (`callId` birincil anahtar, durum, zamanlar, `reason`,
   isteyen adı, `acknowledgedAt`); repository `GET` + `ringing`. Testler: push ayrıştırma (`ring`/`stop`/bilinmeyen
   `reason`), tekrar push'un yok sayılması, süresi geçmiş çağrının çalmaması.
2. **Çalma:** `WakeUpCallNotifier` (dinlenme alarmı bildiricisinin kardeşi; ortak parçalar `core/alarm`'a çıkarılır,
   büyük refactor yapılmaz) + `WakeUpCallActivity` (tek düğme, `showWhenLocked`/`turnScreenOn`); `stop` ve `expiresAt`'te
   susma; ses seviyesi U4; izin ekranı U5. Testler (Robolectric): `ring` → bildirim + tam ekran, `stop` → iptal,
   `expiresAt` → iptal, `UYANDIM` → susar ve sonuç kuyruğa girer, çift dokunma tek sonuç.
3. **Sonuç ve durum:** `result` ucu, `pending_actions` kuyruğu, WorkManager yeniden deneme, kapalı çağrıda sessiz geçiş;
   ana sayfa bandı U7. Testler: idempotent gönderim, çevrimdışı kuyruk, uygulama yeniden başlatma, token yenileme
   sırasında gelen çağrı, saat dilimi.
4. **Cihazda doğrulama:** tablet (Webfleet PRO X, TomTom öndeyken, ekran kapalı) ve telefonlar (Galaxy S25 *Auto Blocker*,
   T Phone Pro): uygulama arkada, kilitli, zorla durdurulmuş, sessiz mod, DND, alarm sesi 0, çevrimdışı; iki cihazda
   aynı anda çalma ve birinin cevabıyla diğerinin susması. Demo backend bu ucu taşımaz.

Backend/kokpit tarafı: şoför kartı ve sefer sayfasında *Şoförü uyandır* düğmesi (sebep seçimi), durum ve geçmiş listesi,
*Yanıt yok* sonrasında *Telefonla ara* önerisi, şoför başına aynı anda tek açık çağrı, dakikada en fazla 1 çağrı,
5 dk zaman aşımı işi, FCM gönderim hatasında `Failed`, denetim kaydı.

## 9. Kabul testleri (backend)

1. Kayıtlı cihazı olan şoföre çağrı → `Pending`, her cihaza `driver.wakeup` + `event = ring` push, `ttl` kalan süre,
   `notification` bloğu yok.
2. Kayıtlı cihazı olmayan şoför → `409 wake_up_call_no_device`; `driver` modülü olmayan hesap → `409` (uygun kod).
3. Açık çağrı varken ikinci çağrı → `409 wake_up_call_active`; dakikada 1'den fazla → `429`.
4. `GET wake-up-calls/{id}` başka şoförün çağrısında `404 wake_up_call_not_found`.
5. `ringing` → `Ringing` ve cihaz türü; kapalı çağrıda `204`, değişiklik yok.
6. `result` `Acknowledged` → `Acknowledged`, `acknowledgedAt`, `acknowledgedDeviceKind`; diğer cihazlara `event = stop`
   push'u; aynı `clientActionId` ile ikinci gönderim `204`, kayıt değişmez; ikinci cihazın `Acknowledged`'ı kaydı
   değiştirmez.
7. `expiresAt` geçince backend `Expired` yapar; geç gelen `result` `204`, kayıt değişmez.
8. Planlamacı iptali → `Cancelled` + tüm cihazlara `stop` push'u.
9. Hiçbir cihaza push gönderilemezse (`UNREGISTERED`) → `Failed`, kokpitte *Gönderilemedi*; geçersiz token kaydı silinir
   (docs/13 §3.2).
10. Push ve kayıt içeriğinde mesaj metni, kişisel veri veya token yoktur (docs/13 §2); denetim kaydında kim, ne zaman,
    sebep, sonuç vardır.

## 10. Codex görevi (yapıştırılabilir)

> DynamicONE backend'ine planlamacının şoförü tablet ve telefonundan sesli uyandırmasını ekle. Sözleşme: Android
> deposundaki `docs/25_DRIVER_WAKE_UP_CALL.md` (§2–§7, §9). `WakeUpCall` varlığı
> (`Pending|Ringing|Acknowledged|Expired|Cancelled|Failed`, `expiresAt = +5 dk`, `reason`:
> `call_office|departure_time|loading_time_changed|trip_changed|other`, isteğe bağlı `tripId`); kokpitte şoför kartı ve
> sefer sayfasında *Şoförü uyandır* düğmesi (sebep seçimi), durum/geçmiş listesi ve *Yanıt yok* sonrasında *Telefonla
> ara* önerisi; şoförün tüm `me/devices` kayıtlarına yalnızca-`data` FCM push (`type = driver.wakeup`,
> `event = ring|stop`, `callId`, `reason`, `expiresAt`, `ttl` = kalan süre; `notification` bloğu yok; kişisel veri yok);
> Driver API `GET me/wake-up-calls/{callId}`, `POST …/ringing` (`occurredAt`, `installationId`) ve `POST …/result`
> (`Acknowledged|Expired`, `occurredAt`, `installationId`, `clientActionId` ile idempotent; ilk `Acknowledged` çağrıyı
> kapatır ve diğer cihazlara `stop` push'u gönderir). Şoför başına aynı anda tek açık çağrı, dakikada en fazla 1 çağrı,
> süresi dolan çağrıları `Expired` yapan arka plan işi, FCM gönderim hatasında `Failed`, denetim kaydı. Problem `code`
> değerleri: `wake_up_call_not_found`, `wake_up_call_active`, `wake_up_call_no_device`. Mevcut yetki, hız sınırı ve
> problem+json davranışını koru; push içeriğini log'a yazma. §9'daki testleri ekle, OpenAPI'de görünsün. Alan ve kod
> adlarını değiştirme; bir farklılık gerekiyorsa açıkça yaz.

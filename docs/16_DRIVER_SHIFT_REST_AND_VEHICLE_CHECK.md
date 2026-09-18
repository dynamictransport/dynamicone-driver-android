# 16 — Şoför mesaisi: takograf dinlenmesi, uyandırma, araç NFC etiketi ve günlük kontrol (backend sözleşme önerisi)

Durum: **öneri, 18.09.2026.** Backend'de henüz yoktur; Codex bu dokümana göre hazırlar (§14). Uygulama tarafı bu
sözleşmeye göre yazıldı ve yalnızca MockWebServer ile test edildi; `app.driverShift=false` (varsayılan) iken hiçbir
ekran bu uçları çağırmaz (§12). Bir alan değişirse iki taraf birlikte değişir; tek kaynak yayındaki OpenAPI
(`driver-v1`) dokümanıdır.

## 1. Amaç ve akış

Tır şoförünün günlük sürüş ve çalışma hakkı takograf kayıtlarına bağlıdır. Hak dolunca şoför uzun (günlük) dinlenme
yapar; dinlenme bitince mesaiye başlar. DynamicONE App:

1. Dinlenme sırasında ana ekranda dinlenmenin ne zaman biteceğini gösterir.
2. Dinlenme bitince telefonda kurulu alarmla şoförü uyarır ("Dinlenmen bitti, mesaiye başla").
3. **MESAİ BAŞLAT** → şoför araca gider, araca yapıştırılmış **NFC etiketini** okutur.
4. **Günlük araç kontrolü**: her maddede *Tamam* / *Sorun var*; sorunda fotoğraf.
5. Kontrol gönderilir, mesai başlar, şoför işine (sefere) devam eder.

Kullanıcı kararları (18.09.2026):

| Konu | Karar |
| --- | --- |
| Takografa göre dinlenme bitmeden *Mesai başlat* | **Uyar, izin ver.** Kalan süre gösterilir; şoför basılı tutarak yine başlatabilir. Kayıt ofiste "dinlenme bitmeden başladı" diye işaretli görünür. |
| Alarm hangi dinlenmede | **Her günlük dinlenme sonunda.** Şoför o dinlenme için tek dokunuşla kapatabilir (ör. haftalık izin). |
| Uyarı biçimi | **Alarm sesi** (kilit ekranında tam ekran), şoför ayarlardan "sadece bildirim"e çevirebilir. |
| Kontrolde sorun | **Mesai başlar, ofise bildirilir.** Aracın yola çıkıp çıkmayacağına planlamacı karar verir; uygulama engellemez. |

## 2. Genel kurallar

- Uçlar şoför modülünün parçasıdır: **Driver API** `/api/driver/v1/` altında, bugünkü şoför kuralları (`driver_access_required`,
  `Authorization: Bearer`, problem+json, `Retry-After`, `404 api_disabled`). Mobile API token'ı da geçerlidir (docs/15 §3).
- Şoför kimliği token'dan çözülür; istemci şoför/personel kimliği göndermez.
- Zamanlar ISO-8601 UTC (`Z`). Süreler **dakika** (tam sayı). Enum değerleri metin.
- Yazma istekleri çevrimdışı yapılabilir: telefon kaydı önce saklar, bağlantı gelince **aynı `commandId`** ile gönderir.
  Aynı kullanıcı + `commandId` tekrar gelirse ilk sonuç **200** ile aynen döner; aynı `commandId` altında farklı içerik
  `409 command_conflict` (docs/15 §5.3 ile aynı kural). `elapsed…Ms` alanları karşılaştırmaya dahil değildir.
- Resmi zaman telefon saatine bırakılmaz (docs/15 §5.4 ile aynı): `elapsed…Ms` varsa `olayZamanı = alınmaZamanı − elapsed`
  (`ServerElapsed`), yoksa telefonun gönderdiği zaman (`DeviceClock`, işaretli). Gelecekte > 2 dk → `…_time_invalid`,
  7 günden eski → `…_too_old`.
- Uygulamanın hata davranışı docs/15 §5.3 ile aynıdır: yalnızca 400 ve 409 kalıcı hatadır; 401 refresh; 403/404/429/5xx
  ve bağlantı hatası "gönderilmedi" → aynı `commandId` ile otomatik tekrar.

## 3. `GET driver-times` — takograf özeti

Şoförün en son Webfleet sürüş/dinlenme özeti (`WebfleetDriverTimeSnapshots`, `DriverProfile.WebfleetDriverId`
bağlantısı; docs/06 Webfleet ve docs/58). Yeni sağlayıcı isteği **yapılmaz**; kayıtlı son snapshot okunur.

```json
{
  "status": "Available",
  "referenceTimeUtc": "2026-09-18T21:05:00Z",
  "receivedAtUtc": "2026-09-18T21:06:12Z",
  "currentActivity": "Break",
  "dailyRest": {
    "state": "Resting",
    "startedAtUtc": "2026-09-18T20:40:00Z",
    "endsAtUtc": "2026-09-19T05:40:00Z",
    "minimumDurationMinutes": 540
  },
  "remainingDrivingTodayMinutes": 0,
  "remainingWorkingTodayMinutes": null,
  "nextBreakDueUtc": null,
  "nextBreakMinimumMinutes": null,
  "latestEndCurrentShiftUtc": null,
  "hasInfringement": false
}
```

| Alan | Kural |
| --- | --- |
| `status` | `Available` · `NotLinked` (şoför bir Webfleet sürücüsüne bağlı değil) · `NoData` (bağlı ama kayıt yok ya da hesabın Remaining Driving Times lisansı yok). `Available` değilse diğer alanlar `null`/yok. |
| `referenceTimeUtc` | Webfleet `reference_time`: değerlerin geçerli olduğu an. Uygulama 15 dakikadan eskiyse "son bilinen" yazar (Planning Intelligence ile aynı eşik). |
| `receivedAtUtc` | Snapshot'ın DynamicONE'a geldiği an. |
| `currentActivity` | `Driving` · `OtherWork` · `Standby` · `Break` · `Unknown` (backend `DriverActivity`). |
| `dailyRest.state` | `Resting`: son vardiya bitti, şoför günlük dinlenmede ve **yeni vardiya henüz başlamadı** (`endsAtUtc` geçmişte olabilir: asgari dinlenme tamamlandı, şoför henüz başlamadı). `Working`: vardiya sürüyor (sürüş, diğer iş, kısa mola, hazır bulunma). `Unknown`: karar verilemiyor. Hangi Webfleet alanlarından türetileceği backend kararıdır (ör. `current_workstate`, `current_shift_start`, `earliest_start_next_shift`); 5 dakikalık senkron gecikmesi kabul edilir. |
| `dailyRest.endsAtUtc` | `Resting` iken zorunlu: **bir sonraki vardiyanın en erken yasal başlangıcı** (Webfleet `earliest_start_next_shift`). Uyandırma alarmı bu saate kurulur. |
| `dailyRest.startedAtUtc` | Dinlenmenin başladığı an (son vardiyanın bittiği an); bilinmiyorsa `null`. Uygulama aynı dinlenmeyi bu alanla tanır. |
| `dailyRest.minimumDurationMinutes` | Bu dinlenme için istenen asgari süre (ör. 540 = 9 sa, 660 = 11 sa); bilinmiyorsa `null`. |
| `remainingDrivingTodayMinutes`, `remainingWorkingTodayMinutes` | Kalan günlük sürüş / çalışma. Negatif değer süre aşımıdır, 0'a çevrilmez. Bilinmiyorsa `null` (0 yazılmaz). |
| `nextBreakDueUtc`, `nextBreakMinimumMinutes` | Sıradaki molanın en geç başlangıcı (`reference_time + next_break`) ve süresi. |
| `latestEndCurrentShiftUtc` | Vardiyanın en geç bitişi (Webfleet `latest_end_current_shift`). |
| `hasInfringement` | Webfleet ihlal işareti. |

Haftalık dinlenme Webfleet'ten gelmez (docs/06, TH-2026-0429); uydurulmaz. Değerler Webfleet'in de belirttiği gibi
**gösterge amaçlıdır**; yasal kontrol operatörün sorumluluğundadır. Uygulama bu uyarıyı ekranda gösterir.

Hatalar: `403 driver_access_required`.

## 4. `GET shift` — şoförün mesaisi

```json
{
  "active": {
    "id": "8d1f…",
    "commandId": "0b7c…",
    "startedAtUtc": "2026-09-19T05:52:10Z",
    "endedAtUtc": null,
    "endedBy": null,
    "vehicle": { "vehicleId": "41aa…", "registrationNumber": "WX 12345", "identifiedBy": "Nfc" },
    "trailerRegistrationNumber": "WGM 1234A",
    "startedBeforeRestEnd": false,
    "vehicleCheck": { "id": "c9e2…", "result": "DefectsReported", "defectCount": 1, "photosExpected": 1, "photosReceived": 1 }
  },
  "lastEnded": null,
  "plannedVehicle": { "vehicleId": "41aa…", "registrationNumber": "WX 12345", "trailerRegistrationNumber": "WGM 1234A" }
}
```

| Alan | Kural |
| --- | --- |
| `active` | Açık mesai veya `null`. En fazla bir açık mesai vardır. |
| `lastEnded` | `active` yokken son biten mesai (ekranda "Mesai 17:30'da bitti"); en fazla 24 saat geriye bakılır, yoksa `null`. |
| `endedBy` | `Driver` (uygulamadan *Mesaiyi bitir*) · `Tachograph` (§9.1 otomatik kapanış) · `Office` (web'den düzeltme). |
| `vehicle.identifiedBy` | `Nfc` (etiket okutuldu) · `Manual` (etiket okunamadı, planlanan araç seçildi; §7). |
| `startedBeforeRestEnd` | Başlangıç anında takografa göre dinlenme bitmemişti (§7). |
| `vehicleCheck.result` | `Ok` · `DefectsReported`. |
| `plannedVehicle` | Şoförün devam eden, yoksa en yakın onaylı atamasındaki çekici ve dorse; yoksa `null`. Uygulama farklı araç okutulunca uyarır ve etiket okunamazsa bu aracı önerir. |

Nesne şeması (`ShiftDto`) `active`, `lastEnded` ve yazma yanıtlarında aynıdır.

## 5. `GET vehicle-tags/{tagUid}` — okutulan etiketin aracı

Etiket okununca şoföre plakayı hemen göstermek için (çevrimiçiyse). UID biçimi docs/15 §5.1 ile aynı
(`^([0-9A-F]{2}){4,10}$`, büyük harf, ayraçsız).

```json
{ "vehicleId": "41aa…", "registrationNumber": "WX 12345", "displayName": "DAF XF 480", "isPlannedVehicle": true }
```

Hatalar: `404 vehicle_tag_unknown` (kayıtlı etkin etiket yok), `403 driver_access_required`. Etiket listesi uygulamaya
verilmez; doğrulama yalnızca sunucudadır.

## 6. `GET vehicle-check/template` — günlük kontrol listesi

```json
{
  "templateId": "b71e…",
  "version": 1,
  "items": [
    {
      "key": "no_new_damage",
      "titles": { "en": "No new damage on the truck or trailer", "pl": "Brak nowych uszkodzeń ciągnika i naczepy", "tr": "Çekici ve dorsede yeni hasar yok" },
      "hints": { "en": "Body, cab, glass, bumpers", "pl": "Nadwozie, kabina, szyby, zderzaki", "tr": "Kasa, kabin, camlar, tamponlar" },
      "photoRequiredOnDefect": true,
      "notApplicableAllowed": false
    }
  ]
}
```

| Alan | Kural |
| --- | --- |
| `items` | 1–30 madde, gösterim sırasıyla. |
| `key` | `^[a-z0-9_]{1,40}$`, şablonda benzersiz. Sürümler arasında aynı anlamdaki madde aynı anahtarı korur. |
| `titles`, `hints` | Dil kodu → metin. `en` zorunlu; uygulama cihaz dilini, yoksa `en`'i gösterir. `hints` isteğe bağlı. |
| `photoRequiredOnDefect` | *Sorun var* seçilince en az bir fotoğraf zorunlu. |
| `notApplicableAllowed` | Üçüncü seçenek *Geçerli değil* (ör. konteyner yüklü değilken konteyner kilidi). |

Uygulama son alınan şablonu telefonda saklar. Hiç şablon alınamamışsa (ilk kullanım, internet yok) **§6.1 varsayılan
şablonu** (`templateId = null`, `version = 1`) kullanır; backend bu anahtarları her zaman kabul etmelidir.

### 6.1 Varsayılan şablon (sürüm 1, backend'in ilk kaydı)

Kullanıcıya önerilen liste (18.09.2026); ofis web'den değiştirebilir. Tüm maddelerde `photoRequiredOnDefect = true`.

| `key` | EN | PL | TR | Geçerli değil |
| --- | --- | --- | --- | --- |
| `no_new_damage` | No new damage on the truck or trailer | Brak nowych uszkodzeń ciągnika i naczepy | Çekici ve dorsede yeni hasar yok | — |
| `tyres` | Tyres: pressure normal, no cuts or damage | Opony: ciśnienie w normie, bez przecięć i uszkodzeń | Lastikler: basınç normal, kesik veya hasar yok | — |
| `lights` | Lights and indicators work | Światła i kierunkowskazy działają | Işıklar ve sinyaller çalışıyor | — |
| `mirrors_windows` | Mirrors and windows clean and intact | Lusterka i szyby czyste i całe | Aynalar ve camlar temiz ve sağlam | — |
| `no_leaks` | No leaks (oil, fuel, air, AdBlue) | Brak wycieków (olej, paliwo, powietrze, AdBlue) | Sızıntı yok (yağ, yakıt, hava, AdBlue) | — |
| `brakes_air` | Brakes and air pressure normal | Hamulce i ciśnienie powietrza w normie | Frenler ve hava basıncı normal | — |
| `coupling` | Trailer coupling, lines and connectors secure | Sprzęg, przewody i złącza naczepy sprawne | Dorse bağlantısı, hortumlar ve soketler sağlam | evet |
| `plates_reflectors` | Number plates and reflectors in place | Tablice rejestracyjne i odblaski na miejscu | Plakalar ve reflektörler yerinde | — |
| `dashboard_warnings` | No warning lights on the dashboard | Brak kontrolek ostrzegawczych na desce | Gösterge panelinde uyarı lambası yok | — |
| `safety_equipment` | Safety equipment complete (vest, triangle, extinguisher, first aid) | Wyposażenie bezpieczeństwa kompletne (kamizelka, trójkąt, gaśnica, apteczka) | Güvenlik ekipmanı tam (yelek, üçgen, söndürücü, ilk yardım) | — |
| `container_locks` | Container locks (twist-locks) closed | Zamki kontenera (twist-locki) zamknięte | Konteyner kilitleri (twist-lock) kapalı | evet |

İpuçları (`hints`): `no_new_damage` → EN "Body, cab, glass, bumpers" · PL "Nadwozie, kabina, szyby, zderzaki" · TR "Kasa,
kabin, camlar, tamponlar"; `container_locks` → EN "Only when a container is loaded" · PL "Tylko gdy kontener jest załadowany"
· TR "Sadece konteyner yüklüyse".

## 7. `POST shift/start` — araç + kontrol + mesai başlangıcı

Tek komut: etiket okutma, kontrol cevapları ve başlangıç birlikte gönderilir (kısmi durum yoktur).

```json
{
  "commandId": "0b7c7a0e-43a4-4a8e-9d8e-21c4b1a6f0e3",
  "vehicleTagUid": "04A224B2C35E80",
  "manualVehicleId": null,
  "tagScannedAtUtc": "2026-09-19T05:49:02.120Z",
  "startedAtUtc": "2026-09-19T05:52:10.431Z",
  "elapsedSinceStartMs": 1520,
  "installationId": "e1f0c2d4-5b6a-4c7d-8e9f-0a1b2c3d4e5f",
  "restWarningAcknowledged": false,
  "vehicleCheck": {
    "templateId": "b71e…",
    "templateVersion": 1,
    "items": [
      { "key": "no_new_damage", "result": "Ok" },
      { "key": "lights", "result": "Defect", "note": "Sol stop lambası yanmıyor", "photoIds": ["5d2b…"] },
      { "key": "container_locks", "result": "NotApplicable" }
    ]
  }
}
```

| Alan | Kural |
| --- | --- |
| `commandId` | UUID, zorunlu; tekrar gönderimde değişmez (§2). |
| `vehicleTagUid` / `manualVehicleId` | Tam olarak biri dolu. `manualVehicleId` yalnızca etiket okunamadığında ve yalnızca `plannedVehicle.vehicleId` olabilir; mesai `identifiedBy = Manual` olarak incelemeye düşer. |
| `tagScannedAtUtc` | Etiket okutma (veya elle seçim) anı, telefon saati. |
| `startedAtUtc` | Şoförün kontrolü gönderip başlattığı an, telefon saati. Resmi başlangıç §2 zaman kuralıyla hesaplanır. |
| `elapsedSinceStartMs` | `startedAtUtc`'den gönderime kadar monotonic süre; telefon yeniden başladıysa `null`. |
| `restWarningAcknowledged` | Uygulama "dinlenme henüz bitmedi" uyarısını gösterdi ve şoför yine başlattı. Sunucu `startedBeforeRestEnd`'i kendi snapshot'ına göre ayrıca hesaplar. |
| `vehicleCheck.templateId` | Kullanılan şablon; varsayılan şablonda `null` (§6.1). |
| `items` | Şablondaki her madde tam bir kez. `result`: `Ok` · `Defect` · `NotApplicable` (yalnızca izinli maddede). `note` en fazla 500 karakter, yalnızca `Defect`'te. `photoIds`: yalnızca `Defect`'te, fotoğraf başına istemcinin ürettiği UUID; `photoRequiredOnDefect` ise en az bir tane. |

İşleme sırası: idempotency (§2) → erişim → etiket/araç → kontrol doğrulaması → zaman → açık mesai (§9.1 kuralı uygulandıktan
sonra hâlâ açık mesai varsa `409 shift_already_active`) → kayıt → **201**.

Yanıt (201 ve idempotent 200):

```json
{
  "shift": { "…": "ShiftDto (§4)" },
  "vehicleCheck": { "id": "c9e2…", "pendingPhotoIds": ["5d2b…"] }
}
```

Hatalar: `400 vehicle_tag_unknown`, `400 vehicle_check_invalid`, `400 shift_time_invalid`, `400 shift_too_old`,
`409 shift_already_active`, `409 command_conflict`, `403 driver_access_required`.

Kontrolde `Defect` varsa mesai **yine başlar** (kullanıcı kararı); sunucu kusurları planlamaya bildirir (§10).

## 8. `POST vehicle-checks/{checkId}/photos` — kusur fotoğrafı

`multipart/form-data`: `photoId` (metin, başlangıç isteğindeki UUID) ve `photo` (JPEG veya PNG, en fazla 10 MB). Uygulama
kamera fotoğrafını yönü uygulanmış, EXIF'siz JPEG olarak gönderir (R10 ile aynı hazırlık). `photoId` ile idempotent: aynı fotoğraf tekrar
gelirse **200**.

```json
{ "photoId": "5d2b…", "receivedAtUtc": "2026-09-19T05:53:01Z" }
```

Hatalar: `400 photo_unexpected` (bu kontrolde böyle bir `photoId` yok), `400 invalid_document`/`413` (dosya),
`404` (kontrol yok). Fotoğraflar mesai başlangıcı kabul edildikten sonra ayrı ayrı gönderilir; eksik fotoğraf
`photosExpected`/`photosReceived` ile web'de görünür.

## 9. `POST shift/end` — mesaiyi bitir

```json
{
  "commandId": "7f3a…",
  "shiftId": "8d1f…",
  "startCommandId": "0b7c…",
  "endedAtUtc": "2026-09-19T16:31:40Z",
  "elapsedSinceEndMs": 800,
  "installationId": "e1f0c2d4-5b6a-4c7d-8e9f-0a1b2c3d4e5f"
}
```

`shiftId` veya `startCommandId`'den en az biri dolu (başlangıç henüz gönderilmemişken telefon mesai kimliğini bilmez).
Yanıt 200/201: `{ "shift": ShiftDto }` (`endedBy = Driver`). Hatalar: `409 shift_not_active` (mesai zaten kapalı ya da yok),
`409 command_conflict`, `400 shift_time_invalid`.

Devam eden sefer mesai bitirmeyi **engellemez**: uzun seferde şoför günlük dinlenme için mesaiyi bitirir, dinlenmeden sonra
yeni mesaide aynı sefere devam eder.

### 9.1 Takograf ile otomatik kapanış

Şoför mesaiyi uygulamadan bitirmeyi unutursa: açık mesai varken §3'e göre günlük dinlenme başlamışsa
(`dailyRest.state = Resting`, `startedAtUtc > mesai başlangıcı`), backend mesaiyi dinlenmenin başladığı anla kapatır
(`endedBy = Tachograph`). Bu kural en geç yeni bir `shift/start` işlenirken uygulanır; Webfleet senkronunda da
uygulanabilir. Uygulama da aynı kuralla ekranda mesaiyi "takografa göre bitti" gösterir ve *Mesai başlat* sunar.

## 10. Web / ofis tarafı

1. **Araç ana verisi:** çekiciye NFC etiketi UID'si (docs/15 §5.1 normalleştirmesi, etkin araçlar arasında benzersiz, pasife alma).
   Dorse etiketi bu sürümde yok.
2. **Kontrol şablonu:** maddeler, EN/PL/TR başlık ve ipucu, fotoğraf zorunluluğu, *Geçerli değil* izni, sürümleme. İlk kayıt §6.1.
3. **Mesailer:** şoför, başlangıç/bitiş (zaman kaynağı), araç ve tanıma yolu (`Nfc`/`Manual` işaretli), `startedBeforeRestEnd`
   işareti, `endedBy`.
4. **Kontrol sonuçları:** mesai başına maddeler, not ve fotoğraflar. `DefectsReported` planlamaya bildirim/uyarı olarak düşer;
   isteğe bağlı olarak Atölye modülünde iş emri oluşturma. Uygulama aracı kilitlemez.
5. **Webfleet:** production'da sürücü süre senkronu açık olmalı (Integration schedules), `FleetIntelligenceFoundation`
   migration'ı uygulanmış olmalı ve şoförlerin `WebfleetDriverId` eşleştirmesi yapılmış olmalı; aksi halde `driver-times`
   `NotLinked`/`NoData` döner ve alarm kurulmaz.

## 11. Push (isteğe bağlı, ilk sürüm için zorunlu değil)

`type = "driver.times"`, yalnızca veri (bildirim metni yok), yüksek öncelik. Şoförün `dailyRest.state` değeri değiştiğinde
veya `endsAtUtc` 5 dakikadan fazla kaydığında gönderilir. Uygulama `driver-times`'ı okur ve alarmı yeniden kurar. Push
olmadan uygulama ~30 dakikada bir, uygulama açılınca ve bağlantı gelince okur.

## 12. Android tarafı (bu depo)

- `app.driverShift=false` (varsayılan, `gradle.properties`): bugünkü davranış; mesai uçları çağrılmaz, alarm yoktur.
- `app.driverShift=true` (ör. `-Papp.driverShift=true`): şoför modülünde takograf kartı, dinlenme alarmı, NFC + kontrol ile
  mesai başlatma ve mesai bitirme açılır. Sunucu `GET shift` için `404` (api_disabled değil) dönerse mesai "sunucuya bağlı
  değil" gösterilir. Demo modunda bu akış yoktur (sahte veri üretilmez).
- Kararlar ve varsayımlar: `ASSUMPTIONS.md` S1–S16.

## 13. Backend kabul listesi (Codex)

1. `driver-times`: `Available`/`NotLinked`/`NoData`; `Resting`/`Working` türetimi Webfleet 1.74 alanlarıyla belgelenmiş; negatif
   süreler korunur, eksikler `null`; eski snapshot `referenceTimeUtc` ile görünür.
2. `shift`: `active`/`lastEnded`/`plannedVehicle`; §9.1 otomatik kapanış.
3. `vehicle-tags/{uid}`: bilinen/bilinmeyen/pasif etiket, UID normalleştirmesi.
4. `vehicle-check/template`: §6.1 ilk kayıt; sürüm artışı; `templateId = null` ile varsayılan anahtarlar kabul.
5. `shift/start`: idempotency (elapsed farklı → 200 aynı gövde; içerik farklı → 409), `Manual` yalnızca planlanan araçla,
   kontrol doğrulaması, zaman kuralı, açık mesai → 409, `Defect` mesaiyi engellemez.
6. `vehicle-checks/{id}/photos`: `photoId` idempotency, beklenmeyen fotoğraf 400, 10 MB sınırı.
7. `shift/end`: `startCommandId` ile çözümleme, kapalı mesai → 409 `shift_not_active`.
8. Web: araç NFC etiketi, şablon editörü, mesai ve kontrol listeleri, kusur bildirimi.
9. OpenAPI `driver-v1` ve `docs/` kılavuzu; production migration ve yayın kullanıcı onayıyla.

## 14. Codex için görev metni (backend)

> DynamicONE backend'e şoför mesaisi, takograf dinlenme özeti, araç NFC etiketi ve günlük araç kontrolü ekle. Sözleşme:
> Android reposundaki `docs/16_DRIVER_SHIFT_REST_AND_VEHICLE_CHECK.md` (bu doküman). Uçlar Driver API (`/api/driver/v1`)
> altında; mevcut Driver API davranışını ve DTO'larını bozma. Takograf verisi için mevcut `WebfleetDriverTimeSnapshots`
> ve `DriverProfile.WebfleetDriverId` kullan, yeni Webfleet isteği ekleme; `dailyRest` türetimini WEBFLEET.connect 1.74
> alanlarına göre belgele. Araç etiketi `OperationalVehicle` üzerinde (UID normalleştirmesi docs/15 §5.1), kontrol
> şablonu sürümlü ve §6.1 ile tohumlanmış olsun. `shift/start` ve `shift/end` `commandId` ile idempotent, zaman kuralı
> docs/15 §5.4 ile aynı. Kusurlu kontrol mesaiyi engellemez, planlamaya bildirilir. Endpoint veya alan adı değiştirmen
> gerekirse dokümanı da güncelle ve farkı açıkça raporla. §13 kabul listesini testlerle doğrula. Production veritabanına
> migration uygulama ve yayın yapma; migration betiğini ve dağıtım adımlarını hazırla.

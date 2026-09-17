# 15 — DynamicONE App: Mobile API v1 ve NFC ofis mesaisi (backend sözleşme önerisi)

Durum: **backend'de uygulandı, henüz yayında değil** (17.09.2026). Uygulama: `dynamictransport/DynamicONE` branch
`feature/mobile-api-nfc-attendance` (commit 2cfb6cb7), migration `20260917130519_MobileNfcAttendance`, kılavuz
`docs/80_MobileAppApi.md`. Backend'in gerçek HTTP yanıtları Android testlerinde sözleşme testi olarak kullanılır
(`app/src/test/resources/mobile-v1`). Bir alan değişirse iki taraf birlikte değişir; tek kaynak yayındaki OpenAPI
(`mobile-v1`) dokümanıdır.

## 1. Amaç

DynamicONE Driver uygulaması **DynamicONE App** oldu: şoförler ve ofis personeli aynı uygulamayı kullanır.
Hangi ekranların görüneceğine uygulama değil, sunucu karar verir (`me.modules`).

İlk iki modül:

| Modül anahtarı | Kim | Uygulamada |
| --- | --- | --- |
| `driver` | Driver API erişimi olan şoför (bugünkü `driver_access_required` kuralı) | Bugünkü şoför ekranları (`/api/driver/v1`) |
| `officeAttendance` | Ofis mesai takibine dahil edilebilen aktif personel | Mesai (NFC giriş/çıkış) ve Mesai kayıtlarım |

İleride eklenecek modüller aynı listeye yeni bir anahtar olarak eklenir. Uygulama bilmediği anahtarı yok sayar.

## 2. Genel kurallar

- Taban adres: `/api/mobile/v1/`. Yalnızca HTTPS.
- Driver API ile aynı kurallar: `Authorization: Bearer`, cookie yok; hatalar `application/problem+json`
  (`status`, `code`, `title`, `traceId`); 429'da `Retry-After`; API kapalıysa `404 api_disabled`.
- İstemci başlıkları: `X-App-Version`, `X-Platform: android`.
- Zamanlar ISO-8601 UTC (`Z`). İş günü tarihleri `yyyy-MM-dd`.
- Enum değerleri metin olarak gönderilir, sayı olarak değil.
- Kimlik her zaman token'dan çözülür: istemci kullanıcı, personel veya şoför kimliği göndermez.

## 3. Kimlik doğrulama

`/api/driver/v1/auth/*` ile **aynı istek/yanıt gövdeleri ve hata kodları**:

| Uç | Not |
| --- | --- |
| `POST auth/login` | `{ userName, password, twoFactorCode? }` → token yanıtı (`tokenType`, `accessToken`, `expiresIn`, `refreshToken`, `refreshTokenExpiresAtUtc`, `mustChangePassword`). |
| `POST auth/refresh` | `{ refreshToken }` → yeni token çifti (rotasyonlu, tek kullanımlık). |
| `POST auth/logout` | 204. Refresh token'ı iptal eder. |
| `POST auth/change-password` | `{ currentPassword, newPassword }` → 204; tüm oturumlar kapanır. Identity hata kodları `errors` içinde. |

Farklar:

- Giriş, **en az bir mobil modülü olan** her aktif kullanıcıya açıktır. Hiç modülü yoksa
  `403 mobile_access_required`.
- 2FA, kilitleme, `password_change_required` davranışı Driver API ile aynıdır.

Token uyumluluğu (zorunlu):

1. Mobile API ve Driver API **aynı oturum/refresh token deposunu** kullanır.
2. `/api/mobile/v1/auth/login` ile alınan access token, kullanıcının şoför erişimi varsa `/api/driver/v1/*`
   uçlarında geçerlidir (yoksa bugünkü gibi `403 driver_access_required`).
3. Driver API'den alınmış bir refresh token `/api/mobile/v1/auth/refresh` ile yenilenebilir (kurulu
   uygulamalar oturum kaybetmeden yeni sürüme geçer).
4. `POST /api/driver/v1/me/devices` ve `DELETE …/me/devices/{installationId}` mobile token ile çalışır.

## 4. `GET me`

```json
{
  "userId": "7b2f7a0e-2d7e-4c1a-9a51-3a1f0f2c9d10",
  "employeeId": "c3a1d5f2-8e44-4b0e-a0b8-6f2b1c9e7a55",
  "driverId": null,
  "displayName": "Ayşe Yılmaz",
  "preferredLanguageCode": "tr",
  "modules": ["officeAttendance"]
}
```

| Alan | Kural |
| --- | --- |
| `userId` | Identity kullanıcı kimliği, zorunlu. |
| `employeeId` | `EmployeeProfile.Id`; personel kaydı yoksa `null`. |
| `driverId` | Şoför erişimi varsa Driver API `me.driverId` ile aynı değer, yoksa `null`. |
| `displayName` | Görünen ad. |
| `preferredLanguageCode` | `en` / `pl` / `tr` veya `null`. |
| `modules` | Açık modül anahtarları, sırasız, tekrarsız. `driver` ⇔ `driverId != null`. `officeAttendance` ⇔ kullanıcı ofis mesai takibine dahil (bkz. §5.1). |

## 5. Ofis mesaisi (NFC)

### 5.1 Kapsam ve mevcut Attendance modülüyle ilişki

Backend'deki **Mesai Yönetimi** modülü (TH-2026-0434, `docs/68_OfficeAttendance.md`, tablolar
`AttendanceDevices`, `AttendancePunches`, `AttendanceCorrections` …) aynen kullanılır; mobil okutma yeni bir
**kayıt kaynağıdır**, yeni bir hesaplama değildir.

- `officeAttendance` modülü, web modülünün yeni atama/eşleştirme için kullandığı kuralla belirlenir: aktif
  `EmployeeProfile`, personel ayarlarında şoför/teknisyen değil. (Plan ataması zorunlu mu, backend kararı;
  öneri: zorunlu değil, planı olmayan gün raporda zaten görünür.)
- **NFC noktası:** her fiziksel etiket bir `AttendanceDevice` (veya ayrı bir `AttendanceNfcPoint` tablosu) olarak
  kaydedilir: ad (ör. "Kraków ofis — giriş"), konum, saat dilimi, **UID**, **yön** (`In` / `Out`), etkin/pasif.
  Web: *Mesai Cihazları* sayfasında "NFC etiketi" tipi; UID etkin etiketler arasında benzersiz.
- **UID biçimi:** büyük harf hex, ayraçsız, 4–10 bayt (`^([0-9A-F]{2}){4,10}$`), ör. `04A224B2C35E80`.
  Uygulama etiketin anti-collision kimliğini (`Tag.getId()`) bu biçimde gönderir. Web formu `04:A2:24:…` gibi
  girilen değeri aynı biçime normalleştirmelidir.
- **Kayıt:** `AttendancePunch`, `Source = MobileNfc` (yeni enum değeri), cihaz = NFC noktası,
  `Direction` = etiketin yönü, `OccurredAtUtc` §5.4'e göre. Mobil kayıtta personel doğrudan bilinir: punch'a
  personel bağlantısı (ör. nullable `EmployeeProfileId`) veya nokta başına otomatik eşleştirme gerekir; hangi
  yolun seçileceği backend kararı. `Fingerprint` = kullanıcı + `commandId` üzerinden hash (mükerrer engeli).
- Günlük takip, ham kayıtlar, düzeltme/onay ve raporlar mobil kayıtları kaynak ayrımıyla birlikte gösterir.
  Tekrarlı yön, eksik çıkış vb. bugünkü gibi **reddedilmez, incelemeye düşer**.

### 5.2 `GET attendance/status`

Ana ekrandaki durum ve tek ana buton.

```json
{
  "state": "CheckedIn",
  "nextAction": "CheckOut",
  "workDate": "2026-09-17",
  "timeZoneId": "Europe/Warsaw",
  "lastPunch": {
    "id": "5a0d0c55-6d3b-4f55-8c1b-2f8b1a8f7e21",
    "commandId": "0b7c7a0e-43a4-4a8e-9d8e-21c4b1a6f0e3",
    "direction": "In",
    "occurredAtUtc": "2026-09-17T06:57:12Z",
    "receivedAtUtc": "2026-09-17T06:57:13Z",
    "workDate": "2026-09-17",
    "source": "MobileNfc",
    "pointName": "Kraków ofis — giriş"
  }
}
```

| Alan | Kural |
| --- | --- |
| `state` | `CheckedIn` / `CheckedOut`. |
| `nextAction` | **`CheckOut` ⇔ içinde bulunulan iş gününün (`timeZoneId`) en son geçerli kaydı `In`**; aksi halde `CheckIn`. Önceki günden kalan açık giriş ertesi gün `CheckIn` sunar (gece yarısını aşan vardiya kapsam dışı). |
| `workDate` | Sunucu saatine göre `timeZoneId` içindeki bugünün tarihi. |
| `timeZoneId` | Personelin plan saat dilimi; plan yoksa son kullandığı NFC noktasının; o da yoksa şirket varsayılanı. |
| `lastPunch` | Herhangi bir günün en son geçerli kaydı (düzeltmeler uygulanmış) veya `null`. |

Hatalar: `403 attendance_access_required`.

### 5.3 `POST attendance/punches`

İstek:

```json
{
  "commandId": "0b7c7a0e-43a4-4a8e-9d8e-21c4b1a6f0e3",
  "direction": "In",
  "tagUid": "04A224B2C35E80",
  "scannedAtUtc": "2026-09-17T06:57:12.345Z",
  "elapsedSinceScanMs": 1520,
  "installationId": "e1f0c2d4-5b6a-4c7d-8e9f-0a1b2c3d4e5f"
}
```

| Alan | Kural |
| --- | --- |
| `commandId` | UUID, zorunlu. Uygulama okutma anında üretir, tekrar gönderimde değiştirmez. |
| `direction` | `In` (Mesai Başlat) / `Out` (Mesaiyi Bitir), zorunlu. Kullanıcının bastığı buton. |
| `tagUid` | §5.1 biçimi, zorunlu. |
| `scannedAtUtc` | Okutma anında **telefon saati**, zorunlu. |
| `elapsedSinceScanMs` | Okutmadan bu isteğin gönderilmesine kadar geçen süre, telefonun açılıştan beri sayan monotonic saatiyle (kullanıcı değiştiremez). Telefon arada yeniden başlatıldıysa `null` veya alan hiç gönderilmez (ikisi aynı anlamdadır). |
| `installationId` | Uygulama kurulum kimliği (Driver API `me/devices` ile aynı değer; donanım kimliği değil). Denetim için. |

İşleme sırası:

1. **Idempotency:** aynı kullanıcı + `commandId` daha önce işlendiyse ilk sonuç **200** ile aynen döner.
   Kimlik alanları `commandId`, `direction`, `tagUid`, `scannedAtUtc`, `installationId`dir; `elapsedSinceScanMs` her denemede
   değiştiği için karşılaştırılmaz ve resmi zaman ilk kabul edilen istekteki değerdir. Kimlik alanlarından biri farklıysa
   `409 command_conflict`.
2. Erişim: `officeAttendance` yoksa `403 attendance_access_required`.
3. Etiket: etkin NFC noktası bulunamazsa `400 attendance_tag_unknown`.
4. Yön: etiketin yönü `direction` ile aynı değilse `400 attendance_tag_direction_mismatch`.
5. Zaman (§5.4) geçersizse `400 attendance_time_invalid` veya `400 attendance_punch_too_old`.
6. Kayıt yazılır → **201**.

Yanıt (201 ve idempotent 200):

```json
{
  "punch": { "...": "GET attendance/status → lastPunch ile aynı nesne" },
  "status": { "...": "GET attendance/status ile aynı nesne, kayıt sonrası" }
}
```

Uygulamanın hata davranışı: 400/409 kalıcı hata (kayıt "Gönderilemedi" + sebep); 401 refresh; 403 ve 404
`api_disabled` "gönderilmedi", daha sonra aynı `commandId` ile tekrar; 429 `Retry-After` sonrası; bağlantı
hatası/5xx aynı `commandId` ile otomatik tekrar. Bu yüzden 1. adımın idempotency'si zorunludur.

### 5.4 Okutma zamanı (öneri)

Uygulama çevrimdışı çalışır: kapı önünde internet yoksa kayıt telefonda bekler ve bağlantı gelince gönderilir.
Resmi saat telefon saatine bırakılmamalıdır:

- `elapsedSinceScanMs` varsa: `OccurredAtUtc = receivedAtUtc − elapsedSinceScanMs` (zaman kaynağı
  `ServerElapsed`).
- `null` ise: `OccurredAtUtc = scannedAtUtc` (zaman kaynağı `DeviceClock`), ham kayıtlarda işaretli gösterilir.
- `OccurredAtUtc > receivedAtUtc + 2 dk` → `attendance_time_invalid`.
- `receivedAtUtc − OccurredAtUtc > 7 gün` → `attendance_punch_too_old` (otomatik işçinin geriye bakma süresi
  ile uyumlu; kesin süre backend/ürün kararı).
- `|scannedAtUtc − (receivedAtUtc − elapsed)| > 5 dk` ise kayıt kabul edilir ama incelemeye düşer (telefon saati
  yanlış).

### 5.5 `GET attendance/days?from=2026-09-01&to=2026-09-30`

Personelin kendi kayıtları. `from`/`to` iş günü tarihleri, dahil. Uygulama her zaman bir takvim ayını ister (ayın 1'i –
son günü). `to` bugünden sonraysa sunucu bugüne kırpar (telefonun ve ofisin saat dilimi farklı olabilir);
`from > to` veya 31 günden uzun aralık → `400 invalid_date_range`.

```json
{
  "timeZoneId": "Europe/Warsaw",
  "days": [
    {
      "workDate": "2026-09-16",
      "state": "Complete",
      "workedMinutes": 492,
      "punches": [
        { "id": "…", "commandId": "…", "direction": "In",  "occurredAtUtc": "2026-09-16T06:58:40Z", "receivedAtUtc": "2026-09-16T06:58:41Z", "workDate": "2026-09-16", "source": "MobileNfc", "pointName": "Kraków ofis — giriş" },
        { "id": "…", "commandId": null,  "direction": "Out", "occurredAtUtc": "2026-09-16T15:10:02Z", "receivedAtUtc": null, "workDate": "2026-09-16", "source": "Correction", "pointName": null }
      ]
    }
  ]
}
```

| Alan | Kural |
| --- | --- |
| `days` | Yalnızca en az bir kaydı olan günler, **en yeni gün önce**. |
| `state` | `Complete` (geçerli giriş/çıkış çiftleri) · `Open` (bugün, son kayıt giriş) · `Incomplete` (geçmiş gün, eksik giriş/çıkış) · `NeedsReview` (tekrarlı yön, bekleyen düzeltme vb.). |
| `workedMinutes` | Web *Günlük Takip* ile aynı hesap (tamamlanmış çiftler, sabit mola düşülmüş). `Complete` değilse `null`; eksik süre 0 gösterilmez. |
| `punches` | Düzeltmeler uygulanmış geçerli kayıtlar, eskiden yeniye. Geçersiz sayılan kayıt listelenmez. |
| `source` | `MobileNfc` · `Terminal` (kart okuyucu/CSV) · `Correction` (onaylı düzeltme). |

Geç giriş, erken çıkış, planlanan süre ve fazla mesai bu uçta **yoktur** (ürün kararı gelmeden personele
gösterilmez).

## 6. Güvenlik ve risk

- Standart NFC etiketinin UID'si kopyalanabilir (kullanıcı kararı 17.09.2026: ilk sürümde standart etiket).
  Kopyalanamaz okutma gerekirse sonraki sürüm NTAG 424 DNA (SUN/SDM imzalı NDEF) olabilir; bu, sözleşmeye yeni
  isteğe bağlı bir alan ekler.
- Etiket UID listesi uygulamaya **verilmez**; doğrulama yalnızca sunucudadır.
- Log/denetim kayıtlarına token, şifre yazılmaz. `installationId` ve UID yalnızca mesai yetkililerine görünür.
- Oran sınırı: `attendance/punches` için kullanıcı başına makul bir limit (öneri: dakikada 20).

## 7. Backend kabul listesi (Codex)

1. `me` modül listesi: şoför, ofis personeli, ikisi de olmayan (`mobile_access_required`) hesaplar.
2. Mobile login token'ı Driver API'de, Driver API refresh token'ı Mobile API refresh'te çalışıyor.
3. Aynı `commandId` iki kez → tek kayıt, ikinci yanıt 200 ve aynı gövde; farklı gövde → 409.
4. Bilinmeyen/pasif etiket, yanlış yön, gelecekteki/çok eski zaman hataları doğru kodlarla.
5. `elapsedSinceScanMs` ile zaman hesabı; `null` iken `DeviceClock` işareti.
6. `nextAction` kuralı: bugün In → CheckOut; dünden açık In → CheckIn; DST günü.
7. `attendance/days` 31 gün sınırı, `workedMinutes` web raporuyla aynı, eksik günde `null`.
8. Web: NFC etiketi ekleme (UID normalleştirme, benzersizlik, yön), ham kayıtlarda kaynak ve zaman kaynağı.
9. OpenAPI grubu (ör. `mobile-v1`) ve `docs/` kılavuzu; production migration kullanıcı onayıyla.

## 8. Android tarafında geçiş

- `app.mobileApi=false` (varsayılan, `gradle.properties`): giriş, refresh ve `me` bugünkü gibi Driver API'den yapılır;
  oturumun tek modülü `driver`'dır. Ofis personeli bu sürümde giriş yapamaz (`driver_access_required`).
- `app.mobileApi=true`: giriş, refresh, çıkış, şifre değişikliği ve `me` `/api/mobile/v1/` üzerinden; ekranlar
  `me.modules`'a göre açılır. Şoför ekranları Driver API uçlarını aynı token ile kullanmaya devam eder.
- Mobile API adresi Driver API adresinden türetilir (`…/api/driver/v1/` → `…/api/mobile/v1/`); ayrı adres ayarı yoktur.
- Backend yayına alınınca: `gradlew.bat assembleDebug -Papp.mobileApi=true` ile cihazda denenir, sonra varsayılan `true`
  yapılır.

## 9. Codex için görev metni (backend)

> DynamicONE backend'e DynamicONE App için Mobile API v1 ekle. Sözleşme: Android reposundaki
> `docs/15_MOBILE_APP_API.md` (bu doküman). Mevcut Driver API (`/api/driver/v1`) davranışını ve DTO'larını bozma;
> aynı oturum/refresh token deposunu kullan (§3). Ofis mesaisi için mevcut Attendance modülünü (TH-2026-0434,
> `docs/68_OfficeAttendance.md`) genişlet: NFC etiketi noktası (UID + yön), `AttendanceSource.MobileNfc`, personele
> doğrudan bağlı punch, `commandId` idempotency, §5.4 zaman kuralı, `attendance/status`, `attendance/punches`,
> `attendance/days`. Web'de *Mesai Cihazları* sayfasına NFC etiketi ekleme/pasife alma ekle. Endpoint veya alan adı
> değiştirmen gerekirse dokümanı da güncelle ve farkı açıkça raporla. §7 kabul listesini testlerle doğrula. Production
> veritabanına migration uygulama ve yayın yapma; migration betiğini ve dağıtım adımlarını hazırla.

# 13 — Push bildirimleri (FCM): backend sözleşme önerisi

**Durum (15.09.2026): backend'de uygulandı ve yayında** (TH-2026-0444; yayın TH-2026-0451; kesin sözleşme backend `docs/75_DriverPushNotifications.md`). Bu doküman ilk öneridir; farklılık olursa backend dokümanı geçerlidir. Üretimde FCM gönderiminin etkinleştirilmesi (migration, Firebase kimliği) backend'de ayrı bir adımdır.

## 1. Amaç

Planlamacı veya ofis şoföre mesaj gönderdiğinde (kokpit sefer sohbeti veya genel sohbet), uygulama kapalıyken bile telefonda bildirim görünmesi. Aynı altyapı ileride şu olaylar için kullanılabilir (docs/06): yeni sefer, sefer değişikliği/iptali, belge reddi.

## 2. İlkeler

- Push yalnızca **tetikleyicidir**; içeriğin kaynağı API'dir (docs/06).
- Push içeriğinde mesaj metni, kişisel veri veya token **yoktur**.
- Bildirim bir okundu bilgisi değildir. Okundu bilgisi yalnızca uygulamada mesaj ekranda görüldüğünde `POST messages/{messageId}/read` ile gönderilir.
- Push gönderimi en iyi çabadır: gönderim hatası mesajın kaydedilmesini engellemez.

## 3. Önerilen endpoint'ler

### 3.1 `POST /api/driver/v1/me/devices`

Bearer kimlik doğrulaması. Gövde:

```json
{
  "installationId": "3f0c9f5e-6a51-4c7e-9a43-2b8d7c1e9a10",
  "platform": "Android",
  "pushToken": "<FCM registration token>",
  "appVersion": "0.1.0",
  "locale": "tr"
}
```

- `204 No Content`. Aynı `installationId` ile tekrar çağrı kaydı günceller (idempotent upsert).
- Aynı `pushToken` başka bir kullanıcıya kayıtlıysa önceki kayıt silinir (cihaz el değiştirdi).
- `400` problem+json (`code`: öneri `invalid_device`), `401` oturum yok.
- Uygulama çağırır: girişten sonra, FCM token yenilendiğinde, açılışta kayıtlı token değişmişse.

### 3.2 `DELETE /api/driver/v1/me/devices/{installationId}`

- `204 No Content`; kayıt yoksa da `204`.
- Uygulama çıkışta, oturumu kapatmadan önce çağırır.
- Sunucu ayrıca şu durumlarda kullanıcının cihaz kayıtlarını siler: şifre değişikliği, hesap pasifleştirme, oturumların iptali; FCM'nin `UNREGISTERED` veya geçersiz token yanıtı.

### 3.3 Veri modeli önerisi

Additive migration ile `DriverDevices`: `Id`, `UserId`, `DriverProfileId`, `InstallationId` (unique), `Platform`, `PushToken`, `AppVersion`, `Locale`, `CreatedAtUtc`, `UpdatedAtUtc`, `LastSeenAtUtc`.

## 4. Push mesajı (FCM HTTP v1)

**Tetik:** `ToDriver` yönlü bir `DriverMessage` kaydı oluştuğunda (kokpit sefer sohbeti ve genel mesaj), transaction commit edildikten sonra, hedef şoförün kayıtlı cihazlarına. Aynı `commandId` tekrarında ikinci push gönderilmez.

**Öneri: `notification` + `data`.** Uygulama süreci işletim sistemi tarafından kapatılmış olsa da bildirimin görünmesi için sistem bildirimi kullanılır; metin yerelleştirme anahtarlarıyla uygulamadan gelir, mesaj içeriği taşınmaz.

```json
{
  "message": {
    "token": "<pushToken>",
    "android": {
      "priority": "HIGH",
      "notification": {
        "channel_id": "messages",
        "tag": "message-<tripId veya general>",
        "title_loc_key": "push_message_title_trip",
        "title_loc_args": ["DT-2026-000012-01"],
        "body_loc_key": "push_message_body"
      }
    },
    "data": {
      "type": "driver.message",
      "messageId": "<guid>",
      "tripId": "<guid veya boş>",
      "sentAtUtc": "2026-09-14T10:34:00Z"
    }
  }
}
```

- `tag` aynı sohbetin bildirimini günceller: aynı seferden art arda gelen mesajlar tek bildirimde kalır.
- Genel mesajda `title_loc_key` = `push_message_title_general`, `title_loc_args` yoktur.
- Sefer numarası iş tanımlayıcısıdır; kişi adı veya mesaj metni gönderilmez.
- Uygulama açıkken bildirim sistem tarafından gösterilmez; uygulama `data` ile gelen kutusunu okur, ilgili sohbet ekranda açık değilse kendi bildirimini gösterir.

## 5. Güvenlik ve işletim

- FCM service account kimliği yalnızca backend'in gizli ayarlarında durur; uygulamaya konmaz.
- Push token kişisel veri gibi ele alınır: log ve telemetride yazılmaz veya maskelenir; kullanıcı silinince silinir.
- Mevcut hız sınırları geçerlidir (driver uçları kullanıcı başına 120 istek/dk).

## 6. Mobil uygulama tarafı

**15.09.2026 durumu:** Aşağıdakiler uygulamada hazırdır. Cihaz kaydı (`RealPushRegistrar`): girişten sonra, oturum varken her uygulama açılışında ve FCM token yenilenince `POST me/devices`; çıkışta, oturum kapatılmadan önce `DELETE me/devices/{installationId}`. Kayıt en iyi çabadır: hata oturumu etkilemez, sonraki açılışta tekrar kaydolunur. Oturum yoksa veya demo modunda istek gönderilmez. Token saklanmaz ve loglanmaz.

- Firebase Messaging, `app/google-services.json` (Git'e girmez) varsa derlemede etkinleşir; dosya yoksa uygulama push olmadan çalışır.
- FCM kaydı yalnızca gerçek backend'e giriş yapmış şoför için başlar (manifest'te auto-init kapalı). Çıkışta token silinir ve uygulamanın bildirimleri temizlenir. Cihaz kaydını silme çağrısı oturum kapatılmadan (erişim token'ı geçerliyken) yapılır.
- Android 13+ için `POST_NOTIFICATIONS` izni girişten sonra bir kez istenir. Ret halinde uygulama çalışmaya devam eder; Mesajlar listesinde "Bildirimler kapalı" uyarısı ve açma düğmesi gösterilir.
- Bildirim kanalı `messages` ("Mesajlar"); sistemin push'tan gösterdiği bildirimler için varsayılan kanal ve ikon manifest'te tanımlıdır. Yerelleştirme anahtarları uygulamada vardır: `push_message_title_trip`, `push_message_title_general`, `push_message_body`. Kodda referansları olmadığı için release build'in kaynak küçültmesi bunları silmesin diye `app/src/main/res/raw/keep.xml` içinde tutulurlar.
- Uygulama açıkken `type = driver.message` verisi gelince gelen kutusu API'den okunur. Ekranda açık olmayan ve yeni okunmamış mesajı olan her sohbet için `message-<tripId|general>` etiketli tek bildirim gösterilir; kilit ekranında mesaj içeriği gizlenir. Ekranda açık sohbet için bildirim gösterilmez, sohbet hemen yeniden okunur. Gelen kutusu okuması başarılı olmuş bir `messageId` tekrar gelirse işlenmez. Okundu bilgisi (`{}` gövdesiyle) yalnızca mesaj ekranda gerçekten görüldüğünde gönderilir; bildirimin gelmesi okundu sayılmaz.
- Bildirime dokununca ilgili sohbet (genel veya sefer) açılır; sohbet ekranda açılınca bildirimi kalkar.
- **18.09.2026 (öneri, docs/16 §11):** `type = driver.times` yalnızca veri push'u; şoförün takograf dinlenmesi değişince
  gönderilir. Uygulama (`app.driverShift=true`) `driver-times` ve `shift`'i okur, uyandırma alarmını yeniden kurar;
  bildirim göstermez. Dinlenme alarmı push değildir: telefonda kurulur (kanallar `rest_alarm`, `rest_reminder`).
- Backend hazır olmadan deneme: Firebase konsolu → Messaging → yeni bildirim kampanyası → hedef uygulama `com.dynamictransport.driver`. Özel veri olarak `type` = `driver.message` ve `tripId` = sefer kimliği eklenirse bildirime dokununca o seferin sohbeti açılır.

## 7. Kurulum adımları (şirket hesabı sahibi)

1. Firebase Console'da proje oluştur veya mevcut şirket projesini kullan.
2. Android uygulaması ekle: paket adı `com.dynamictransport.driver`. `google-services.json` dosyasını mobil geliştirmeye ver.
3. FCM HTTP v1 için service account oluştur; kimliği backend gizli ayarlarına koy.

## 8. Kabul kontrolleri

- Kokpitten gönderilen mesaj, uygulama kapalıyken birkaç saniye içinde bildirim olarak gelir; dokununca doğru sohbet açılır.
- Aynı seferden iki mesaj tek bildirimde görünür.
- Çıkış yapmış cihaza bildirim gitmez; şifre değişikliğinden sonra eski kayıt kullanılmaz.
- Push içeriğinde mesaj metni ve kişisel veri yoktur.
- Push gönderimi başarısız olsa da mesaj kokpitte ve uygulamada görünür.

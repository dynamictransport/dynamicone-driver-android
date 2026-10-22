# DynamicONE App

Dynamic Transport çalışanları için tek Android uygulaması (eski adı DynamicONE Driver). Her hesap sunucunun açtığı modülleri görür: şoförler sefer ekranlarını, ofis personeli NFC ile mesai kaydını.

## Amaç

**Şoför modülü (`driver`)** — şoförün günlük iş akışının tamamını tek ve basit bir uygulamada toplar:

- Kullanıcı adı / şifre ile giriş
- Mesai başlatma / bitirme
- Atanan seferleri görme
- Yeni seferi kabul / reddetme (red sebebi zorunlu)
- Sefer adımlarını tek büyük butonla ilerletme
- Mola başlatma / bitirme
- Sunucunun sefer bazlı evrak listesine göre CMR, gümrük evrağı (A1 = `Customs`), Interchange, konteyner/mühür fotoğrafı ve diğer evrakları ayrı ayrı yükleme; kontrol durumu (eksik, kontrol bekliyor, onaylandı, reddedildi) ve PDF seçimi; fotoğraflar uygulama içi belge tarayıcıyla (çerçeve, canlı kalite kontrolü, otomatik çekim, perspektif düzeltme; okunaksız fotoğraf kabul edilmez — bkz. `ASSUMPTIONS.md` S1–S9)
- Konteyner ve mühür fotoğrafı çekme
- Sorun / gecikme / arıza bildirme
- Yayınlanan aylık ücret bilgilerini (para birimi bazında ek ödeme/kesinti) ve imzalı personel evraklarını görme
- Ofisle genel ve sefer bazlı mesajlaşma
- Sunucunun sefer planındaki işlem seçenekleriyle (durak/konteyner bazlı) ilerleme, koordinatlı navigasyon
- İnternet yokken işlemlere devam etme ve daha sonra otomatik senkronize olma

**Ofis mesaisi modülü (`officeAttendance`)** — ofis personeli için:

- *MESAİ BAŞLAT* / *MESAİYİ BİTİR* tek butonu ve ofisteki giriş veya çıkış NFC etiketini okutma
- Kaydın önce telefona yazılması; internet yokken bekleyip bağlantı gelince otomatik gönderilmesi (gönderildi / bekliyor / gönderilemedi ayrı gösterilir)
- Aylık *Mesai kayıtlarım*: gün gün giriş/çıkış saatleri, gün durumu ve sunucunun hesapladığı çalışılan süre

Backend sözleşmesi: [docs/15_MOBILE_APP_API.md](docs/15_MOBILE_APP_API.md). 30.09.2026'dan beri varsayılan `app.mobileApi=true`: şoförler dahil her hesap Mobile API ile girer ve modüllerini sunucudan alır. Eski yol (Driver API, yalnızca şoför modülü) için `gradlew.bat assembleDebug -Papp.mobileApi=false`. Yalnızca 400 ve 409 kalıcı hatadır; 403/404/429/5xx durumunda kayıt aynı `commandId` ile tekrar gönderilir (docs/15 §5.3).

**Şoför günlük mesaisi (şoför modülünde, `app.driverShift`)** — takograf dinlenmesi ve günlük araç kontrolü:

- Ana ekranda takograf kartı: dinlenmenin bitişi ve kalan süre; çalışırken kalan günlük sürüş ve sonraki mola (Webfleet, gösterge amaçlı)
- Dinlenme bitince telefonda kurulu uyandırma alarmı (kilit ekranında tam ekran, 10 dk erteleme, o dinlenme için kapatma; ayarlardan sadece bildirim, 15/30 dk önce)
- *MESAİ BAŞLAT* → araçtaki NFC etiketini okutma → maddeler tek tek *Tamam* / *Sorun var* (sorunda fotoğraf) → basılı tutarak gönderme; internet yoksa telefonda bekler
- Uzun seferde günlük dinlenme için mesaiyi bitirme; dinlenmeden sonra yeni mesaide aynı sefere devam

Backend sözleşmesi ve Codex görevi: [docs/16_DRIVER_SHIFT_REST_AND_VEHICLE_CHECK.md](docs/16_DRIVER_SHIFT_REST_AND_VEHICLE_CHECK.md). Backend uçları yayına alınana kadar `app.driverShift=false` (varsayılan) ile bugünkü davranış sürer; denemek için `gradlew.bat assembleDebug -Papp.driverShift=true`.

**Dorse onayı (şoför modülünde)** — sefer kabul edildikten sonra, başlamadan önce:

- Ofisin aktif dorse listesinden seçim: plakalar büyük satırlarda, uzun listede plaka/şirket kodu araması
- Planlanan dorse yalnızca *Planlanan* etiketiyle işaretlenir; otomatik onaylanmaz, şoför açıkça seçer
- Onaylanana kadar sefer ekranında *Sefere başladım* yerine *DORSEYİ SEÇ* görünür; engellenen adım sunucuya gönderilmez
- Onaylanan dorse sefer detayında görünür; sefer başladıktan sonra seçim kilitlenir

Backend sözleşmesi: [docs/20_DRIVER_TRAILER_CONFIRMATION.md](docs/20_DRIVER_TRAILER_CONFIRMATION.md). Fotoğraf veya serbest plaka girişi yoktur; liste boşsa şoför ofise yönlendirilir. Onay çevrimdışı kuyruğa alınmaz: istek okunan listenin sürümünü taşır.

**Personel evrakları modülü (`hrDocuments`)** — şoför ve ofis personeli için:

- Ofisin gönderdiği evraklar (ör. RODO bilgilendirmesi) *Personel evraklarım* ekranında: imza bekleyenler son güne göre, altında imzalananlar; ana ekranda ve mesai ekranında "İmzanızı bekleyen evrak" kartı
- PDF uygulama içinde, dil seçerek (PL/TR/EN/AZ…) okunur; belgenin sonunda beyan kutusu ve parmakla imza → *İMZALA VE GÖNDER*
- İmza önce telefona yazılır, internet yoksa sonra aynı `commandId` ile gönderilir; sunucu onaylayana kadar "gönderilmeyi bekliyor"
- Yeni evrak, son gün yaklaşınca ve geçince bildirim; dokununca evrak açılır. Son gün geçse de imza atılabilir, hiçbir ekran kilitlenmez

Backend sözleşmesi ve Codex görevi: [docs/17_HR_DOCUMENT_SIGNING.md](docs/17_HR_DOCUMENT_SIGNING.md). Ekranlar sunucu `me.modules` içinde `hrDocuments` gönderince açılır (Mobile API, `app.mobileApi=true` gerekir); ayrı bir ayar yoktur.

## İlk platform

- Android
- Kotlin
- Jetpack Compose
- Offline-first yaklaşım
- DynamicONE REST API
- Azure tabanlı backend ile entegrasyon

## En önemli ürün ilkesi

Şoför uygulamayı yönetmez; uygulama şoföre sıradaki doğru işlemi gösterir.

Ana ekranda mümkün olduğunca:
1. Güncel durum
2. Aktif sefer
3. Tek ana aksiyon
4. Kritik uyarı
5. En fazla 4–6 büyük menü

görünmelidir.

## Repo kullanımı

İlk olarak `PROMPT_START_HERE.md` dosyasını Claude Code'a verin.

Claude Code projeyi oluştururken `CLAUDE.md` kurallarına ve `docs/` altındaki ürün sözleşmesine uymalıdır.

## Dosyalar

- `CLAUDE.md` — AI geliştirme kuralları
- `PROMPT_START_HERE.md` — ilk geliştirme promptu
- `docs/00_PRODUCT_VISION.md` — ürün vizyonu
- `docs/01_DRIVER_JOURNEY.md` — şoför akışları
- `docs/02_SCREEN_SPEC.md` — ekran spesifikasyonları
- `docs/03_STATE_MACHINE.md` — sefer durum makinesi
- `docs/04_OFFLINE_SYNC.md` — offline/senkronizasyon kuralları
- `docs/05_SECURITY.md` — güvenlik
- `docs/06_BACKEND_INTEGRATION.md` — DynamicONE entegrasyonu
- `docs/07_IMPLEMENTATION_PLAN.md` — fazlar
- `docs/08_ACCEPTANCE_TESTS.md` — kabul kriterleri
- `docs/09_DESIGN_SYSTEM.md` — mobil tasarım sistemi
- `docs/10_GIT_WORKFLOW.md` — Git çalışma biçimi
- `backend/openapi.yaml` — başlangıç API sözleşmesi
- `mockups/driver-app.html` — tarayıcıda açılabilen interaktif mockup

## Önerilen geliştirme sırası

### Faz 1 — Temel uygulama
Login → Ana ekran → Mesai → Sefer listesi → Sefer detayı → Sefer kabul → Sefer aksiyonları.

### Faz 2 — Evraklar
Kamera, belge/fotoğraf, yükleme kuyruğu, fotoğraf doğrulama, CMR/EIR/A1.

### Faz 3 — Offline
Room/local DB, pending action queue, WorkManager sync, retry, bağlantı göstergesi.

### Faz 4 — Bildirim ve operasyon
Push notification, planlamacı mesajları, gecikme/sorun bildirimi, kritik uyarılar.

### Faz 5 — Ücretler ve personel
Kazanç/ücret görünümü, şoför belgeleri, geçerlilik tarihleri, profil.

### Faz 6 — İleri seviye
NFC mesai, Webfleet doğrulama, GPS/ETA, rota sapması, QR/barcode, otomatik belge sınıflandırma.

---

## Geliştirme ortamı ve build

### Gereksinimler

- Android Studio 2026.1 veya üzeri (stable)
- Android SDK Platform 37 (Android 17); build-tools 36.0.0 (AGP varsayılanı)
- JDK: elle kurulum gerekmez. Gradle daemon ve testler **JDK 21** ile çalışır (`gradle/gradle-daemon-jvm.properties`); makinede yoksa Gradle indirir. Bytecode hedefi **Java 17**.
- Sistem PATH'indeki Java sürümü build'i etkilemez; wrapper çağrılırken `JAVA_HOME` en az JDK 17 olmalıdır (ör. Android Studio JBR).

| Bileşen | Sürüm |
|---|---|
| Gradle (wrapper) | 9.7.1 |
| Android Gradle Plugin | 9.4.0 (built-in Kotlin) |
| Kotlin | 2.3.21 |
| Compose BOM | 2026.09.00 |
| minSdk / targetSdk / compileSdk | 26 / 37 / 37 |

Tüm sürümler `gradle/libs.versions.toml` içindedir; yalnızca stable sürümler kullanılır.

### İlk kurulum

1. Repository'yi Android Studio ile açın; Gradle sync'i bekleyin.
2. SDK yolu için `local.properties` oluşturun (Git'e eklenmez):

   ```properties
   sdk.dir=C\:/Users/<kullanici>/AppData/Local/Android/Sdk
   ```

### Komutlar

```bash
./gradlew assembleDebug        # debug APK: app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # unit + Robolectric Compose UI + MockWebServer API testleri
./gradlew lintDebug            # Android lint (hata varsa build kırılır)
```

Windows'ta `gradlew.bat` kullanın. CI (`.github/workflows/android-ci.yml`) aynı üç komutu JDK 21 ile çalıştırır.

### API ortamları

| Build | Backend | Adres |
|---|---|---|
| Debug | development | `driver.devApiBaseUrl` (varsayılan `https://localhost:7167/api/driver/v1/`) |
| Release | production | `driver.prodApiBaseUrl` (varsayılan `https://dynamicone.ai/api/driver/v1/`) |

Build ayarları (`gradle.properties`, `~/.gradle/gradle.properties` veya `-P`):

| Property | Varsayılan | Açıklama |
|---|---|---|
| `driver.devApiBaseUrl` | `https://localhost:7167/api/driver/v1/` | Debug build'in API adresi. Yalnızca HTTPS ve `/api/driver/v1/` ile biten adres kabul edilir |
| `driver.prodApiBaseUrl` | `https://dynamicone.ai/api/driver/v1/` | Release build'in API adresi. Driver API uçları 15.09.2026'da üretime yayınlandı (backend TH-2026-0451) |
| `driver.demoMode` | `false` | `true` → debug build uygulama içi demo backend ile çalışır. Release build'de etkisizdir |

Uygulamada SQL bağlantısı, storage anahtarı, JWT imza anahtarı veya başka backend sırrı yoktur; yalnızca HTTPS REST API kullanılır. Kişisel değerler Git'e commit edilmez.

### Development backend ile çalıştırma

1. **Backend'de Driver API'yi açın** (varsayılan olarak kapalıdır ve her istek `404 api_disabled` döner): backend repository'sinde `./tools/Initialize-DriverApi.ps1` çalıştırın, sonra DynamicONE.Web'i Visual Studio'dan yeniden başlatın. `https://localhost:7167/api/driver/v1/health` 200 dönmelidir.
2. **Cihazdan PC'ye port yönlendirin:** `adb reverse tcp:7167 tcp:7167` (emülatör ve USB cihaz için; her `adb` yeniden başlatmasından sonra tekrarlayın).
3. **Geliştirme sertifikasını debug build'e ekleyin:** `powershell -ExecutionPolicy Bypass -File tools\Export-DevHttpsCertificate.ps1`. Script bu PC'nin ASP.NET Core geliştirme sertifikasının yalnızca açık kısmını (özel anahtar yok) `app/dev-https/res/raw/` altına yazar; dosya Git'e girmez. Dosya varsa yalnızca debug build bu sertifikaya güvenir (`app/dev-https/res/xml/network_security_config.xml` → `debug-overrides`). Telefona sertifika yüklemek gerekmez: ASP.NET geliştirme sertifikası CA olmadığı için Android ayarlarından CA olarak yüklenemez. Release build yalnızca sistem CA'larına güvenir; global SSL bypass yoktur.
4. **Kurun ve açın:** `gradlew.bat installDebug`.
5. **Giriş:** Identity kullanıcı adı (ör. `ad.soyad`, personel no değil) ve şifre. Hesabın `driver_access` yetkisi olmalı ve aktif bir sürücüye bağlı olmalıdır; MFA açık hesaplarda 6 haneli kod istenir.

### Push bildirimleri (Firebase)

- Firebase projesi `dynamicone-driver`, Android uygulaması `com.dynamictransport.dynamicone` (applicationId 17.09.2026'da `com.dynamictransport.driver`'dan değişti: aynı Firebase projesine bu paket adıyla Android uygulaması eklenmeli). Konsoldan indirilen `google-services.json` dosyasını `app/google-services.json` olarak koyun; dosya Git'e girmez. Dosya yoksa veya bu paket adını içermiyorsa uygulama push olmadan derlenir ve çalışır (`BuildConfig.PUSH_CONFIGURED = false`).
- FCM kaydı yalnızca gerçek backend'e giriş yapıldıktan sonra başlar; demo modunda push yoktur. Android 13+ cihazlarda girişten sonra bildirim izni istenir.
- Cihaz kaydı (`POST`/`DELETE me/devices`) ve FCM mesaj push'u backend'de yayında (TH-2026-0444). Uygulama girişten sonra cihazı kaydeder, çıkıştan önce kaydı siler. Üretimde gönderimin etkinleştirilmesi backend'de ayrı adımdır. Ayrıntı: [docs/13_PUSH_NOTIFICATIONS.md](docs/13_PUSH_NOTIFICATIONS.md). Firebase service account anahtarı yalnızca backend'in gizli ayarlarına konur, bu repoya girmez.

### Demo modu (backend olmadan)

`gradlew.bat installDebug -Pdriver.demoMode=true` ile uygulama içi **fake backend** kullanılır; ekranlarda **DEMO** rozeti görünür. Demo, gerçek API hatalarında asla devreye girmez ve release build'de kullanılamaz.

- Giriş: herhangi bir kullanıcı adı, şifre `demo`
- Hazır veri: 1 yeni atanmış sefer, 1 kabul edilmiş yarınki sefer, 2 tamamlanmış sefer
- Profil → Demo ayarları: **İnternet yokmuş gibi davran** (offline akışı denemek için), **Demo verilerini sıfırla**
- Fake backend offline kuyruk, state machine, illegal geçişte 409 ve `clientActionId` idempotency kurallarını uygular

### Mimari özeti

```
UI (Compose) → ViewModel → UseCase (gerektiğinde) → Repository (domain arayüzü)
   gerçek API : Real*Repository → Retrofit DriverApi → DynamicONE Driver API v1 (bearer, tek refresh, problem+json)
   demo       : OfflineFirst*Repository → Room + PendingAction kuyruğu → WorkManager → FakeDriverBackend
```

- `core/common/BackendMode` — debug/release ve `driver.demoMode`'a göre gerçek/demo seçimi; DI (`RepositoryModule`) tek yerde seçer.
- `core/network` — OpenAPI DTO'ları, `DriverHttpClients` (header, bearer, serileştirilmiş refresh, otomatik tekrar yok), problem+json eşlemesi.
- `data/real` — gerçek API repository'leri, sefer adımı eşlemesi (`DriverApiMapping`), upload hazırlığı, `ApiActivityTracker` (Retry-After, çevrimiçi durumu, son senkronizasyon).
- `domain/statemachine/TripStateMachine` — tek merkezî geçiş tablosu; `NextActionResolver` ana aksiyonu sunucunun izin verdiği adımlar ∩ local kurallardan üretir.
- `data/sync/SyncEngine` — demo modunda FIFO gönderim, idempotent tekrar, 409 → görünür hata.
- `feature/*` — ekran başına ViewModel + stateless Compose ekranı.
- `data/real/RealTripExecutionRepository` + `PendingCommandStore` — sunucu planındaki işlemler; `commandId` gönderimden önce Room `pending_commands` tablosuna yazılır, sonucu belirsiz komut aynı id ve payload ile tekrar gönderilir.
- `data/real/RealDocumentRepository`, `RealPersonnelRepositories`, `RealMessageRepository` — evrak slotları, ücret ve imzalı evrak, mesajlar.
- `app/navigation/AppShell` — hesabın modüllerinden alt menü (en fazla 5 öğe) ve ilk ekran; `data/real/AccountRemote` girişi Driver API veya Mobile API (`app.mobileApi`) üzerinden yapar.
- `data/real/RealAttendanceRepository` + `core/nfc` — NFC etiketi okuma (reader mode), `attendance_punches` tablosu, `AttendanceSyncWorker` ile idempotent otomatik gönderim; kural: `domain/rules/AttendanceRules`.
- `data/real/RealHrDocumentRepository` + `core/pdf` — personel evrakları: `hr_signatures` (bekleyen imzalar) ve `hr_document_cache` tabloları, SHA-256 ile doğrulanan PDF deposu (`HrDocumentFileStore`), `HrDocumentSyncWorker`, uygulama içi PDF (`PdfRenderer`); kural: `domain/rules/HrDocumentRules`.
- Kararlar ve API boşlukları: `ASSUMPTIONS.md` (R1–R15, O1–O16, M1–M12, D1–D15), `docs/12_API_CONTRACT_GAPS.md`, `docs/15_MOBILE_APP_API.md`, `docs/17_HR_DOCUMENT_SIGNING.md`.

### Logo ve uygulama ikonu

Kaynak: `branding/dynamicone-mark.png` (şeffaf DynamicONE işareti, 572×384). Uygulama ikonu (uyarlanabilir ön katman ve Android 13 tek renkli katmanı), açılış ekranı ikonu ve giriş ekranındaki logo bu dosyadan üretilir; logo değişince dosyayı değiştirip şunu çalıştırın:

```powershell
powershell -ExecutionPolicy Bypass -File tools\Generate-AppIcons.ps1
```

### Sorun giderme

- `java.io.IOException: Unable to establish loopback connection`: JDK 16+ Windows'ta NIO pipe için geçici dizinde AF_UNIX soket açar. Geçici dizine erişim kısıtlıysa kısa ve yazılabilir bir dizin verin: `set JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\Temp` (dizin mevcut olmalı).

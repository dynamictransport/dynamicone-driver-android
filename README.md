# DynamicONE Driver

Dynamic Transport şoförleri için Android saha uygulaması.

## Amaç

DynamicONE Driver, şoförün günlük iş akışının tamamını tek ve basit bir uygulamada toplar:

- Kullanıcı adı / şifre ile giriş
- Mesai başlatma / bitirme
- Atanan seferleri görme
- Yeni seferi kabul / reddetme (red sebebi zorunlu)
- Sefer adımlarını tek büyük butonla ilerletme
- Mola başlatma / bitirme
- CMR, Interchange/EIR, A1, POD ve diğer evrakları yükleme
- Konteyner ve mühür fotoğrafı çekme
- Sorun / gecikme / arıza bildirme
- Ücret ve hakedişleri görme
- Planlamacıdan gelen bildirimleri alma
- İnternet yokken işlemlere devam etme ve daha sonra otomatik senkronize olma

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

# CLAUDE.md — DynamicONE App Development Contract

Bu dosya proje için bağlayıcı geliştirme kurallarını tanımlar.

## 1. Proje amacı

DynamicONE App, Dynamic Transport çalışanlarının tek mobil uygulamasıdır (eski adı DynamicONE Driver).
Her hesap sunucunun verdiği modülleri görür (`me.modules`, `docs/15_MOBILE_APP_API.md`):

- `driver`: profesyonel kamyon şoförlerinin günlük operasyonu. Kullanıcıların önemli kısmı uygulamayı araç içinde,
  hızlı ve stresli koşullarda kullanır.
- `officeAttendance`: ofis personelinin NFC etiketiyle mesai girişi/çıkışı ve kendi mesai kayıtları.

Yeni ihtiyaçlar yeni bir modül olarak eklenir: rol kararı uygulamada verilmez, menü modüllerden kurulur
(`app/navigation/AppShell.kt`).

Bu nedenle tüm modüllerde:
- mümkün olan en az adım,
- mümkün olan en az yazı girişi,
- büyük dokunma hedefleri,
- güçlü görsel durum göstergeleri,
- offline çalışma,
- yanlış dokunmayı önleme,
- bir ekranda tek baskın aksiyon

zorunludur.

## 2. Teknoloji

Yeni Android projesini Android Studio'nun güncel stable sürümüyle oluştur.

Tercih:
- Kotlin
- Jetpack Compose + Material 3
- Single Activity
- ViewModel
- Coroutines + Flow
- Repository pattern
- Room
- WorkManager
- Retrofit/OkHttp
- Kotlin Serialization veya Moshi (birini seç, projede tek standarda bağlı kal)
- Hilt/Dagger veya güncel resmi DI yaklaşımı
- DataStore
- Android Keystore
- Firebase Cloud Messaging (push gerekince)

Stable bağımlılık kullan. Alpha/Beta bağımlılıkları açık gerekçe olmadan kullanma.

## 3. Mimari

Katmanlar:

UI -> ViewModel -> UseCase (gerektiğinde) -> Repository -> Local/Remote Data Source

Kurallar:
- UI doğrudan Retrofit çağrısı yapamaz.
- UI doğrudan Room DAO çağrısı yapamaz.
- Network modelleri domain modelleri olarak kullanılmamalı.
- Navigation route'ları typed/centralized olmalı.
- Tekrarlanan business rule ViewModel içine dağılmamalı.

Önerilen paket yapısı:

com.dynamictransport.dynamicone
  app/
  core/
    common/
    design/
    network/
    database/
    sync/
    security/
    nfc/
  feature/
    auth/
    home/
    shift/
    trips/
    tripdetail/
    documents/
    earnings/
    messages/
    profile/
    incident/
    attendance/
  domain/
  data/

## 4. Offline-first zorunludur

Kritik user action önce local olarak kaydedilir.

Örnek:
- sefer kabul
- "başladım"
- varış
- mola başlangıç/bitiş
- fotoğraf metadata
- evrak metadata
- sorun bildirimi

Her action bir `clientActionId` UUID taşır.

Sunucu aynı `clientActionId` için idempotent davranmalıdır.

UI durumları:
- Synced
- Pending sync
- Sync failed

Şoföre başarısız upload'ı başarılı gibi gösterme.

## 5. Sefer durumları

Durum makinesini `docs/03_STATE_MACHINE.md` üzerinden uygula.

Backend'in illegal transition göndermesine de UI tarafında karşı koruma olmalı.

Bir sefer ekranında ana aksiyon `nextAllowedAction` üzerinden belirlenmeli.
Hard-code edilmiş dağınık if/else zincirleri oluşturma.

## 6. Fotoğraf/evrak

Fotoğraf çekilirken:
- document type
- trip id
- capturedAt
- clientActionId
- optional GPS
- optional container number
- optional seal number

metadata kaydedilir.

Fotoğraf sıkıştırma:
- okunabilirliği bozmayacak şekilde
- orijinal belge metnini okunamaz hale getirmeyecek şekilde
- metadata ve medya ayrı yönetilmeli

Upload:
1. metadata local queue
2. server upload session / signed URL
3. media upload
4. finalize
5. local state synced

## 7. UX kuralları

- Ana buton minimum yaklaşık 56dp yükseklik, kritik actionlarda daha büyük.
- Küçük metin linklerini ana operasyon için kullanma.
- Aynı ekranda iki eşit önemde primary CTA verme.
- Kırmızı yalnızca hata/tehlike/iptal gibi anlamlarda kullanılmalı. İstisna (kullanıcı kararı, 14.09.2026): Ücretler ekranında kesintiler kırmızı ve "−" işaretiyle, kazançlar yeşil ve "+" işaretiyle gösterilir.
- Durumu sadece renkle anlatma; icon + text de kullan.
- Şoförün sefer sırasında uzun metin yazmasını isteme.
- Sebep gerekiyorsa preset seçenek + "Diğer" kullan.
- Kritik onaylarda yanlış dokunmayı önle; her adımda modal kullanma.
- Menü en fazla 6 ana seçenek.
- Alt navigasyon gerekiyorsa 4 veya 5 hedefi geçme.

## 8. Güvenlik

- Şifreyi cihazda saklama.
- Access token güvenli saklanmalı.
- Refresh token Android Keystore ile korunan depolamada tutulmalı.
- Log'a token, şifre, belge resmi, kişisel veri yazma.
- Release build'de debug logging kapalı.
- API sadece HTTPS.
- Certificate pinning ancak operasyonel bakım planı varsa uygulanmalı.
- Oturum süresi dolduğunda pending upload verisi silinmemeli.

## 9. Test zorunluluğu

Her feature için:
- ViewModel unit test
- State transition test
- Repository test
- kritik Compose UI test

Özellikle:
- duplicate tap
- offline action
- app restart
- upload retry
- token refresh
- aynı seferin birden çok push bildirimi
- saat dilimi
- düşük depolama alanı

test edilmelidir.

## 10. Kod değiştirme biçimi

Her görevde:
1. Önce ilgili dosyaları oku.
2. Mevcut mimariyi bozma.
3. En küçük mantıklı değişikliği yap.
4. Test yaz/güncelle.
5. Build/test çalıştır.
6. Sonuçta değişen dosyaları ve test sonucunu raporla.

Build'i bilerek kırık bırakma.

## 11. Kaynak doğruluğu

Backend endpoint uydurma.
`backend/openapi.yaml` sözleşmesini kaynak kabul et.
Eksik endpoint varsa önce TODO/contract öner ve açıkça belirt.

## 12. Dil

UI metinleri string resource üzerinden gelmeli.
Başlangıç:
- English
- Polish
- Turkish

Yeni dil eklenmesini kolaylaştır.

## 13. AI için yasaklar

- Gerçek credential commit etme.
- `.env`, secret, token, password commit etme.
- Büyük refactor ile feature geliştirmeyi aynı commit'te karıştırma.
- Kullanıcıdan istenmeyen yeni business rule icat etme.
- Mock veriyi production API gibi göstermeme.

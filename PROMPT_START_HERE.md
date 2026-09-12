# Claude Code — İlk Ana Prompt

Aşağıdaki görevi bu repository içindeki tüm `.md` dosyalarını okuyarak yap.

Sen DynamicONE Driver Android uygulamasının principal Android engineer'ısın.

## Hedef

Dynamic Transport şoförleri için production-grade Android uygulamasının ilk çalışan temelini oluştur.

Bu repository'de:
- `CLAUDE.md`
- `docs/*`
- `backend/openapi.yaml`
- `mockups/driver-app.html`

ürün ve teknik sözleşme olarak kabul edilmelidir.

## İlk sprintte yapılacaklar

1. Güncel stable Android Studio/AGP ile Android projesini initialize et.
2. Kotlin + Jetpack Compose + Material 3 kullan.
3. Clean-ish feature-based architecture kur.
4. Dependency injection kur.
5. Network katmanı kur.
6. Room local database kur.
7. DataStore kur.
8. WorkManager tabanlı sync altyapısını kur.
9. Mock backend ile aşağıdaki ekranları çalışır hale getir:
   - Splash/session check
   - Login
   - Home
   - Shift start/end
   - Trips
   - Trip detail
   - Accept trip
   - Start trip / next action
   - Break start/end
   - Documents
   - Earnings placeholder
10. `mockups/driver-app.html` tasarım yönünü takip et, ancak native Android UI üret.
11. Tüm UI metinlerini resources içine al.
12. English / Polish / Turkish locale altyapısını kur.
13. Dark mode çalışsın ancak ilk odak light mode.
14. Demo için fake repository sağla.
15. Fake repository ile uygulama emulator üzerinde backend olmadan gezilebilir olsun.
16. Unit test ve temel Compose UI test ekle.
17. README'ye kurulum/build talimatlarını ekle.

## Kritik UX

Ana ekranda:
- üstte şoför ve mesai durumu,
- hemen altında aktif sefer,
- tek büyük "sıradaki aksiyon" butonu,
- en fazla 4 ana hızlı menü

olmalı.

Atanmış yeni sefer varsa kullanıcı uygulamayı açtığında bu durum açıkça görünmeli:
`Yeni sefer atandı` → `Seferi Gör` → `Kabul Et`

Kabul edildikten sonra ana aksiyon state machine'e göre değişmeli:
`Mesaiyi Başlat`
`Seferi Başlat`
`Tesise Vardım`
`Yüklemeyi Tamamladım`
`Molaya Çık`
`Molayı Bitir`
`Teslimata Vardım`
`Teslimatı Tamamladım`
`Seferi Bitir`

## Evrak

İlk sprintte evrak ekranını ve local modelini oluştur.
Gerçek upload entegrasyonu ikinci sprintte yapılabilir.

Document types başlangıçta:
- CMR
- Interchange / EIR
- A1
- POD
- Container photo
- Seal photo
- Other

Camera action açık ve belirgin olmalı.

## Çalışma biçimi

Önce repository'yi analiz et ve kısa implementation plan çıkar.
Sonra doğrudan uygula.
Her küçük detay için kullanıcıya soru sorma.
Belirsiz business rule varsa `ASSUMPTIONS.md` içine açık varsayım yaz ve güvenli/default davranış kullan.

Her aşamada build/test çalıştır.
Kırık kod bırakma.

## Sprint sonucu

Sonuçta şunları raporla:
- Oluşturulan mimari
- Ekranlar
- Fake data akışı
- Test sayısı / sonucu
- Bilinen eksikler
- Bir sonraki sprint için önerilen sıra

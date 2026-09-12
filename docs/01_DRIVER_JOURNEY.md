# Driver Journey

## A. Gün başlangıcı

1. Şoför login olur.
2. Uygulama bugünkü mesai durumunu kontrol eder.
3. Mesai başlamadıysa ana primary CTA:
   **MESAİYİ BAŞLAT**
4. Gerekirse:
   - araç seçimi/doğrulaması
   - trailer seçimi/doğrulaması
   - kısa checklist
5. Mesai başlar.
6. Atanmış sefer varsa ana ekrana çıkar.

## B. Yeni sefer

Push:
**Yeni sefer atandı — #TRP-2026-001245**

Şoför:
1. Seferi görür.
2. Özet:
   - nereden
   - nereye
   - planlanan saat
   - container
   - özel talimat
3. `KABUL ET`
4. Reddetme gerekiyorsa:
   - araç problemi
   - yasal süre
   - sağlık
   - yanlış atama
   - diğer
   ve kısa açıklama.

## C. Sefer yürütme

Örnek ana akış:

Assigned
→ Accepted
→ Started
→ Arrived Pickup/Terminal
→ Pickup/Loading Completed
→ En Route
→ Arrived Delivery
→ Delivery Completed
→ Finished

Her aşamada ekranda yalnızca uygun aksiyonlar gösterilir.

## D. Mola

Aktif seferde secondary action:
`MOLA`

Basılınca:
- `Molaya başladım`
- server/local timestamp
- ana CTA `Molayı bitir`

Mola aktifken yanlışlıkla "teslimatı tamamladım" gibi aksiyonlar gösterilmez.

## E. Evrak

Sefer ekranında `EVRAKLAR`.

Belge kartları:
- CMR
- Interchange / EIR
- A1
- POD
- Container Photo
- Seal Photo
- Other

Durum:
- Eksik
- Bekliyor (offline/pending)
- Yüklendi
- Hatalı / tekrar çek

Fotoğraf çekme mümkünse uygulama içinden yapılır.
Gallery seçimi secondary seçenek olabilir.

## F. Sorun bildirimi

Sefer sırasında görünür ama primary olmayan bir `SORUN BİLDİR` aksiyonu.

Kategori:
- Gecikme
- Trafik
- Terminal kuyruğu
- Araç arızası
- Kaza
- Evrak sorunu
- Container/seal sorunu
- Müşteri sorunu
- Diğer

Kullanıcı mümkün olduğunca seçenek seçer, yazı yazmak zorunda kalmaz.
Gerekirse fotoğraf ekler.

## G. Mesai sonu

Aktif sefer yoksa:
`MESAİYİ BİTİR`

Özet:
- çalışma süresi
- sefer sayısı
- eksik upload var mı?
- pending sync var mı?

Pending sync varsa şoföre açıkça gösterilir.

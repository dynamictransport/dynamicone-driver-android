# Screen Specification

## 1. Splash

Amaç:
- session kontrolü
- local DB init
- kısa sync kickoff

Uzun splash yasak.

## 2. Login

Alanlar:
- Kullanıcı adı
- Şifre
- Şifreyi göster
- Giriş yap

Secondary:
- Destek
- Dil seçimi

Hata:
- basit insan dili
- teknik stack trace yok

## 3. Home

### Üst alan
- Şoför adı
- Online/offline
- Mesai: başladı / başlamadı
- Son sync

### Aktif sefer kartı
- Trip no
- Route
- Saat
- Container
- Durum

### PRIMARY ACTION
Ekranın en önemli ve en büyük butonu.
State'e göre text değişir.

Örnek:
`MESAİYİ BAŞLAT`
`SEFERİ KABUL ET`
`SEFERİ BAŞLAT`
`TESİSE VARDIM`
`YÜKLEME TAMAMLANDI`
`TESLİMATA VARDIM`
`TESLİMAT TAMAMLANDI`

### Quick actions
Maksimum 4:
- Seferler
- Evraklar
- Ücretler
- Sorun Bildir

Bottom navigation önerisi:
- Ana Sayfa
- Seferler
- Evraklar
- Profil

## 4. Trips

Sekmeler:
- Aktif
- Yaklaşan
- Tamamlanan

Liste item:
- route
- date/time
- trip no
- status
- container no (varsa)

## 5. Trip Detail

Üst:
- Trip no
- status badge

Ana kart:
- pickup
- delivery
- plan time
- container
- trailer/vehicle
- customer instruction

Timeline:
- Atandı
- Kabul edildi
- Başladı
- Pickup/terminal
- Teslimat
- Tamamlandı

Primary CTA state machine'den gelir.

Secondary:
- Navigasyon
- Planlamacıyı ara
- Evraklar
- Sorun bildir

## 6. Documents

Her belge kartında:
- icon
- belge adı
- status
- photo/file thumbnail
- tekrar çek

Belge ekleme:
`FOTOĞRAF ÇEK` primary
`GALERİDEN SEÇ` secondary

Container/seal photo için kamera tercih edilir.

## 7. Shift

- Mesai başlangıç saati
- Süre
- Aktif/kapalı
- araç
- trailer
- checklist status

Primary:
- Başlat / Bitir

## 8. Earnings

Şoförün muhasebe ekranı değil; sade hakediş özeti.

- Bu ay
- Önceki ay
- Gün/çalışma sayısı
- Bonuslar
- Kesintiler (varsa, açıklamalı)
- Toplam

Detaylar backend yetkisine göre gelir.

## 9. Notifications

- yeni sefer
- sefer değişikliği
- planner mesajı
- belge reddi
- yaklaşan personel belgesi bitişi

## 10. Profile

- ad soyad
- personel no
- telefon
- dil
- şirket bilgisi
- driver document validity
- logout

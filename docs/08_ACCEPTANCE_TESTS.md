# Acceptance Tests

## Login

- doğru kullanıcı giriş yapar
- hatalı şifre insan diliyle hata verir
- şifre cihazda plain text kalmaz
- token refresh çalışır

## New Trip

Given şoföre yeni trip atanmış
When app açılır
Then home screen yeni seferi görünür şekilde gösterir
And primary CTA seferi gör/kabul akışına götürür

## Accept Offline

Given telefon offline
When driver ACCEPT yapar
Then UI işlemi pending olarak kaydeder
And action Room queue'ya yazılır
When internet gelir
Then action server'a bir kez işlenir

## Duplicate Tap

When driver primary CTA'ya iki kez hızlı basar
Then iki ayrı business action oluşmaz

## Break

Given active trip
When break starts
Then break state visible
And END_BREAK primary olur

## Documents

- doğru trip'e bağlanır
- doğru document type
- photo capture
- app restart sonrası pending upload kaybolmaz
- failed upload retry edilir
- uploaded belge thumbnail/status gösterir

## Trip Finish

Required documents business rule ile tanımlanmışsa:
- eksik zorunlu evrak varsa uyarı
- backend izin vermiyorsa finish kapalı/blocked
- izin veriyorsa finish olur

## Localization

- EN
- PL
- TR

metin taşması temel ekranlarda test edilir.

## Accessibility

- touch target
- font scaling
- contrast
- renk dışında text/icon ile status

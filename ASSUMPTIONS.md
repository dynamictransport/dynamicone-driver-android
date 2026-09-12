# Assumptions

Bu başlangıç paketinde açıkça varsayılan konular:

1. İlk platform Android.
2. Giriş kullanıcı adı + şifre.
3. DynamicONE backend REST/JSON API sağlayacak.
4. Mobil uygulama SQL'e doğrudan bağlanmayacak.
5. Sefer state machine backend ile ortak sözleşme olacak.
6. Başlangıç dilleri EN/PL/TR; gerekirse genişletilecek.
7. Ücretler backend tarafından hesaplanacak, mobil sadece gösterecek.
8. Background GPS ilk sprintte zorunlu değil.
9. NFC/Webfleet ilk sprintte zorunlu değil; mimari sonradan eklenebilir.
10. Fotoğraf ve evraklar private storage'a yüklenecek.

Business tarafından kesinleşmesi gerekenler:
- Sefer reddetme şoföre açık mı?
- Hangi belgeler seferi bitirmek için zorunlu?
- Container/seal fotoğrafı hangi adımda zorunlu?
- Ücret detayında şoföre hangi alanlar gösterilecek?
- Mesai başlangıcında araç/trailer seçimi şoför mü yapacak?
- GPS/background tracking kapsamı ne olacak?

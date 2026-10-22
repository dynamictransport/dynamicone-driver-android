# 21 — Personel fotoğrafı (backend sözleşme önerisi)

Durum: **canlıda, 30.09.2026** (backend PR #303, b5adf6c3; sözleşme değişmeden uygulandı). Uygulama bu sözleşmeyle
çalışıyor; üretimde İK fotoğrafı olan bir ofis hesabıyla emülatörde doğrulandı (512 px, EXIF yok). Alan gelmezse bugünkü kişi
ikonu kalır. Codex görevi §7'dedir.

Kullanıcı isteği (30.09.2026): personelin fotoğrafı uygulamada görünsün. Kullanıcı kararları:

- Kaynak **yalnızca İK personel fotoğrafıdır** (`EmployeeProfilePhoto`, İK → Personel kartında yüklenen). Hesap profil
  fotoğrafı (`ApplicationUser.ProfilePhotoStorageKey`) ve şoför profili fotoğrafı (`DriverProfilePhoto`) kullanılmaz.
- Uygulama fotoğrafı **yalnızca gösterir**; uygulamadan yükleme/değiştirme yoktur.

## 1. Kaynak ve sınırlar

- Fotoğraf, token'dan çözülen kullanıcının bağlı `EmployeeProfile`'ının `IsCurrent` fotoğraflarından
  `UploadedAtUtc` azalan ilkidir — web'deki `WorkforceController.Photo` ile aynı seçim.
- Yalnızca kullanıcının **kendi** fotoğrafı döner. Başka personelin fotoğrafı için mobil uç yoktur.
- Personel kaydı olmayan hesap (ör. yalnızca kullanıcı hesabı) fotoğrafsızdır.
- Veritabanı değişikliği yoktur (`EmployeeProfilePhotos.Sha256` zaten saklanıyor).

## 2. `GET /api/mobile/v1/me` eki

Yanıta nullable `photoSha256` alanı eklenir:

```json
{
  "userId": "7b2f7a0e-2d7e-4c1a-9a51-3a1f0f2c9d10",
  "employeeId": "c3a1d5f2-8e44-4b0e-a0b8-6f2b1c9e7a55",
  "driverId": null,
  "displayName": "Ayşe Yılmaz",
  "preferredLanguageCode": "tr",
  "modules": ["officeAttendance"],
  "assignedVehicle": null,
  "photoSha256": "3F1A…C9"
}
```

| Alan | Kural |
| --- | --- |
| `photoSha256` | Seçilen fotoğrafın `EmployeeProfilePhoto.Sha256` değeri (büyük harf hex, 64 karakter). Fotoğraf yoksa veya personel kaydı yoksa `null`. |

Uygulama değeri opak bir sürüm olarak kullanır: yalnızca değer değiştiğinde fotoğrafı yeniden indirir.

## 3. `GET /api/mobile/v1/me/photo`

Mevcut `mobile-api.access` politikası, `api_disabled` ara katmanı, hız sınırı ve problem yanıtları aynen geçerlidir.
Parametre ve gövde yoktur.

- `200`: fotoğrafın baytları. `Content-Type` saklanan türdür (`image/jpeg`, `image/png` veya `image/webp`).
  `ETag: "<photoSha256>"`, `Cache-Control: private, no-store` (uygulama kendi önbelleğini tutar).
- `404 photo_not_found`: personel kaydı yok, güncel fotoğraf yok veya depodaki dosya okunamıyor.
- Diğer hatalar diğer Mobile API uçlarıyla aynı (`401 unauthorized`, `404 api_disabled`, `429 rate_limit_exceeded`).

Yeniden boyutlandırma yapılmaz; uygulama fotoğrafı telefonda küçültür.

## 4. Uygulama davranışı (bilgi için)

- `me` her okunduğunda `photoSha256` karşılaştırılır; farklıysa `me/photo` bir kez indirilir. Aynıysa istek yoktur.
- İndirilen baytların SHA-256'sı `photoSha256` ile aynı değilse fotoğraf kullanılmaz (önceki kalır).
- İndirilen fotoğraf telefonda küçültülür (uzun kenar ≤ 512 px), EXIF verisi atılarak uygulamanın özel alanına
  yazılır; internet yokken de gösterilir.
- `photoSha256` `null` veya `404 photo_not_found` → telefondaki fotoğraf silinir, kişi ikonu gösterilir.
- Ağ hatasında önceki fotoğraf kalır; bir sonraki `me` okumasında yeniden denenir.
- Çıkış yapınca veya hesap değişince fotoğraf telefondan silinir.
- Fotoğraf Profil ekranının başında gösterilir. Log'a yazılmaz.

## 5. Güvenlik ve gizlilik

- Fotoğraf kişisel veridir: yalnızca sahibine döner, log'a ve hata raporuna yazılmaz.
- Salt okumadır; mevcut denetim kurallarından fazlası gerekmez.

## 6. Kabul testleri (backend)

1. Personel kaydı ve güncel fotoğrafı olan kullanıcı → `me.photoSha256` = en yeni güncel fotoğrafın `Sha256`'sı;
   `me/photo` `200`, doğru `Content-Type`, `ETag` ve baytlar.
2. Birden çok `IsCurrent` fotoğraf → en yeni `UploadedAtUtc` seçilir; `me` ve `me/photo` aynı fotoğrafı gösterir.
3. Fotoğrafı olmayan personel ve personel kaydı olmayan kullanıcı → `photoSha256: null`, `me/photo` `404 photo_not_found`.
4. Depoda dosya yok → `me/photo` `404 photo_not_found` (500 değil).
5. Başka kullanıcının fotoğrafı hiçbir yoldan dönmez.
6. Hesap profil fotoğrafı ve şoför profili fotoğrafı kullanılmaz (yalnızca onları olan kullanıcı → `null`).
7. `api_disabled` iken `404 api_disabled`.

## 7. Codex görevi (yapıştırılabilir)

> DynamicONE backend'inin Mobile API v1'ine personelin kendi İK fotoğrafını ekle. Sözleşme: Android deposundaki
> `docs/21_EMPLOYEE_PHOTO.md` (§1–§6). `GET /api/mobile/v1/me` yanıtına nullable `photoSha256` ekle ve
> `GET /api/mobile/v1/me/photo` ucunu ekle. Fotoğraf seçimi `WorkforceController.Photo` ile aynı olsun: kullanıcının
> bağlı `EmployeeProfile`'ının `IsCurrent` fotoğraflarından `UploadedAtUtc` azalan ilki; dosyayı `IEmployeeFileStore`
> ile oku. Yalnızca İK fotoğrafı kullanılır; `ApplicationUser.ProfilePhotoStorageKey` ve `DriverProfilePhoto`
> kullanılmaz. Fotoğraf yoksa veya dosya okunamıyorsa `404` ve problem `code` = `photo_not_found`. `200` yanıtı saklanan
> `Content-Type`, `ETag: "<Sha256>"` ve `Cache-Control: private, no-store` taşır; yeniden boyutlandırma yapma. Migration
> yok. Mevcut `mobile-api.access` yetkisi, hız sınırı, api_disabled ve problem+json davranışını koru; fotoğrafı log'a
> yazma. §6'daki testleri ekle, OpenAPI'de görünsün. Backend Mobile API dokümanına kısa bir bölüm ekle. Alan ve kod
> adlarını değiştirme; bir farklılık gerekiyorsa açıkça yaz.

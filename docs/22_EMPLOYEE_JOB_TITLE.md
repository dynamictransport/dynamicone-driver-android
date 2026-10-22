# 22 — Personel unvanı (backend sözleşme önerisi)

Durum: **backend'de var (30.09.2026)**: `MobileApiAccessService` — `JobTitleDefinition.Name`, yoksa `JobTitle`, boşsa null. Önceki durum: öneri. Uygulama tarafı hazır: alan gelmezse ekranlar bugünkü gibi kalır
(ad soyadın altında boş satır oluşmaz). Codex görevi §6'dadır.

Kullanıcı isteği (30.09.2026): Mesai ekranında ad soyadın altında kişinin unvanı yazsın.

## 1. Kaynak ve sınırlar

- Unvan, token'dan çözülen kullanıcının bağlı `EmployeeProfile` kaydındaki **İK görev/unvan** alanıdır (web'de İK →
  Personel kartında görünen unvan). Backend'de birden fazla aday alan varsa (ör. görev tanımı kaydı ve serbest metin
  unvan), personel kartında görünen seçilir ve hangisi olduğu backend dokümanına yazılır.
- Yalnızca kullanıcının **kendi** unvanı döner.
- Personel kaydı olmayan veya unvanı boş olan hesap → `null`.
- Unvan İK'da nasıl yazıldıysa öyle döner; uygulama çevirmez ve kısaltmaz. (Unvanlar için dil başına metin
  gerekiyorsa ayrı bir karar; bu sözleşme tek metin varsayar.)
- Veritabanı değişikliği beklenmez.

## 2. `GET /api/mobile/v1/me` eki

Yanıta nullable `jobTitle` alanı eklenir:

```json
{
  "userId": "7b2f7a0e-2d7e-4c1a-9a51-3a1f0f2c9d10",
  "employeeId": "c3a1d5f2-8e44-4b0e-a0b8-6f2b1c9e7a55",
  "driverId": null,
  "displayName": "Ayşe Yılmaz",
  "preferredLanguageCode": "tr",
  "modules": ["officeAttendance"],
  "assignedVehicle": null,
  "photoSha256": null,
  "jobTitle": "Operasyon Müdürü"
}
```

| Alan | Kural |
| --- | --- |
| `jobTitle` | Personelin güncel İK unvanı, baştaki/sondaki boşluklar kırpılmış. Unvan yoksa, boşsa veya personel kaydı yoksa `null`. En fazla 200 karakter. |

Driver API `me` değişmez.

## 3. Uygulama davranışı (bilgi için)

- `jobTitle` kırpılır; boşsa yok sayılır.
- Mesai ekranındaki durum kartında ad soyadın altında, Profil ekranında addan hemen sonra gösterilir. En fazla iki
  satır; uzunsa `…` ile kesilir.
- Ad gibi telefonda saklanır; internet yokken açılışta da görünür. Yeni bir `me` yanıtı değeri günceller, `null`
  gelirse satır kalkar. Çıkışta silinir.
- Log'a yazılmaz.

## 4. Güvenlik ve gizlilik

Unvan kişisel veridir ama hassas değildir: yalnızca sahibine döner, salt okumadır.

## 5. Kabul testleri (backend)

1. Unvanı olan personel → `me.jobTitle` personel kartındaki unvanla aynı.
2. Unvanı boş/boşluk olan personel ve personel kaydı olmayan kullanıcı → `jobTitle: null`.
3. İK'da unvan değişince bir sonraki `me` yeni değeri döner.
4. Başka kullanıcının unvanı hiçbir yoldan dönmez.
5. Mevcut `me` alanları ve davranışı değişmez (`api_disabled`, hız sınırı, problem yanıtları).

## 6. Codex görevi (yapıştırılabilir)

> DynamicONE backend'inin Mobile API v1 `GET /api/mobile/v1/me` yanıtına personelin kendi İK unvanını ekle.
> Sözleşme: Android deposundaki `docs/22_EMPLOYEE_JOB_TITLE.md` (§1–§5). Yanıta nullable `jobTitle` (string) ekle:
> token'dan çözülen kullanıcının bağlı `EmployeeProfile` kaydındaki, web'de İK → Personel kartında görünen unvan/görev
> alanı; kırpılmış, boşsa veya personel kaydı yoksa `null`. Birden fazla aday alan varsa personel kartında görüneni
> kullan ve hangisi olduğunu dokümana yaz. Migration beklenmiyor; gerekiyorsa açıkça yaz. Driver API `me` değişmesin.
> Mevcut `mobile-api.access` yetkisi, hız sınırı, api_disabled ve problem+json davranışını koru. §5'teki testleri ekle,
> OpenAPI'de görünsün, backend Mobile API dokümanına kısa bir not ekle. Alan adını değiştirme; farklılık gerekiyorsa
> açıkça yaz.

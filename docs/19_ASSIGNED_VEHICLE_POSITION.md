# 19 — Atanmış aracın son konumu (backend sözleşme önerisi)

Durum: **öneri, 19.09.2026.** Backend'de yoktur; Android uygulaması bu sözleşmeye göre hazırdır ve uç yokken
(`404`, `api_disabled` değil) konum satırını hiç göstermez. Codex görevi §7'dedir.

Kullanıcı isteği (19.09.2026): şoför ana sayfada atanmış aracının şu anki konumunu görsün. Kullanıcı kararı: harita
gömülmez; yer adı telefonda çözülür ("Gliwice, PL"), konumun zamanı ("12 dk önce") ve *Haritada göster* düğmesi
(telefonun harita uygulaması) gösterilir.

## 1. Kaynak ve sınırlar

- Araç, `me.assignedVehicle` ile **aynı seçimdir** (backend PR #133, `DriverApiReadService.AssignedVehicleAsync`):
  şoförün güncel filo ataması. Sefer aracı veya mesai (NFC) aracı kullanılmaz.
- Konum, o aracın bağlı Webfleet aracının (`OperationalVehicle.WebfleetVehicleId`) en yeni `WebfleetPositions`
  kaydıdır (enlem ve boylamı dolu olanlar içinde `PositionTimeUtc` azalan, sonra `Id` azalan). Yeni bir Webfleet
  çağrısı veya zamanlayıcı eklenmez; mevcut eşitleme (varsayılan 60 sn) yeterlidir.
- Yalnızca kimliği token'dan çözülen şoförün kendi atanmış aracı döner. Başka araç, geçmiş iz, sürücü adı, hız,
  kontak, yakıt ve kilometre **dönmez** (veri en aza indirme).
- Yaş sınırı yoktur: park halindeki aracın konumu saatlerce aynı kalabilir; zamanı uygulama gösterir.
- Veritabanı değişikliği yoktur.

## 2. `GET /api/driver/v1/assigned-vehicle/position`

OpenAPI `driver-v1`, mevcut bearer politikası, Mobile API token uyumluluğu, `api_disabled` ara katmanı, hız sınırı ve
problem yanıtları aynen geçerlidir. İstek gövdesi ve parametre yoktur.

Her zaman `200` (şoför erişimi varsa):

```json
{
  "status": "Available",
  "vehicle": {
    "vehicleId": "8d2f3c1e-6c0b-4e55-9a51-3a1f0f2c9d10",
    "companyCode": "Dyna049",
    "registrationNumber": "PZ8W582"
  },
  "position": {
    "latitude": 50.294512,
    "longitude": 18.671433,
    "positionTimeUtc": "2026-09-19T10:58:12Z",
    "receivedAtUtc": "2026-09-19T10:59:03Z"
  }
}
```

| Alan | Kural |
| --- | --- |
| `status` | `Available` · `NotAssigned` · `NotLinked` · `NoData` (büyük/küçük harf duyarlı metin, sayı değil). |
| `vehicle` | `me.assignedVehicle` ile aynı nesne; `NotAssigned` iken `null`. |
| `position` | Yalnızca `Available` iken dolu, diğer durumlarda `null`. |
| `latitude` / `longitude` | WGS84 ondalık derece, JSON sayısı. |
| `positionTimeUtc` | Webfleet'in bildirdiği konum zamanı, UTC, `Z` ekiyle (`DateTime.SpecifyKind(..., Utc)`). |
| `receivedAtUtc` | Sunucunun kaydı aldığı zaman, UTC, `Z` ekiyle. |

Durumlar:

| Durum | Ne zaman |
| --- | --- |
| `NotAssigned` | `me.assignedVehicle` null olurdu (atama yok, bitmiş/gelecek atama, pasif araç). |
| `NotLinked` | Araçta `WebfleetVehicleId` yok. |
| `NoData` | Bağlı Webfleet aracının koordinatlı konum kaydı yok. |
| `Available` | Koordinatlı en yeni kayıt döner. |

Hatalar: diğer Driver API uçlarıyla aynı (`401 unauthorized`, `403 driver_access_required`, `404 api_disabled`,
`429 rate_limit_exceeded`). Başka hata kodu eklenmez.

## 3. Uygulama davranışı (bilgi için)

- Ana sayfa öne gelince ve öndeyken 60 saniyede bir okunur; ekran arka plana gidince okuma durur.
- `404` (`api_disabled` değil): uç bu sunucuda yok, satır gösterilmez; uygulama yeniden açılınca tekrar denenir.
- Konum yalnızca bellekte tutulur (telefona yazılmaz); hesap değişince veya çıkışta silinir.
- Yanıttaki `vehicle.vehicleId`, ekranda gösterilen `me.assignedVehicle.vehicleId` ile aynı değilse konum gösterilmez
  (iki okuma arasında atama değişmiş olabilir; bir sonraki okumada düzelir).
- Yer adı telefonun adres çözümüyle (Android `Geocoder`) bulunur; bulunamazsa koordinat yazılır.
- *Haritada göster* telefonun harita uygulamasını `geo:` bağlantısıyla açar.

## 4. Güvenlik ve gizlilik

- Konum şoförün kendi atanmış aracına aittir; başka şoföre ait araç asla dönmez (§6 testleri).
- Log'a koordinat yazılmaz. Mevcut denetim kurallarından fazlası gerekmez (salt okuma).

## 5. Performans

Tek atanmış araç ve tek son kayıt okunur; mevcut `IX_WebfleetPositions_Vehicle_PositionTime` indeksi kullanılır.
Uygulama en fazla dakikada bir istek gönderir.

## 6. Kabul testleri (backend)

1. Güncel atama + koordinatlı konum → `Available`, doğru araç ve en yeni koordinatlı kayıt; koordinatsız daha yeni
   kayıt atlanır.
2. Atama yok / bitmiş / gelecek / pasif araç → `NotAssigned`, `vehicle` ve `position` null.
3. `WebfleetVehicleId` yok → `NotLinked`.
4. Bağlı ama koordinatlı kayıt yok → `NoData`.
5. Başka şoförün aracı ve konumu hiçbir durumda dönmez.
6. Zamanlar `Z` ekiyle serileşir.
7. Mobile API token'ı ile de çalışır; şoför erişimi olmayan hesap `403 driver_access_required` alır.
8. `api_disabled` iken `404 api_disabled`.

## 7. Codex görevi (yapıştırılabilir)

> DynamicONE backend'ine Driver API v1 için `GET /api/driver/v1/assigned-vehicle/position` ucunu ekle. Sözleşme:
> Android deposundaki `docs/19_ASSIGNED_VEHICLE_POSITION.md` (§1–§6). Araç seçimi için PR #133'teki
> `DriverApiReadService.AssignedVehicleAsync`'i yeniden kullan; konum için `PlanningVehiclePositionReadService` ile
> aynı sorguyu (koordinatlı en yeni `WebfleetPositions` kaydı) kullan. Yanıt her zaman 200 ve `status`
> (`Available`/`NotAssigned`/`NotLinked`/`NoData`) taşır; `vehicle` `me.assignedVehicle` ile aynı biçimdedir;
> `position` yalnızca `Available` iken doludur ve sadece `latitude`, `longitude`, `positionTimeUtc`, `receivedAtUtc`
> içerir. Zamanları `DateTime.SpecifyKind(..., DateTimeKind.Utc)` ile `Z` ekli döndür. Hız, kontak, yakıt, sürücü
> ve geçmiş dönme. Migration yok. Mevcut driver-v1 yetki, hız sınırı, api_disabled ve problem+json davranışını koru.
> §6'daki testleri ekle, OpenAPI `driver-v1`'de görünsün. Backend dokümanına (`docs/67_DriverAppApi.md`) kısa bir
> bölüm ekle. Alan/enum adlarını değiştirme; bir farklılık gerekiyorsa açıkça yaz.

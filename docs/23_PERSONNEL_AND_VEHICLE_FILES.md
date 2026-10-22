# 23 — Belgelerim ve Araç belgeleri (Mobile API v1)

Durum: **backend'de var (30.09.2026)**, uygulama combined worktree'de hazır. Kaynak: kullanıcının Mobile API v1
brifi ve backend `src/DynamicONE.Web/Controllers/MobileDocumentsApiController.cs` +
`MobileApi/MobileDocumentContracts.cs` (alan adları bunlarla karşılaştırıldı). Veritabanı migration'ı yoktur.
OpenAPI yalnızca development'ta: `/swagger/mobile-v1/swagger.json`.

Mevcut `hr-documents` imza/makbuz akışı (docs/17) değişmedi; bu uçlar personel dosyasına **yüklenmiş** evraklar içindir.
Mobil yükleme ucu yoktur, uygulama yalnızca okur.

## 1. Uçlar

| Uç | Modül | Not |
|---|---|---|
| `GET me/documents?page=1&pageSize=50` | `hrDocuments` | Tüm türler, en yeni yükleme önce. `items/page/pageSize/hasMore`, pageSize 1–100 (uygulama 50). |
| `GET me/documents/requirements` | `driver` | 15 zorunlu tür, `Missing/Incomplete/Expired/ExpiringSoon/Valid` + dört sayaç. |
| `GET me/documents/{id}/content` | `hrDocuments` | Özgün dosya. **Sadece `driver` modülü olan hesap 403 alır.** |
| `GET me/assigned-vehicle/documents` | `driver` | `{ assignedVehicle, items, requirements }`; araç yoksa null + boş diziler. |
| `GET me/assigned-vehicle/documents/{id}/content` | `driver` | Özgün dosya; atama yoksa 404. |

Dosya uçları PDF/JPEG/PNG/WebP, `ETag`, `Content-Disposition` ve `Cache-Control: private, no-store` döner.

## 2. Hatalar

`401 unauthorized` (mevcut refresh; refresh reddi oturumu kapatır), `403 hr_documents_access_required` /
`driver_access_required` (erişim kaldırıldı), `404 document_not_found` (dosya yok / erişim artık yok),
`503 document_storage_unavailable` (geçici okuma sorunu), `429 rate_limit_exceeded` (Retry-After'a kadar yeni istek
yok), `404 api_disabled` (servis kapalı). Başka bir 404 liste ucunda "sunucuya bağlı değil" olarak gösterilir.

## 3. Uygulama

- `feature/personnelfiles`: `MyDocumentsScreen`, `VehicleDocumentsScreen`, uygulama içi `DocumentViewerScreen`.
- `data/real/RealPersonnelFileRepositories.kt`: hesaba bağlı, yalnızca bellekte; çıkışta ve başka hesap girince silinir.
- Dosyalar `cache/private_document_view/` altına indirilir (FileProvider'da **yok**, başka uygulamaya verilmez),
  görüntüleyici kapanınca ve çıkışta silinir. Token URL'ye konmaz.
- Navigasyon: ofis çubuğunda *Belgelerim*; şoförde Profil → *Daha fazla* altında *Belgelerim* ve *Araç belgeleri*.
- Kararlar: ASSUMPTIONS.md "Belgelerim ve Araç belgeleri" (F1–F8).

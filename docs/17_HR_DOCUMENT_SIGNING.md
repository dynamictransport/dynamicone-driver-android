# 17 — Personel evrakı imzalatma (telefondan imza): backend sözleşme önerisi

Durum: **öneri, 18.09.2026.** Backend'de yoktur. Android tarafı bu sözleşmeye göre yazıldı ve yalnızca MockWebServer
ile test edildi. Uygulama bu ekranları sunucu `me.modules` içinde `hrDocuments` gönderene kadar göstermez; ayrı bir
açma/kapama ayarı yoktur (docs/15 §1: modülleri sunucu belirler). Bir alan veya kod değişirse iki taraf birlikte
değişir; tek kaynak yayındaki OpenAPI (`mobile-v1`) dokümanıdır. Codex görev metni: §13.

## 1. Amaç

Ofis DynamicONE web'den bir personel evrakı (ilk örnek: 4 dilli RODO bilgilendirme metni
`DynamicONE_RODO_4_Dil_PL_TR_EN_AZ.docx`) gönderir. Evrak, seçilen çalışanların telefonunda **Personel
evraklarım** ekranında görünür, bildirim gelir, çalışan evrakı okuyup **parmağıyla imzalar**. Evrakın bir **son imza
tarihi** vardır. Ofis web'de kimin gördüğünü, imzaladığını ve geciktiğini izler; imzalı kanıt PDF'i personel dosyasına
girer.

Kullanıcı kararları (18.09.2026):

| Konu | Karar |
| --- | --- |
| İmza biçimi | Belge sonuna kadar okunur, beyan kutusu işaretlenir, **parmakla imza** atılır, *İMZALA VE GÖNDER*. Ofisteki tablet imzasıyla (`EmployeeFormSignature`) aynı yapı. |
| Son gün geçerse | **Hatırlatılır, engellenmez.** İmza son günden sonra da atılabilir, "gecikti" diye işaretlenir. Sefer ve mesai hiçbir zaman engellenmez. |

**Hukuki sınır (hukuk danışmanına teyit ettirilmeli):** Uygulama içi imza, oturum açmış çalışanın kimliği
doğrulanmış elektronik beyanıdır (kanıt değeri: hesap, zaman, cihaz, belge özeti). **Nitelikli elektronik imza
değildir** (kwalifikowany podpis elektroniczny). Bilgilendirme, yönetmelik/talimat tebliği ve "okudum, aldım"
teyitleri için uygundur. Yazılı veya nitelikli elektronik şekil şartı olan belgelerin (ör. iş sözleşmesi, fesih)
yerini tutmaz. Evrak metni de buna göre yazılmalıdır: RODO metni zaten "zgoda değil, potwierdzenie zapoznania się"
diyor.

**RODO evrakı için gönderim öncesi:** metindeki köşeli parantezli alanlar (şirketin tam unvanı, adres, KRS/NIP,
RODO e-postası, IOD bilgisi) doldurulmalıdır. İmza sayfasındaki alanları (ad soyad, personel no, sürüm, dil, tarih,
imza) kanıt PDF'i otomatik doldurur (§8); evrak dosyasında elle doldurulmaz.

## 2. Kavramlar

| Kavram | Anlamı |
| --- | --- |
| **Evrak** (imza talebi) | Ofisin gönderdiği bir belge: sürüm, referans dil, son gün, dil sürümleri. Gönderildikten sonra içeriği değişmez; içerik değişirse yeni sürüm yeni evrak olarak gönderilir. |
| **Dil sürümü** | Aynı evrakın bir dildeki hali: PDF dosyası + başlık + beyan metni. `languageCode` ISO 639-1 (`pl`, `tr`, `en`, `az`, …). Birden çok dili tek PDF'te içeren dosya `mul` kodudur. |
| **Atama** | Evrakın bir çalışana gönderilmesi. Durum, açılma zamanı, imza ve hatırlatmalar atamaya aittir. API'de her şey atama üzerinden görünür: `id` atamanın kimliğidir. |
| **İmza** | Çalışanın bir dil sürümünü imzalaması: seçilen dil, içeriğin SHA-256'sı, beyan onayı, imza çizgileri, zaman ve cihaz bilgisi. Değişmez kanıttır. |

Öneri: çalışan evrakı dört dilin hepsini değil, **kendi seçtiği dil sürümünü** okur ve imzalar; kanıtta hangi dilin
gösterildiği yazılır (RODO metnindeki "Język wyświetlonej wersji" alanı). Referans dil (RODO'da Lehçe) her dil
sürümünde ayrıca belirtilir.

## 3. Genel kurallar

- Taban adres `/api/mobile/v1/` (docs/15 §2): Bearer, problem+json (`status`, `code`, `title`, `traceId`), 429'da
  `Retry-After`, API kapalıysa `404 api_disabled`, zamanlar ISO-8601 UTC, enum'lar metin.
- Kimlik token'dan çözülür; istemci çalışan kimliği göndermez. Bir çalışan yalnızca **kendi atamalarını** görür;
  başkasının atama kimliği `404 hr_document_not_found` döner (varlığı sızdırılmaz).
- Modül: `hrDocuments`. Aktif `EmployeeProfile`'ı olan ve mobil uygulamaya giriş yapabilen her hesaba açılır (şoför
  ve ofis personeli). Modül yoksa bu uçlar `403 hr_documents_access_required` döner.

## 4. Nesne: `HrDocument`

```json
{
  "id": "8d0f6f4e-2f5c-4a8e-9f61-0c7a3b2d1e90",
  "version": "1.0",
  "referenceLanguageCode": "pl",
  "sentAtUtc": "2026-09-18T08:00:00Z",
  "dueAtUtc": "2026-09-25T21:59:00Z",
  "status": "Pending",
  "firstOpenedAtUtc": null,
  "signedAtUtc": null,
  "signedLanguageCode": null,
  "receiptAvailable": false,
  "languages": [
    {
      "languageCode": "pl",
      "title": "Klauzula informacyjna RODO dla pracowników DynamicONE",
      "declaration": "Potwierdzam, że przed rozpoczęciem korzystania z systemu DynamicONE otrzymałem(-am) niniejszą informację …",
      "contentSha256": "3F2A9C0B5D1E7A4C8B6F0E2D9A1C3B5E7F9D1A3C5E7B9D1F3A5C7E9B1D3F5A7C",
      "sizeBytes": 184233,
      "pageCount": 6
    },
    {
      "languageCode": "tr",
      "title": "DynamicONE çalışanları için RODO aydınlatma metni",
      "declaration": "DynamicONE sistemini kullanmaya başlamadan önce kişisel verilerimin işlenmesine ilişkin bu bilgilendirmeyi aldığımı …",
      "contentSha256": "9C1B7E3A5D0F2C4E6A8B0D2F4A6C8E0B2D4F6A8C0E2B4D6F8A0C2E4B6D8F0A2C",
      "sizeBytes": 179801,
      "pageCount": 6
    }
  ]
}
```

| Alan | Kural |
| --- | --- |
| `id` | Atama kimliği (GUID). |
| `version` | Evrak sürümü, serbest metin (ör. `1.0`), zorunlu. |
| `referenceLanguageCode` | Yorum farkında geçerli dil; `null` olabilir. |
| `sentAtUtc` | Atamanın oluşturulduğu an. |
| `dueAtUtc` | Son imza anı. Web'de tarih + saat şirket saat diliminde seçilir (varsayılan 23:59, `Europe/Warsaw`) ve UTC'ye çevrilir. Son gün yoksa `null`. Uygulama kendi saat diliminde gösterir. |
| `status` | `Pending` (imza bekliyor; son gün geçmiş olabilir) · `Signed` · `Withdrawn` (ofis geri çekti, imzalanmadı). "Gecikti" ayrı durum değildir: `Pending` + `dueAtUtc` geçmiş. |
| `firstOpenedAtUtc` | Çalışanın herhangi bir dil sürümünün içeriğini ilk indirdiği an (§5.3). |
| `signedAtUtc` | Resmî imza zamanı (§5.4 kuralı); `Signed` değilse `null`. |
| `signedLanguageCode` | İmzalanan dil sürümü. |
| `receiptAvailable` | Kanıt PDF'i hazır (§8). İmza kaydedildikten sonra PDF üretimi arka planda tamamlanıyorsa kısa süre `false` olabilir. |
| `languages` | En az bir dil sürümü. Sıra web'de girildiği sıradır (referans dil önce önerilir). `declaration` çalışanın imzaladığı beyandır, metin olarak (PDF içinde de yer alabilir). `contentSha256` PDF baytlarının SHA-256'sı, büyük harf hex. `pageCount` bilinmiyorsa `null`. |

## 5. Uçlar

### 5.1 `GET hr-documents?state=Pending`

İmza bekleyen atamalar (son günü geçmişler dahil). Yanıt Driver API sayfa biçimindedir:

```json
{ "items": [ { "…": "HrDocument" } ], "page": 1, "pageSize": 100, "hasMore": false }
```

- Sıra: `dueAtUtc` artan (son günü olmayanlar sonda), sonra `sentAtUtc`.
- Sunucu en fazla 100 atama döndürür; bekleyen evrak sayısı bunu aşmamalıdır (aşarsa `hasMore: true`, uygulama
  sonraki sayfayı `page=2` ile ister).
- `Withdrawn` atamalar listede yoktur.

### 5.2 `GET hr-documents?state=Signed&page=1&pageSize=25`

İmzalanmış atamalar, `signedAtUtc` azalan. `pageSize` en çok 100. `state` başka bir değerse `400 invalid_state`.

### 5.3 `GET hr-documents/{id}` ve `GET hr-documents/{id}/content?languageCode=tr`

- `GET hr-documents/{id}`: tek atama, `Withdrawn` dahil (bildirime dokunulunca uygulama güncel durumu buradan okur).
- `GET …/content`: seçilen dil sürümünün PDF'i. `200 application/pdf`, başlık `ETag: "<contentSha256>"`.
  İlk başarılı indirme atamanın `firstOpenedAtUtc` değerini yazar (web'de "Görüldü").
- Hatalar: `404 hr_document_not_found`, `400 hr_language_unavailable` (evrakta bu dil yok),
  `409 hr_document_withdrawn` (imzalanmadan geri çekildi; imzalı evrakın içeriği indirilebilir kalır).

Uygulama indirdiği dosyanın SHA-256'sını `contentSha256` ile karşılaştırır; tutmazsa dosyayı göstermez.

### 5.4 `POST hr-documents/{id}/signature`

İstek:

```json
{
  "commandId": "5b1e0c1a-7f7e-4b0a-8f5a-6f1c2d3e4a5b",
  "languageCode": "tr",
  "contentSha256": "9C1B7E3A5D0F2C4E6A8B0D2F4A6C8E0B2D4F6A8C0E2B4D6F8A0C2E4B6D8F0A2C",
  "declarationAccepted": true,
  "signature": {
    "aspectRatio": 2.5,
    "strokes": [
      [ { "x": 0.112, "y": 0.534 }, { "x": 0.118, "y": 0.529 }, { "x": 0.131, "y": 0.521 } ],
      [ { "x": 0.402, "y": 0.611 }, { "x": 0.455, "y": 0.402 } ]
    ]
  },
  "signedAtUtc": "2026-09-19T06:41:12.345Z",
  "elapsedSinceSignMs": 1520,
  "installationId": "e1f0c2d4-5b6a-4c7d-8e9f-0a1b2c3d4e5f"
}
```

| Alan | Kural |
| --- | --- |
| `commandId` | UUID, zorunlu. İmza anında üretilir, tekrar gönderimde değişmez. |
| `languageCode` | İmzalanan (ekranda gösterilen) dil sürümü. |
| `contentSha256` | Çalışana gösterilen PDF'in SHA-256'sı. O dil sürümünün kayıtlı değeriyle aynı olmalı. |
| `declarationAccepted` | `true` olmalı (beyan kutusu işaretlendi). |
| `signature.strokes` | Parmak çizgileri. Her çizgi noktalar dizisi; `x` sağa, `y` aşağı, imza alanına göre `0..1`. Kurallar backend'in tablet imzasıyla aynı (`EmployeeSignatureRules.Parse`): 1–100 çizgi, her çizgi ≥ 2 nokta, toplam ≤ 5000 nokta, tüm noktaları çevreleyen kutu en az 0,03 geniş ve 0,015 yüksek. |
| `signature.aspectRatio` | İmza alanının genişlik/yükseklik oranı (1–6). Sunucu imza resmini bu oranla çizer. |
| `signedAtUtc` | İmza anında **telefon saati**. |
| `elapsedSinceSignMs` | İmzadan bu isteğe kadar geçen süre, telefonun açılıştan beri sayan saatiyle; telefon arada yeniden başladıysa `null`/yok (docs/15 §5.3 ile aynı anlam). |
| `installationId` | Uygulama kurulum kimliği (docs/15 §5.3). |

İşleme sırası:

1. **Idempotency:** aynı kullanıcı + `commandId` daha önce işlendiyse ilk yanıt **200** ile aynen döner.
   Karşılaştırılan alanlar: `commandId`, atama `id`, `languageCode`, `contentSha256`, imza çizgileri (ör. JSON
   özeti), `signedAtUtc`, `installationId`. `elapsedSinceSignMs` karşılaştırılmaz. Diğerlerinden biri farklıysa
   `409 command_conflict`.
2. Modül yoksa `403 hr_documents_access_required`.
3. Atama bu çalışanın değilse veya yoksa `404 hr_document_not_found`.
4. Geri çekilmişse `409 hr_document_withdrawn`.
5. Başka bir `commandId` ile zaten imzalanmışsa (ör. ikinci telefon) `409 hr_document_already_signed`.
6. Dil yoksa `400 hr_language_unavailable`; özet tutmazsa `409 hr_document_changed`.
7. `declarationAccepted` değilse `400 hr_declaration_required`; çizgiler kurala uymuyorsa `400 hr_signature_invalid`.
8. **Zaman (reddedilmez):** `elapsedSinceSignMs` varsa resmî zaman = `receivedAtUtc − elapsed` (kaynak
   `ServerElapsed`); yoksa `signedAtUtc` (kaynak `DeviceClock`); bu değer `receivedAtUtc + 2 dk`'dan ileriyse
   `receivedAtUtc` kullanılır (kaynak `ClockAdjusted`, web'de işaretli). Telefon saati yanlış diye imza
   reddedilmez. Resmî zaman `dueAtUtc`'den sonraysa atama "gecikti" işaretlenir; imza yine geçerlidir.
9. İmza ve kanıt kaydı yazılır, atama `Signed` olur. Kanıt PDF'i (§8) aynı işlemde veya hemen ardından arka planda
   üretilir → **201**.

Yanıt (201 ve idempotent 200): `{ "document": { "…": "HrDocument, status Signed" } }`.

Uygulamanın hata davranışı (docs/15 §5.3 ile aynı): 400/409 kalıcı hata (imza telefonda "gönderilemedi" + sebep;
`hr_document_already_signed` ise evrak imzalı sayılır ve liste yenilenir; `hr_document_changed` ise yeni içerik
indirilip tekrar imzalatılır); 401 refresh; 403 ve `api_disabled` "gönderilmedi", sonra aynı `commandId` ile
tekrar; 429 `Retry-After` sonrası; bağlantı hatası/5xx aynı `commandId` ile otomatik tekrar. İmza, oturum kapansa da
telefonda silinmez; başka bir hesap girince silinir.

### 5.5 `GET hr-documents/{id}/receipt`

İmzalı kanıt PDF'i (§8), `application/pdf`. İmzalanmamış veya henüz üretilmemişse `404 hr_receipt_not_available`.

### 5.6 `POST me/devices`, `DELETE me/devices/{installationId}`

Push bildirimi için cihaz kaydı, **Driver API ile aynı gövde ve kurallar** (docs/13 §3.1–3.2), fakat şoför modülü
gerekmez: mobil modülü olan her hesap kaydolabilir. Şoförler bugünkü gibi Driver API üzerinden kaydolmaya devam
eder. `hr.document` push'u kullanıcının **tüm kayıtlı cihazlarına** gider (Driver API veya Mobile API kaydı);
kayıtların tek tabloda birleşmesi veya ayrı tutulması backend kararıdır. Şifre değişikliği, hesap pasifleştirme ve
çıkışta kayıtlar docs/13 §3.2'deki gibi silinir.

## 6. Push (`type = hr.document`)

```json
{
  "message": {
    "token": "<pushToken>",
    "android": {
      "priority": "HIGH",
      "notification": {
        "channel_id": "hr_documents",
        "tag": "hr-document-<id>",
        "title_loc_key": "push_hr_document_title",
        "body_loc_key": "push_hr_document_body"
      }
    },
    "data": {
      "type": "hr.document",
      "documentId": "<atama id>",
      "reason": "Assigned",
      "sentAtUtc": "2026-09-18T08:00:03Z"
    }
  }
}
```

| `reason` | Ne zaman | `title_loc_key` / `body_loc_key` |
| --- | --- | --- |
| `Assigned` | Atama oluşunca | `push_hr_document_title` / `push_hr_document_body` |
| `Reminder` | §7 hatırlatması | `push_hr_document_reminder_title` / `push_hr_document_reminder_body` |
| `Overdue` | Son gün geçince | `push_hr_document_overdue_title` / `push_hr_document_overdue_body` |
| `Withdrawn` | Ofis geri çekince | **Yalnızca veri** (bildirim yok): uygulama listeyi yeniler, bildirimi kaldırır. |

Push'ta evrak başlığı, kişi adı veya içerik yoktur (docs/13 §2). Push gönderimi en iyi çabadır: gönderilemese de
atama geçerlidir, uygulama listeyi açılışta okur.

## 7. Hatırlatma kuralları (varsayılan, ASSUMPTIONS D6)

- Atama oluşunca bir bildirim (`Assigned`).
- Son günden **24 saat önce** hâlâ imzalanmamışsa bir hatırlatma (`Reminder`). Atama son güne 24 saatten az
  kala oluşturulduysa bu hatırlatma gönderilmez.
- Son gün geçtiği anda hâlâ imzalanmamışsa bir bildirim (`Overdue`). Sonrasında otomatik bildirim yoktur.
- Ofis web'den seçili çalışanlara elle hatırlatma gönderebilir (`Reminder`); aynı atamaya 12 saatte en çok bir.
- Hatırlatmalar yalnızca `Pending` atamalara gider. Hiçbir durumda uygulama veya iş (sefer, mesai) kilitlenmez.

## 8. Kanıt PDF'i (imzalı evrak)

Mevcut tablet imzasındaki kanıt yaklaşımı (`EmployeeFormSignature`: `SnapshotJson`, `SnapshotSha256`, `StorageKey`,
`FileSha256`, `EmployeeSignedFormDocument`) aynen kullanılır; tanık (`WitnessUserId`) yerine oturum ve cihaz bilgisi
kanıttır. PDF = imzalanan dil sürümünün **orijinal sayfaları** + sonuna eklenen **imza sayfası**. İmza sayfasının
alan adları imzalanan dilde ve Lehçe (referans) yazılır:

- evrak başlığı, sürüm, atama/evrak kimliği, referans dil, **gösterilen dil**, içerik SHA-256;
- çalışanın adı soyadı ve personel numarası (profilden, otomatik);
- beyan metni, ekranda gösterildiği haliyle, ve beyan onayı;
- imza resmi (çizgilerden, `aspectRatio` ile);
- resmî imza zamanı (`Europe/Warsaw` ve UTC) ve zaman kaynağı (`ServerElapsed` / `DeviceClock` /
  `ClockAdjusted`), telefon saati, sunucunun alış zamanı, son gün ve gecikme bilgisi;
- `installationId`, uygulama sürümü (`X-App-Version`), platform, `commandId`.

IP adresi ve oturum kimliği PDF'e yazılmaz; yalnızca denetim kaydında tutulur. Kanıt PDF'i çalışanın personel
dosyasına (docs/13 Workforce personnel files) otomatik eklenir; silinemez, yalnızca yetkililer görür.

## 9. Web (ofis)

Yeni sayfa önerisi **Personel evrak imzaları** (Workforce altında; yetki önerisi `HrDocumentsManage`, yalnızca
izleme için `HrDocumentsView`):

1. **Yeni evrak:** sürüm; referans dil; her dil için başlık, beyan metni (dil başına hazır şablonla doldurulmuş) ve
   PDF (yalnızca PDF, imza baytları kontrol edilir, en çok 20 MB); son gün (tarih + saat, şirket saat dilimi,
   varsayılan +7 gün 23:59); alıcılar: tüm aktif çalışanlar / yalnızca şoförler / yalnızca ofis personeli / seçili
   çalışanlar; **yeni işe girenlere otomatik gönder** (işe giriş tarihinden itibaren N gün süreyle; RODO bilgilendirmesi
   için önerilir). Word dosyası kabul edilmez: ofis Word'den PDF olarak kaydeder (her dil ayrı PDF önerilir; tek PDF
   gerekiyorsa `mul`).
2. **Gönderildikten sonra** içerik ve dil sürümleri değiştirilemez. Hata varsa evrak geri çekilir ve yeni sürüm
   gönderilir.
3. **İzleme:** alıcı bazında durum (*Gönderildi* · *Görüldü* · *İmzaladı* · *Gecikti* · *Geri çekildi*), gönderim, ilk
   açılış, imza zamanı ve zaman kaynağı, dil, gecikme, kanıt PDF'i; filtreler; CSV/Excel dışa aktarma; seçilenlere
   *Hatırlat*; evrakı veya tek alıcıyı *Geri çek* (imzalı kanıtlar kalır).
4. Mobil uygulamaya erişimi olmayan çalışanlar listede "Uygulama erişimi yok" diye görünür; ofis bunları tablet veya
   kâğıt ile imzalatır.

## 10. Güvenlik ve kişisel veri

- Evrak içerikleri Azure depolamada özel kalır; uygulamaya imzalı URL verilmez, içerik Bearer ile bu uçlardan iner.
- Log ve telemetriye imza çizgileri, evrak içeriği, push token yazılmaz. Denetim kaydı: gönderim, açılma, imza,
  hatırlatma, geri çekme (kim, ne zaman).
- Oran sınırı: `…/signature` için kullanıcı başına dakikada 10 öneri; içerik indirme için mevcut sınır.
- Uygulama PDF'i yalnızca uygulamaya özel klasörde tutar (galeri/paylaşım yok), imzalanınca veya oturum kapanınca siler.

## 11. Backend kabul listesi (Codex)

1. `me.modules`: aktif personel kaydı olan şoför ve ofis hesaplarında `hrDocuments`; personel kaydı olmayan
   hesaplarda yok.
2. Çalışan yalnızca kendi atamalarını görür; başkasının `id`'si `404`.
3. `state=Pending` sıralaması ve `Withdrawn` hariç tutulması; `state=Signed` sayfalama.
4. İçerik indirme `ETag` = `contentSha256`; ilk indirme `firstOpenedAtUtc` yazar; geri çekilmiş imzasız evrakta `409`.
5. İmza: idempotency (aynı `commandId` → 200 ve ilk gövde, farklı elapsed dahil; alanlar değişirse 409), ikinci
   telefondan imza → `hr_document_already_signed`, özet farkı → `hr_document_changed`, beyan ve çizgi kuralları.
6. Zaman kuralı (§5.4-8) ve son günden sonra imzanın kabulü ("gecikti" işareti).
7. Kanıt PDF'i: orijinal sayfalar + imza sayfası, özetler kayıtlı, personel dosyasında.
8. Push: `Assigned`, `Reminder` (24 saat önce), `Overdue`, `Withdrawn` (veri), elle hatırlatma sınırı; push içeriğinde
   kişisel veri yok.
9. Mobile API `me/devices` ofis hesabıyla çalışır; şoförün Driver API kaydı da `hr.document` push'u alır.
10. Web: yeni evrak (dil sürümleri, son gün, alıcılar, yeni işe girenler), izleme, hatırlatma, geri çekme, dışa aktarma.
11. OpenAPI (`mobile-v1`) ve `docs/` kılavuzu; production migration ve yayın kullanıcı onayıyla.

## 12. Android tarafı (18.09.2026 durumu)

- Ekran **Personel evraklarım**: imza bekleyenler son güne göre, sonra imzalananlar. Ofis personelinde alt menüde
  (*Evraklarım*), şoförde Profil → *Personel evraklarım* ve ana ekranda "İmzanızı bekleyen evrak" kartı (şoförün
  alt menüsü dolu; *Evraklar* sekmesi sefer evraklarıdır).
- Evrak ekranı: PDF uygulama içinde sayfa sayfa gösterilir, dil sürümü seçilebilir (varsayılan: uygulama dili, yoksa
  referans dil). Beyan ve imza alanı belgenin sonundadır. Parmakla imza + beyan kutusu olmadan *İMZALA VE GÖNDER*
  pasiftir.
- İmza önce telefona (Room, `commandId` ile) yazılır, sonra gönderilir; internet yoksa WorkManager aynı `commandId`
  ile gönderir. Sunucu onaylayana kadar evrak "İmzalandı" değil "Gönderilmeyi bekliyor" görünür.
- `hr.document` push'u listeyi yeniler; uygulama açıkken kendi bildirimini gösterir, bildirime dokununca evrak açılır.
- Ofis hesapları push için Mobile API `me/devices` ile kaydolur (§5.6); şoförler Driver API ile.
- Gereken: `app.mobileApi=true` (Driver API oturumunda modül listesi yalnızca `driver`dır) ve yeni paket adının
  (`com.dynamictransport.dynamicone`) Firebase projesine eklenip `google-services.json`'un yenilenmesi.

## 13. Codex için görev metni (backend)

> DynamicONE backend'e personel evrakı imzalatma ekle. Sözleşme: Android reposundaki
> `docs/17_HR_DOCUMENT_SIGNING.md` (bu doküman). Mobile API v1'e (`/api/mobile/v1`, docs/15) `hrDocuments` modülünü,
> `GET hr-documents` (Pending / Signed), `GET hr-documents/{id}`, `GET hr-documents/{id}/content`,
> `POST hr-documents/{id}/signature`, `GET hr-documents/{id}/receipt` uçlarını ve ofis hesapları için
> `POST/DELETE me/devices` ekle. Tablet imzasının kanıt yapısını (`EmployeeFormSignature`, `EmployeeSignatureRules`,
> `EmployeeSignedFormDocument`) yeniden kullan; imza çizgisi kuralları aynı olsun. Push için mevcut FCM altyapısını
> (`DriverPushDispatcher`, `DriverPushDelivery`, docs/75) şoför olmayan kullanıcılara ve `hr.document` tipine genişlet.
> Web'de *Personel evrak imzaları* sayfasını (§9) ve personel dosyasına kanıt PDF'i eklemeyi yap. Mevcut Driver API
> ve Mobile API davranışını bozma. Endpoint veya alan adı değiştirmen gerekirse dokümanı güncelle ve farkı açıkça
> raporla. §11 kabul listesini testlerle doğrula. Production veritabanına migration uygulama ve yayın yapma;
> migration betiğini ve dağıtım adımlarını hazırla.

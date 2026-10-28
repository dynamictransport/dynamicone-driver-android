# 28 — Personele nakit ödeme ve parmakla imza: backend sözleşmesi

Durum: **canlı, 27.10.2026.** Backend görevi TH-2026-0562 (PR #651, özellik dalı `feature/employee-cash-approval`,
canlı kaynak `89fa94a3`, yayın `b75b792e`, 27.10.2026 17:32 Varşova). `EmployeeCashApprovals` SQL'i uygulandı; mobil
uçlar kimliksiz erişimde 401 döndürüyor. Kaynak belge: `Employee-Cash-Approval-Claude-Handoff.tr.md` +
`Yayin-dogrulamasi.txt` (28.10.2026 aktarımı). Android kararları: `ASSUMPTIONS.md` **P1–P14**.

## 1. Amaç

Finans DynamicONE web'de personelin **avans / maaş / mesai** formunu hazırlar ve ödemeyi onaylar. Parayı teslim eden
görevli (ör. ofis kasası) telefonunda **Personele yapılan nakit ödemeler** ekranını açar; parayı alan personel
görevlinin telefonunda formu görür, **parmağıyla imzalar**, teslim beyanını işaretler ve **Parayı aldım** der.
Personel aynı akışı kendi hesabından **Nakit ödemelerim** bölümünde de tamamlayabilir. İmzalı makbuz PDF'i sunucuda
oluşur; kasa hareketi gerçek teslim gününde yazılır.

Uygulama **tutar belirlemez, ödeme oluşturmaz, formu değiştirmez, kur/bakiye hesaplamaz**. Yetki kullanıcı adına
göre kodlanmaz: `me.modules` ne derse o gösterilir (docs/15 §1).

## 2. Modüller

| Modül | Hesap | Ekran |
| --- | --- | --- |
| `cashDisbursements` | Teslim yetkisi (`finance.employee-cash.deliver`) verilen hesaplar | **Personele yapılan nakit ödemeler** (`AppSection.CASH_DISBURSEMENTS`) |
| `cashPayments` | Kendisine nakit ödeme onaylanabilen personel | **Nakit ödemelerim** (`AppSection.MY_CASH_PAYMENTS`) |

Görevli hesabında oturum açık kalır; alıcının parolası istenmez. Şoförlerin henüz uygulama kullanmaması görevli
cihazındaki imza akışını engellemez.

## 3. Uçlar

Kök: `https://dynamicone.ai/api/mobile/v1`, mevcut Mobile Bearer oturumu.

| Amaç | Görevli (`cashDisbursements`) | Kendi hesabı (`cashPayments`) |
| --- | --- | --- |
| Liste | `GET /cash-payments` | `GET /me/cash-payments` |
| Detay | `GET /cash-payments/{id}` | `GET /me/cash-payments/{id}` |
| İmza | `POST /cash-payments/{id}/signature` | `POST /me/cash-payments/{id}/signature` |
| İmzalı PDF | `GET /cash-payments/{id}/receipt` | `GET /me/cash-payments/{id}/receipt` |

Liste parametreleri: `state=Approved|Received|Cancelled` (varsayılan `Approved`), `page` (1–10000),
`pageSize` (1–100; uygulama 50). Yanıt: `items`, `page`, `pageSize`, `hasMore`. Sıralama onay zamanı azalan, sonra ID.
Alıcı filtresi sunucu sahipliğinin yerine geçmez; uygulama filtre uygulamaz.

## 4. Veri

Liste öğesi ve detay aynı şekildedir:

| Alan | Anlam |
| --- | --- |
| `id` / `employeeId` | Ödeme onayı / alıcı UUID |
| `employeeName` / `registrationNumber` | Onay anındaki alıcı adı / sicil |
| `kind` | `advance`, `salary`, `overtime` |
| `amount` / `currency` | JSON sayı (pozitif, en çok iki ondalık) / `EUR` veya `PLN` |
| `plannedPaymentDate` | Önerilen tarih, `yyyy-MM-dd` |
| `state` | `Approved`, `Received`, `Cancelled` |
| `approvedAtUtc` / `receivedAtUtc` | ISO UTC; `receivedAtUtc` teslim öncesinde null |
| `title` / `lines` / `declaration` | Onaylanan formun aynen gösterilecek metni (dil sunucudan) |
| `approvedByName` | Finans onayını veren kişinin formdaki adı |
| `formHash` | İmza isteğine aynen geri gönderilecek SHA-256 |
| `canSign` | Şu anda imzalanabilir mi; sunucu POST'ta tekrar denetler |
| `receiptUrl` | Teslimden sonra yetkili göreli PDF yolu; öncesinde null |

Uygulama: `CashPaymentDto` → `CashPayment` (`data/real/CashPaymentMapping.kt`). `lines` tipi sözleşmede
belirtilmediği için dize ve nesne öğeleri hoşgörülü okunur (P10).

## 5. İmza isteği

```json
{
  "commandId": "5af65b7a-e11e-4cec-bb92-e191947f26ac",
  "formHash": "GET detayındaki formHash",
  "confirmed": true,
  "aspectRatio": 2.5,
  "strokes": [[{"x": 0.1, "y": 0.6}, {"x": 0.2, "y": 0.2}, {"x": 0.8, "y": 0.7}]]
}
```

- `aspectRatio` çizim alanının en/boy oranı (1–6); uygulama `HrDocumentRules.PAD_ASPECT_RATIO = 2.5`.
- `strokes` 1–100 çizgi, çizgi başına ≥ 2 nokta, toplam ≤ 5000 normalize nokta, X/Y 0–1; tek nokta/çok küçük iz
  kabul edilmez. Döndürmede çizim korunur, uzatılmaz.
- Sunucu ilk başarıda **201**, aynı `commandId` tekrarında **200** ve aynı detay yanıtını döner. Başka komut
  mevcut imzayı değiştiremez. Resmi teslim zamanı sunucudan gelir.
- `confirmed` yalnızca personel teslim beyanını işaretledikten sonra `true` gönderilir; uygulama beyan işaretlenmeden
  istek atmaz.

## 6. Hatalar

`application/problem+json`, `code` alanı. İş kuralları ve uygulamanın davranışı (ASSUMPTIONS P7):

| Kod | HTTP | Uygulama |
| --- | --- | --- |
| `cash_delivery_required`, `cash_access_required` | 403 | İmza FAILED/ACCESS_REQUIRED; `me` yeniden okunur; detay kapanır; listede "yetki yok" |
| `cash_payment_not_found` | 404 | Yerel kayıt ve imza silinir; "artık erişilemiyor" |
| `cash_receipt_not_found` | 404 | Görüntüleyicide "makbuz bulunamadı" |
| `invalid_cash_filter` | 400 | Liste okunamadı (uygulama yalnızca sözleşmedeki üç durumu gönderir) |
| `invalid_cash_signature` | 400 | FAILED; yeniden imzala |
| `cash_form_changed` | 409 | FAILED; detay yeniden okunur, yeni form gösterilir |
| `cash_employee_inactive` | 409 | FAILED; finansa başvur |
| `cash_payment_cancelled` | 409 | Yerel imza silinir; detay yeniden okunur (İptal) |
| `cash_payment_already_received` | 409 | Yerel imza silinir; detay yeniden okunur (Teslim edildi, makbuz) |
| `cash_payment_conflict` | 409 | FAILED; detay yeniden okunur |
| `command_conflict` | 409 | FAILED |
| `cash_receipt_storage_unavailable` | 503 | Makbuz açılamadı; tekrar dene |
| (kodsuz 400) | 400 | FAILED (genel) |
| 401 | | Mevcut token yenileme; yine olmazsa imza beklemede kalır, yeniden girişte gönderilir |
| 429 | | `Retry-After` kadar bekleyip aynı istek |
| çevrimdışı / 5xx | | İmza telefonda beklemede; WorkManager aynı `commandId` ile tekrar dener |

Hiçbir hata yanıtı yerelde "ödendi" durumu üretmez; imza sunucu 200/201 vermeden silinmez.

## 7. Uygulama akışı

1. Liste (`state=Approved` varsayılan; *Teslim edilen* / *İptal edilen* filtreleri): ad, sicil, tür, tutar + para
   birimi (`DriverFormats.money`, EUR/PLN ayrı), planlanan tarih.
2. Satıra dokununca detay sunucudan yeniden okunur; yalnızca bu okuma başarılıysa, `state=Approved` ve
   `canSign=true` ise imza bölümü açılır (P5).
3. Form metni (`title`, `lines`, `declaration`, `approvedByName`) aynen gösterilir. Personel beyanı işaretler,
   parmağıyla imzalar, **PARAYI ALDIM**'a basar.
4. İmza Room'a (`cash_signatures`, yeni UUID) yazılır, sonra gönderilir. Çift dokunma / ekran dönüşü / yeniden
   başlatma / *TEKRAR GÖNDER* aynı `commandId` ile aynı isteği yollar (P6).
5. 200/201: yerel imza silinir, kayıt teslim edilenlere taşınır, liste yenilenir, imzalı makbuz uygulama içi PDF
   görüntüleyicide Bearer ile açılır (P9). Token içeren veya halka açık bağlantı üretilmez.
6. Oturum süresi dolunca bekleyen imza kalır; açık çıkış ve hesap değişimi imzaları siler (P8). İmza/finans verisi
   loga, analitiğe veya push içeriğine yazılmaz.

## 8. Cihaz kabul senaryoları (handoff §"Cihaz kabul senaryoları")

1. Finans 100 EUR avans onaylar: görevli kuyruğunda ve alıcının kendi listesinde görünür; kasa değişmez. Formu
   hazırlayan hesabın teslim izni yoksa imza alamaz.
2. Görevli telefonda detayı açar; alıcı imzalar. Makbuz oluşur, bekleyenlerden kalkar, kasa 100 EUR azalır. Aynı
   POST tekrarı ikinci hareket oluşturmaz.
3. Kendi hesabındaki imza aynı sonucu verir; başka personel ID'siyle erişim başarısızdır.
4. İki cihaz aynı onayı açar: ilki tamamlar; ikincisi yeni ödeme oluşturamaz ve detayı yeniler.
5. Finans onayı iptal eder: açık eski ekrandan imza gönderimi reddedilir.
6. Yetki sonradan kaldırılır, personel pasife alınır veya oturum kapanır: eski ekran yetkiyi aşamaz. PDF de aynı
   erişim denetimine tabidir.
7. Zayıf bağlantı / çift dokunma / uygulama dönüşü imzayı kaybetmez; sunucu teyidi olmadan başarı gösterilmez.
   Maaş, mesai, PLN ve telefon yatay/dikey görünümü de denenir.

Gerçek personel adına test imzası atılmaz; gerçek nakit hareketi oluşturulmaz.

## 9. Hesap durumu (27.10.2026)

- Adem Uzun (`adem.uzun`) ve Manuchehr Rajabov (`manuchehr.rajabov`): `finance.employee-cash.deliver` izni verildi.
- Yunus Emre Halhalli: hesabı Erkan oluşturacak, sonra aynı izin verilmeli.
- Uygulamada kişi/kullanıcı adı sabitlenmez; erişim `/me` modüllerine göredir.

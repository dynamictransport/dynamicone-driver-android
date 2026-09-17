# 14 — Eski durum akışında takılan seferler: backend önerisi

**Durum: ÖNERİ (backend / Codex).** Yeni endpoint veya alan gerektirmez; mevcut `GET trips/{tripId}/execution` ve `POST trips/{tripId}/actions` sözleşmesi içinde kalır. Mobil uygulamada değişiklik gerekmez.

## 1. Sorun

Durak bazlı akıştan (TripActions, TH-2026-0439) önce eski durum adımlarıyla başlatılmış bir sefer, eski akışın beş adımı bittiğinde tamamlanmamış durakları olsa bile ilerleyemiyor.

Örnek (14.09.2026, üretim): **DT-2026-000012-01** (`380d0e81-6fe6-4ce1-add3-70b577d9609e`), üç duraklı konteyner seferi.

| Sıra | Faaliyet | Konum | Durak durumu |
|---|---|---|---|
| 1 | Boşaltma | Rotterdam World Gateway (RWG) | Tamamlandı (13.09 17:10) |
| 2 | Teslimat | DB Schenker Venlo | Planlandı |
| 3 | Boş iade | Kramer Distripark Depot | Planlandı |

13.09'da eski durum bildirimleri yapılmış (16:32 yola çıkış, 17:10 yükleme/terminal, ardından teslimat adımları). Uygulamada sefer "Teslim edildi" görünüyor, `SEFERİ BİTİR` kilitli ve şoför 2. durağa geçemiyor.

## 2. Nedeni

`src/DynamicONE.Infrastructure/Operations/DriverExecutionService.cs` → `StateAsync`:

```csharp
var legacy = !await db.Set<DriverExecutionReceipt>().AnyAsync(x => x.TripId == trip.Id, ct) &&
    await db.DriverTripStatusUpdates.AnyAsync(x => x.TransportTripId == trip.Id &&
        x.Status != DriverTripStatusType.Resting && x.Status != DriverTripStatusType.ProblemReported, ct);
```

- Seferde hiç `DriverExecutionReceipt` yoksa ve Resting/ProblemReported dışında eski durum bildirimi varsa `workflow = LegacyStatuses` döner ve `availableActions` boştur.
- Eski akışın sabit sırası: `Departed → ArrivedAtLoading → LoadingCompleted → ArrivedAtDelivery → DeliveryCompleted`. Durakları tanımaz, "seferi bitir" adımı yoktur.
- `DeliveryCompleted` bildirildikten sonra şoförün yapabileceği ilerleme adımı kalmaz. Durak sayısı ikiden fazlaysa veya duraklar tamamlanmamışsa sefer kilitlenir.

## 3. Önerilen davranış

**Eski akış bittiğinde sefer durak bazlı akışa geçsin:**

- `legacy` yalnızca eski akış henüz bitmemişken `true` olsun.
- Aşağıdaki koşulların hepsi sağlanıyorsa `workflow = TripActions` dönsün ve mevcut kurallarla `CompleteStop` seçenekleri sunulsun:
  - Seferde `DeliveryCompleted` durum bildirimi var.
  - Tamamlanmamış (Completed/Skipped olmayan) durak var.
  - Sefer `InProgress` durumunda ve atama `Active`.
- Sıradaki durak, sıra numarasına göre ilk tamamlanmamış duraktır. Konteyner birimleri, yük durumu etiketleri, `Rest` ve `Problem` bugünkü `TripActions` kurallarıyla aynı hesaplanır.
- İlk `DriverExecutionReceipt` oluştuktan sonra sefer zaten `TripActions` kalır (mevcut kuralın ilk koşulu).
- Geçişten sonra `POST trips/{tripId}/statuses` ile eski ilerleme adımı kabul edilmez (Resting/ProblemReported hariç). Aynı seferde iki akış karışmaz.
- Sefer ve durakların son tamamlanma davranışı mevcut `TripActions` kurallarıyla aynıdır.

**Dokunulmayanlar:** Eski durum geçmişi silinmez veya değiştirilmez. Eski akışı henüz bitmemiş seferler bugünkü gibi eski akışta devam eder. Yeni endpoint yoktur.

**Mobil uygulama:** Her okumada `workflow` alanını kullanır. `LegacyStatuses` → `TripActions` geçişinde eski durum geçmişini bırakıp sunucunun `availableActions` listesini gösterir; değişiklik gerekmez.

## 4. Alternatif (yalnızca operasyon)

Kokpitte planlamacıya "durak bazlı akışa geçir" veya "kalan durakları tamamla / seferi kapat" işlemi. Bu, şoförün sahada devam etmesini sağlamaz. 3. bölümdeki otomatik geçiş tercih edilir.

## 5. Kabul kontrolleri

- DT-2026-000012-01 için `GET execution` → `workflow = TripActions`. Seçenekler: 2. durak (Teslimat, DB Schenker Venlo) için TCNU2618740 konteynerine ait `CompleteStop`, ayrıca `Rest` ve `Problem`.
- 2. durak tamamlanınca sıradaki seçenek 3. durak (Boş iade) olur. Son durakta sefer mevcut kurallarla kapanır.
- `DeliveryCompleted` bildirilmemiş eski seferler `LegacyStatuses` kalır; sonraki eski adım kabul edilir.
- Geçişten sonra eski ilerleme durumu gönderimi reddedilir; durum geçmişi değişmez.
- Aynı `commandId` tekrarı ve `expectedVersion` uyuşmazlığı (409) mevcut kurallarla çalışır.
- .NET testleri: eski akışı bitmiş + tamamlanmamış duraklı sefer, eski akışı bitmemiş sefer, receipt'i olan sefer, iptal/tamamlanmış sefer.

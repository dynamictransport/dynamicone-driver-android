# Offline & Sync

## Neden kritik

Şoför:
- terminalde,
- limanda,
- sınır bölgesinde,
- otoparkta

zayıf internetle çalışabilir.

İnternet yokluğu operasyonu durdurmamalıdır.

## Local source of truth

UI mümkün olduğunca Room üzerinden state okur.
Remote veri local DB'ye yazılır.
UI doğrudan network response'a bağımlı olmamalıdır.

## PendingAction

Alanlar:

- id
- clientActionId UUID
- entityType
- entityId
- actionType
- payloadJson
- createdAt
- retryCount
- state
- lastError

State:
- PENDING
- SENDING
- FAILED_RETRYABLE
- FAILED_PERMANENT
- SYNCED

## Senkronizasyon

WorkManager:
- network available constraint
- unique worker
- exponential backoff
- app start trigger
- connectivity regained trigger
- manuel retry

## Media upload

Media binary'yi DB içine koyma.

Local file + DB metadata kullan.

Upload sonunda local media retention policy uygulanabilir.

## Görsel geri bildirim

Offline:
`Çevrimdışısınız — işlemler kaydediliyor`

Pending:
`3 işlem gönderilmeyi bekliyor`

Failure:
`1 evrak gönderilemedi — tekrar dene`

Şoförü teknik hata koduyla yorma.

## Conflict

Server authoritative.

Conflict durumunda:
- server state al
- local pending action'ı evaluate et
- geçersiz action ise kullanıcıya ve log/audit sistemine açık status oluştur
- veri sessizce kaybolmamalı

# DynamicONE Backend Integration

## Hedef

Driver app ayrı ürün, ancak veri sahibi DynamicONE backend'dir.

Önerilen sınır:

Android App
→ DynamicONE Driver API
→ DynamicONE Application Services
→ Azure SQL / Blob Storage / Integrations

Mobil uygulama Azure SQL'e doğrudan bağlanmaz.

## API alanları

### Auth
- login
- refresh
- logout
- me

### Shift
- current
- start
- end

### Trips
- list
- detail
- accept
- reject
- action/transition

### Break
- start
- end

### Documents
- list
- request upload
- upload/finalize
- delete/retry policy

### Earnings
- monthly summary
- detail

### Incident
- create
- media attachment

### Notifications
- device register
- message list
- read

## Push

FCM token backend'e kaydedilir.

Event örnekleri:
- TripAssigned
- TripChanged
- TripCancelled
- DocumentRejected
- PlannerMessage
- DriverDocumentExpiring

Push sadece tetikleyicidir.
Push payload'ı source of truth değildir.
Push sonrası API/local sync yapılır.

## Versioning

Base:
`/api/driver/v1`

Breaking change'de v2.

Mobile version header:
`X-App-Version`
`X-Platform: android`

## Audit

Her kritik driver action backend audit trail üretmeli:
- driver
- trip
- action
- server time
- client time
- device/session
- source
- clientActionId

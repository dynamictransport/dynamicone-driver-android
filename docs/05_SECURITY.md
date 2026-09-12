# Security

## Authentication

Öneri:
- username/password login
- kısa ömürlü access token
- refresh token
- server-side revocation
- device/session audit

## Device storage

Saklama:
- access token: memory / güvenli storage politikası
- refresh token: Android Keystore korumalı
- username: isteğe bağlı hatırlanabilir
- password: ASLA saklanmaz

## API

- HTTPS only
- auth header
- request correlation ID
- client version
- device ID (privacy-friendly installation id)
- idempotency/clientActionId

## Media

- doğrudan public blob URL verme
- short-lived signed upload URL veya API proxy
- blob private
- MIME/type validation
- file size limits
- malware scanning gerekiyorsa backend pipeline

## Logging

Loglama:
- event
- screen
- action type
- sanitized exception

Loglama yasak:
- password
- token
- full personal IDs
- document binary/base64
- sensitive document content

## Rooted device

İlk sürümde uygulamayı tamamen engellemek zorunlu değil.
Risk sinyali backend'e gönderilebilir.
Daha sonra Play Integrity değerlendirilebilir.

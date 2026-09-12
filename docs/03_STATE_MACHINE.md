# Trip State Machine

UI primary action backend tarafından mümkünse `nextAllowedActions` ile beslenir.

## Temel durumlar

| State | Açıklama | Primary action |
|---|---|---|
| ASSIGNED | Şoföre yeni atandı | ACCEPT_TRIP |
| ACCEPTED | Şoför kabul etti | START_TRIP |
| STARTED | Sefer başladı | ARRIVE_PICKUP |
| AT_PICKUP | Pickup/terminal noktasında | COMPLETE_PICKUP |
| PICKUP_COMPLETED | Pickup/yükleme tamamlandı | START_DELIVERY_LEG |
| EN_ROUTE_DELIVERY | Teslimata gidiyor | ARRIVE_DELIVERY |
| AT_DELIVERY | Teslimat noktasında | COMPLETE_DELIVERY |
| DELIVERY_COMPLETED | Teslimat tamamlandı | FINISH_TRIP |
| FINISHED | Sefer kapandı | none |
| CANCELLED | İptal | none |

## Paralel durum: Break

Break sefer state'ini değiştirmez.

`breakState`:
- NONE
- ON_BREAK

ON_BREAK ise normal trip primary action geçici olarak disabled/hidden olabilir ve:
`END_BREAK`
primary gösterilir.

## Sorun

Incident state sefer durumunu otomatik değiştirmez.
Planner gerekirse backend'de aksiyon alır.

## Kurallar

- Her transition timestamp içerir.
- Her transition `clientActionId` içerir.
- Aynı action tekrar gönderilirse duplicate oluşturmamalı.
- Client local state server state'den ileriye "tahmin" edilmemeli.
- Server illegal transition'a 409 dönmeli.
- Offline transition local `PENDING_SYNC` olarak gösterilir.

## Özel container akışları

İleride state machine backend konfigürasyonu ile genişleyebilir:
- EMPTY_DEPOT
- PORT_GATE_IN
- PORT_GATE_OUT
- CUSTOMER
- RETURN_EMPTY

UI bu durumları hard-code etmek yerine backend action descriptors ile desteklemeye hazırlanmalıdır.

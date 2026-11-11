# 29 — Device management (MDM) and remote screen sessions

Status: contract agreed 2026-11-11. Backend task: `device-management` in the DynamicONE repo.

## 1. Purpose

Company phones and tablets are managed from DynamicONE instead of Intune. The app is the
**device owner** of the phone. The office can see each device's state, send commands
(lock, ring, message, reboot, factory reset) and apply a small set of restrictions. With the
driver's approval the office can watch the screen and, when the accessibility service is on,
operate it remotely.

Decisions taken by the owner:

- The app is the device owner. Intune is removed from these devices.
- A remote session starts only after the driver accepts it on the phone. A permanent
  notification is visible for the whole session and the driver can stop it at any time.
- A device is managed even when nobody is signed in, so the device has its own credential.
  It never uses the driver's access token for MDM calls.

## 2. Device credential

All MDM calls go to `{API_BASE_URL}mdm/…` (`/api/driver/v1/mdm/…`) over HTTPS.

Enrolment returns `deviceId` and `deviceSecret`. Every later call sends

```
Authorization: Device {deviceId as 32 hex digits}.{deviceSecret}
```

The secret is stored with `KeystoreCryptoBox` like the session tokens. It survives sign-out.
A `401` with code `device_not_enrolled` means the device was retired on the server: delete
the credential, stop the check-in worker and clear the applied restrictions.

Errors are `application/problem+json` with a `code` extension, like the rest of the Driver API.

## 3. Endpoints

### 3.1 `POST mdm/enroll` (no credential)

```json
{
  "enrollmentCode": "K7Q2-M9XD",
  "installationId": "uuid (SessionPreferences.installationId())",
  "manufacturer": "HAMMER",
  "model": "Iron 5",
  "serialNumber": "optional, device owner only",
  "osVersion": "14",
  "sdkInt": 34,
  "appVersion": "0.3.0",
  "appVersionCode": 4,
  "isDeviceOwner": true
}
```

`200`:

```json
{ "deviceId": "uuid", "deviceSecret": "43 url-safe characters", "checkInSeconds": 900 }
```

`404 enrollment_code_invalid` for an unknown, used or expired code. `400 validation_failed`
for a malformed body. The code is 8 characters from `23456789ABCDEFGHJKLMNPQRSTUVWXYZ`; the
dash and letter case are ignored by the server.

Enrolling an `installationId` that is already enrolled replaces its secret (re-enrolment
after a reinstall).

### 3.2 `POST mdm/check-in`

Sent on enrolment, every `checkInSeconds` (WorkManager periodic, minimum 15 minutes), on app
start, on a `device.command` push, and after finishing a command.

```json
{
  "appVersion": "0.3.0",
  "appVersionCode": 4,
  "osVersion": "14",
  "sdkInt": 34,
  "securityPatch": "2026-08-05",
  "isDeviceOwner": true,
  "accessibilityEnabled": false,
  "batteryPercent": 81,
  "isCharging": false,
  "networkType": "wifi | cellular | none",
  "storageFreeMb": 41200,
  "storageTotalMb": 118000,
  "pushToken": "FCM token or null",
  "locale": "tr-TR",
  "policyVersionApplied": 3
}
```

`200`:

```json
{
  "checkInSeconds": 900,
  "policy": {
    "version": 3,
    "blockFactoryReset": true,
    "blockUnknownSources": true,
    "blockUsbFileTransfer": false,
    "blockSafeBoot": true,
    "blockAddUser": true
  },
  "commands": [
    { "id": "uuid", "type": "ring", "payload": { }, "expiresAt": "2026-11-11T12:00:00Z" }
  ]
}
```

A command is returned until its result is posted or it expires. Execute each `id` once
(keep the handled ids locally).

### 3.3 `POST mdm/commands/{id}/result`

```json
{ "status": "succeeded | failed | unsupported", "detail": "optional, max 500 characters" }
```

`204`. `404 command_not_found` when the command is not this device's. Posting twice is harmless.

### 3.4 Command types

| type | payload | device action | needs device owner |
|---|---|---|---|
| `sync` | — | nothing; the check-in itself is the answer | no |
| `ring` | `{ "seconds": 60 }` | play the alarm sound at full volume until dismissed or the time is up | no |
| `message` | `{ "title": "…", "body": "…" }` | high-priority notification with the text | no |
| `lock` | — | `DevicePolicyManager.lockNow()` | admin or owner |
| `reboot` | — | `DevicePolicyManager.reboot()` | yes |
| `wipe` | — | `DevicePolicyManager.wipeData(0)`; post `succeeded` first | yes |
| `remoteSession.request` | `{ "sessionId": "uuid", "requestedBy": "Erkan Barlaz", "expiresAt": "…" }` | full-screen consent prompt, see §4 | no |
| `remoteSession.end` | `{ "sessionId": "uuid" }` | stop the stream if it is this session | no |

Answer `unsupported` for an unknown type or when the device-owner requirement is not met.

### 3.5 Policy

Applied only when the app is device owner, through `addUserRestriction` /
`clearUserRestriction`:

| field | restriction |
|---|---|
| `blockFactoryReset` | `DISALLOW_FACTORY_RESET` |
| `blockUnknownSources` | `DISALLOW_INSTALL_UNKNOWN_SOURCES` |
| `blockUsbFileTransfer` | `DISALLOW_USB_FILE_TRANSFER` |
| `blockSafeBoot` | `DISALLOW_SAFE_BOOT` |
| `blockAddUser` | `DISALLOW_ADD_USER` |

The app also calls `setUninstallBlocked(admin, packageName, true)` on itself while enrolled.
Store the applied `version` and report it as `policyVersionApplied`.

### 3.6 Push

Data-only, high priority: `{ "type": "device.command" }`. The app answers with a check-in.
Nothing else is in the push.

## 4. Remote session

1. The office asks for a session. The device receives `remoteSession.request`.
2. The app shows a full-screen prompt (same mechanism as the wake-up call): who is asking,
   **Accept** / **Decline**. It expires at `expiresAt` (90 seconds).
3. Decline or timeout → `POST mdm/remote-sessions/{sessionId}/decline` (`204`), then post the
   command result `succeeded`.
4. Accept → the system MediaProjection dialog → a foreground service of type
   `mediaProjection` with a permanent notification "Ofis ekranınızı görüyor" and a **Stop**
   action → the WebSocket below.

### 4.1 `GET mdm/remote-sessions/{sessionId}/stream` (WebSocket, device credential header)

Device → server:

- text `{"t":"hello","w":720,"h":1600,"control":true}` first. `w`/`h` are the pixel size of
  the frames. `control` is true when the accessibility service is enabled.
- binary: one complete JPEG per message. Start at about 5 frames per second, longest side
  960 px, quality 50. Skip a frame when the previous send has not finished.
- text `{"t":"rotate","w":1600,"h":720}` when the size changes.
- text `{"t":"bye","reason":"driver_stopped | projection_lost | error"}` before closing.

Server → device (text), coordinates are fractions 0..1 of the frame:

- `{"t":"tap","x":0.5,"y":0.31}`
- `{"t":"long","x":0.5,"y":0.31}`
- `{"t":"swipe","x1":0.5,"y1":0.8,"x2":0.5,"y2":0.2,"ms":300}`
- `{"t":"key","k":"back | home | recents | notifications"}`
- `{"t":"text","v":"typed text"}` — set on the focused editable node
- `{"t":"quality","fps":5,"maxSide":960,"q":50}`
- `{"t":"end"}` — the office closed the session

Input messages are ignored when the accessibility service is off. The socket closes with
`4401` for a bad credential and `4404` when the session is not this device's or is over.

### 4.2 Accessibility service

Gestures use `AccessibilityService.dispatchGesture`, keys use `performGlobalAction`. The
service is off by default. It is turned on once per device: by hand in Settings, or by the
app itself when it holds `WRITE_SECURE_SETTINGS` (granted over adb on the test device:
`adb shell pm grant com.dynamictransport.dynamicone android.permission.WRITE_SECURE_SETTINGS`).
The service does nothing outside an accepted session.

## 5. Not in this phase

Silent app updates (APK hosting + `PackageInstaller`), QR provisioning of a factory-reset
phone, location, kiosk mode, password reset. The test phone is made device owner with
`adb shell dpm set-device-owner com.dynamictransport.dynamicone/.core.mdm.DeviceOwnerReceiver`.

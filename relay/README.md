# MCC Push Relay

Forwards notifications from MCC controllers to Apple Push Notification service (APNs).

APNs only accepts notifications signed with the app developer's `.p8` key, which can't be put in
controller firmware. Controllers post to this relay with a username and password, and the relay signs
the request and passes it on. It's stateless: controllers send the phone's device token with every
notification, and nothing is stored apart from the users file.

See the main [README](../README.md#notifications) for the controller side.

## API

### `POST /v1/push`

Requires `Authorization: Basic …` and `Content-Type: application/json`.

```json
{
  "deviceToken": "9f86d081…",
  "environment": "production",
  "title": "Heating Monitor",
  "body": "Flow temperature is above 70°C"
}
```

| Status | Meaning |
|--------|---------|
| `202` | Accepted by APNs. |
| `400` | Invalid body. `{"error": "…"}` says which field. |
| `401` | Missing or wrong credentials. |
| `410` | APNs says the token is dead (`Unregistered`, `BadDeviceToken`, `ExpiredToken`). The controller should forget it. |
| `502` | APNs refused the push for another reason (given in `error`), or couldn't be reached. |

### `GET /healthz`

Returns `{"ok": true}`.

## Setup

1. In the Apple Developer portal, create a key with the **Apple Push Notifications service** enabled
   and download the `.p8`. Note its Key ID and your Team ID. One key works for both sandbox and
   production.
2. Copy `.env.example` to `.env` and fill it in. `APNS_TOPIC` is the app's bundle id.
3. Add a user for each controller (or one for all of them):

   ```sh
   npm install
   npm run add-user -- heating-monitor
   ```

   This prompts for the password and writes a scrypt hash to `USERS_FILE`. The file is re-read on
   every request, so new users work without a restart. Enter the same username and password in the
   app when you add or edit the controller.

4. Run it:

   ```sh
   npm run dev      # local, reloads on change
   # or
   npm run build && npm start
   ```

### Docker

```sh
docker build -t mcc-push-relay .
docker run -d --name mcc-push-relay -p 8080:8080 \
  -e APNS_KEY_PATH=/secrets/AuthKey.p8 -e APNS_KEY_ID=… -e APNS_TEAM_ID=… \
  -e APNS_TOPIC=com.tomasmcguinness.matter-controller-companion-v1 \
  -e USERS_FILE=/secrets/users.json \
  -v "$PWD/secrets:/secrets:ro" \
  mcc-push-relay
```

To add users to a running container, run `npm run add-user` on the host against the mounted file.

### TLS

The relay serves plain HTTP. Put it behind a reverse proxy (e.g. Caddy or nginx) with a certificate
from a public CA. Basic auth needs TLS to be safe, and ESP32 firmware using `esp_crt_bundle_attach`
only trusts public CAs.

Set the app's relay address in `iOS/MCC/Notifications/PushRelay.swift`.

## Tests

```sh
npm test
```

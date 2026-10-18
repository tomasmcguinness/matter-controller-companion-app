# MCC Push Relay

Forwards notifications from MCC controllers to Apple Push Notification service (APNs) for iPhones and
Firebase Cloud Messaging (FCM) for Android phones.

APNs only accepts notifications signed with the app developer's `.p8` key, and FCM only accepts them
from the Firebase project's service account. Neither can be put in controller firmware. Controllers post to this relay with a username and password, and the relay signs
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
| `202` | Accepted by APNs or FCM. |
| `400` | Invalid body. `{"error": "…"}` says which field. Also returned when the relay isn't set up for that platform. |
| `401` | Missing or wrong credentials. |
| `410` | APNs or FCM says the token is dead (APNs: `Unregistered`, `BadDeviceToken`, `ExpiredToken`; FCM: `UNREGISTERED`, `INVALID_ARGUMENT`). The controller should forget it. |
| `502` | APNs or FCM refused the push for another reason (given in `error`), or couldn't be reached. |

`environment` picks the service: `sandbox` or `production` go to APNs, and `fcm` goes to FCM. APNs
tokens must be hex; FCM tokens may contain letters, digits, `:`, `_` and `-`.

### `GET /healthz`

Returns `{"ok": true}`.

## Setup

Set up APNs, FCM, or both. A relay with only one serves only that platform.

1. For iPhones: in the Apple Developer portal, create a key with the **Apple Push Notifications
   service** enabled and download the `.p8`. Note its Key ID and your Team ID. One key works for both
   sandbox and production.

   For Android phones: in the Firebase console, open the project the Android app's
   `google-services.json` came from, go to **Project settings › Service accounts**, and generate a
   new private key. Save the JSON file next to the relay.
2. Copy `.env.example` to `.env` and fill it in. `APNS_TOPIC` is the iOS app's bundle id, and
   `FCM_SERVICE_ACCOUNT_PATH` is the service account file. Leave out the variables for a platform you
   don't use.
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
  -e FCM_SERVICE_ACCOUNT_PATH=/secrets/firebase-service-account.json \
  -e USERS_FILE=/secrets/users.json \
  -v "$PWD/secrets:/secrets:ro" \
  mcc-push-relay
```

To add users to a running container, run `npm run add-user` on the host against the mounted file.

### TLS

The relay serves plain HTTP. Put it behind a reverse proxy (e.g. Caddy or nginx) with a certificate
from a public CA. Basic auth needs TLS to be safe, and ESP32 firmware using `esp_crt_bundle_attach`
only trusts public CAs.

Set the app's relay address in `iOS/MCC/Notifications/PushRelay.swift` and in `PUSH_RELAY_URL` in
`android/app/build.gradle.kts`.

## Tests

```sh
npm test
```

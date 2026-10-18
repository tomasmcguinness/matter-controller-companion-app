# MCC — Matter Controller Companion

MCC is an iOS and Android app for adding Matter devices to a Matter controller (hub) you run
yourself, and for seeing what's on it. You add one or more controllers by address, then use the
system Matter setup flow to scan a device's QR code and commission it onto the controller you picked.

This project exists to make it easy to commission devices into some of my other projects.
- [Heating Monitor](https://github.com/tomasmcguinness/matter-esp32-heating-monitor)
- [Matter Controller](https://github.com/tomasmcguinness/matter-esp32-controller)
- [Home Energy Manager](https://github.com/tomasmcguinness/matter-esp32-home-energy-manager)

The repository also contains **`relay/`**, the small self-hosted service that lets controllers send
push notifications. See [Notifications](#notifications) and [`relay/README.md`](relay/README.md).

The controller does the actual commissioning; the app only talks to it over the REST API below.

### iOS (`iOS/`)

The project has two targets:

- **MCC** — the app. Lists controllers and their devices, and starts the system setup flow.
- **MCCExtension** — the Matter setup extension iOS launches during that flow. It receives the
  onboarding payload and forwards it to the controller.

The Xcode project is generated from `iOS/project.yml` with [XcodeGen](https://github.com/yonaskolb/XcodeGen):

```sh
cd iOS
xcodegen generate
```

### Android (`android/`)

A Kotlin and Jetpack Compose app with the same screens. Adding a device uses the Google Play Services
Matter API: Google's setup flow scans the code and puts the device on Wi-Fi or Thread, then calls the
app's `MccCommissioningService`, which asks the controller to commission it over the network (see the
[on-network variant](#post-apicompanionnodes) of `POST /api/companion/nodes`). Needs Android 8.1 or
later with Google Play Services.

Open `android/` in Android Studio to build and run it. To build from the command line you need JDK 17,
the Android SDK and Gradle 8.14 to generate the wrapper once:

```sh
cd android
gradle wrapper
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

For notifications, add the Firebase project's `google-services.json` to `android/app/` (it isn't
checked in). Without it the app still builds, but notifications can't be turned on. Set the relay
address in `PUSH_RELAY_URL` in `android/app/build.gradle.kts`.

> [!NOTE]
> Google's setup flow uses the phone's own Thread credentials. If your controller runs a Thread network
> the phone doesn't know about, Thread devices won't join. Wi-Fi devices aren't affected. To commission
> uncertified or test devices, turn on developer mode for Google Play Services or register the device's
> vendor and product ids in the [Google Home Developer Console](https://console.home.google.com/).

> [!CAUTION]
> Communication between the App and the API is completely *open and insecure*, so only use this in an environment you trust. I will be adding some sort of pairing process to provide secure communication. All Matter based communication is fully encrypted.

## Controller API

A controller has to implement these endpoints for MCC to work with it. This is exactly what the app
calls (see `iOS/Shared/MCCClient.swift` and `android/app/src/main/java/com/tomasmcguinness/mcc/data/MccClient.kt`)
and decodes (see `iOS/Shared/Models.swift`). The reference
implementation is the ESP32 Heating Monitor firmware (`nodes_get_handler` in `app_main.cpp`).

### Conventions

- The base URL is whatever address the user entered when adding the controller (`http` or `https`,
  typically a local network address such as `http://192.168.1.250`). Paths below are appended to it.
- Requests send `Accept: application/json`. Requests with a body also send
  `Content-Type: application/json`.
- There is **no authentication**. Every request is plain JSON on the local network.
- Any non-2xx status is treated as a failure. If the response has a body, its text is shown to the
  user, so make it readable.
- Client timeouts are 20 seconds, except `POST /api/companion/nodes`, which is 90 seconds.

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/api/companion/info` | Check the controller is reachable |
| `GET` | `/api/companion/nodes` | List commissioned nodes |
| `POST` | `/api/companion/nodes` | Commission a new node |
| `PUT` | `/api/companion/nodes/{nodeId}/update` | Rename a node |
| `DELETE` | `/api/companion/nodes/{nodeId}` | Remove (unpair) a node |
| `PUT` | `/api/companion/notifications` | Store where to send notifications (optional) |
| `DELETE` | `/api/companion/notifications` | Stop sending notifications (optional) |

`{nodeId}` is the node's numeric Matter node id, in decimal.

### `GET /api/companion/info`

Called right after the user types in an address, so a bad entry fails immediately. Any 2xx response
counts as success; the body is ignored.

### `GET /api/companion/nodes`

Returns a JSON array of nodes.

```json
[
  {
    "nodeId": 1,
    "isIcd": false,
    "vendorName": "Acme",
    "productName": "Temperature Sensor",
    "nodeName": "Hallway",
    "powerSource": 1,
    "batteryPercent": 87,
    "batteryVoltage": 2950,
    "extAddress": 1.2345678901234567e+18,
    "hasSubscription": true,
    "endpoints": [
      {
        "endpointId": 1,
        "endpointName": "Temperature",
        "powerSource": 1,
        "batteryPercent": 87,
        "batteryVoltage": 2950,
        "measuredValue": 2150,
        "deviceTypes": [770]
      }
    ]
  }
]
```

**Node**

| Field | Type | Notes |
|-------|------|-------|
| `nodeId` | integer | Required. See the number note below. |
| `extAddress` | integer | Thread extended address. See the number note below. |
| `isIcd` | boolean | Intermittently connected device. Defaults to `false`. |
| `vendorName` | string or null | Null until the node has answered a Basic Information read. |
| `productName` | string or null | Same as `vendorName`. |
| `nodeName` | string or null | Null until a name is set. Shown in preference to `productName`. |
| `powerSource` | integer | Defaults to `0`. |
| `batteryPercent` | integer or null | Whole percent, 0–100. Omit if the node doesn't report it. |
| `batteryVoltage` | integer or null | Millivolts. Omit if the node doesn't report it. |
| `hasSubscription` | boolean | Defaults to `false`. |
| `endpoints` | array | Defaults to empty. |

**Endpoint**

| Field | Type | Notes |
|-------|------|-------|
| `endpointId` | integer | Defaults to `0`. |
| `endpointName` | string or null | |
| `powerSource` | integer | Defaults to `0`. |
| `batteryPercent` | integer or null | Whole percent, 0–100. |
| `batteryVoltage` | integer or null | Millivolts. |
| `measuredValue` | number or null | In hundredths, the way the Matter clusters carry it, so `2150` shows as `21.50`. |
| `deviceTypes` | array of integers | Matter device type ids, e.g. `770` for Temperature Sensor. Defaults to empty. |

Every field other than `nodeId` may be omitted. Missing values fall back to the defaults above rather
than failing the whole response.

**Number note:** `nodeId` and `extAddress` are 64-bit values. The app accepts them as an integer, as a
float in scientific notation (which is how cJSON prints numbers that don't fit an `int`), or as a
decimal string. A value that can't be parsed decodes as `0`.

### `POST /api/companion/nodes`

Commissions a device onto the controller's fabric. The body comes in one of two forms.

**From a setup code (iOS).** The controller does everything, including BLE and network provisioning.

```json
{
  "inUse": false,
  "setupCode": "MT:Y.K9042C00KA0648G00"
}
```

| Field | Type | Notes |
|-------|------|-------|
| `inUse` | boolean | `false`: the iOS setup flow only hands over devices that aren't on a fabric yet. |
| `setupCode` | string | The onboarding payload iOS decoded from the QR code, or the manual pairing code. |

**On the network (Android).** Google Play Services has already put the device on Wi-Fi or Thread and
opened a commissioning window, and it gives the app the device's address and passcode, not the setup
code. The controller should establish PASE with the device at that address (with the CHIP SDK,
`PairDevice(nodeId, RendezvousParameters().SetPeerAddress(...).SetSetupPINCode(passcode))`), then
commission it as usual.

```json
{
  "inUse": true,
  "passcode": 20202021,
  "ipAddress": "fd12:3456:789a:1::5",
  "port": 5540
}
```

| Field | Type | Notes |
|-------|------|-------|
| `inUse` | boolean | `true`: the device is already on the network. |
| `passcode` | integer | The setup passcode for the open commissioning window. |
| `ipAddress` | string | The device's IPv6 or IPv4 address, without brackets or a scope id. |
| `port` | integer | The device's Matter port, usually `5540`. |

A controller that doesn't support this form should return `400` with a readable message. The Android
app can't add devices to it.

Response (2xx):

```json
{ "nodeId": 5 }
```

Both forms get the same response and timing. The controller should hold the request open until commissioning finishes, so a 2xx means the device
really did join the fabric. The app waits up to 90 seconds. If the controller gives up first it should
return an error status (the reference implementation returns `504`), which the app reports as a failed
setup.

### `PUT /api/companion/nodes/{nodeId}/update`

Sets the node's name. The app calls this after commissioning, with the name the user typed into the
system setup sheet, so the device has the same name in the app.

Request:

```json
{ "name": "Hallway" }
```

The response body is ignored. A failure is logged but doesn't fail setup, because the device is
already commissioned.

### `DELETE /api/companion/nodes/{nodeId}`

Removes the node from the controller. No request body; the response body is ignored.

## Notifications

Controllers can send push notifications to the phone. APNs and FCM need the app's credentials, which
can't ship in firmware, so controllers post to a small relay ([`relay/`](relay/README.md)) that holds
them and forwards to Apple (iPhone) or Google (Android):

```
App ──PUT /api/companion/notifications──▶ controller (stores in NVS)
controller ──POST {relayUrl}/v1/push (Basic auth)──▶ relay ──▶ APNs or FCM ──▶ phone
```

The user gives each controller a relay username and password when adding or editing it, turns
notifications on in the app's Settings, and then taps each controller that should send notifications.
A tick shows which ones are in use. The app sends a ticked controller everything it needs. Both
endpoints are optional: a controller that answers `404` is shown as not supporting notifications.

> [!NOTE]
> Like the rest of this API, these requests are unauthenticated plain HTTP on the local network, so the
> relay password and device token are visible to anything on that network.

### `PUT /api/companion/notifications`

Sent when the user ticks the controller in Settings, and again for ticked controllers when the
phone's device token changes or the controller's address or relay credentials are edited. Store every field (e.g. in NVS), replacing anything stored
before, and respond `204`.

```json
{
  "relayUrl": "https://push.example.com",
  "username": "heating-monitor",
  "password": "…",
  "deviceToken": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
  "environment": "production"
}
```

| Field | Type | Notes |
|-------|------|-------|
| `relayUrl` | string | Base URL of the relay. `/v1/push` is appended to it. |
| `username` | string | Relay username for this controller. |
| `password` | string | Relay password for this controller. |
| `deviceToken` | string | The phone's token, opaque to the controller: an APNs device token (64 hex characters) from iOS, or an FCM registration token (about 160 characters, not hex) from Android. Allow at least 256 bytes. Pass it back unchanged. |
| `environment` | string | `sandbox` (iOS debug builds), `production` (iOS release builds) or `fcm` (Android). Pass it back unchanged. |

### `DELETE /api/companion/notifications`

Sent when the user unticks the controller, turns notifications off, or removes the controller's
relay credentials in the app. Erase the stored values and respond `204`.

### Sending a notification

`POST {relayUrl}/v1/push` with HTTP Basic auth and a JSON body:

```json
{
  "deviceToken": "9f86d081…",
  "environment": "production",
  "title": "Heating Monitor",
  "body": "Flow temperature is above 70°C"
}
```

| Status | Meaning | What the controller should do |
|--------|---------|-------------------------------|
| `202` | Sent to Apple or Google. | Nothing. |
| `400` | The body was invalid; the response says why. | Fix the request. |
| `401` | Wrong username or password. | Stop sending until the app sends new credentials. |
| `410` | The phone no longer accepts notifications (the app was deleted, or the token is dead). | Erase the stored token. |
| `502` | APNs or FCM refused or couldn't be reached; the response has their reason. | Retry later. |

With ESP-IDF this is a few lines of `esp_http_client`. The relay must have a certificate from a public
CA so the built-in certificate bundle trusts it:

```c
#include "esp_http_client.h"
#include "esp_crt_bundle.h"

// relay_url, username, password, device_token and environment are the values stored from
// PUT /api/companion/notifications.
static esp_err_t send_notification(const char *title, const char *body)
{
    char url[256];
    snprintf(url, sizeof(url), "%s/v1/push", relay_url);

    esp_http_client_config_t config = {
        .url = url,
        .method = HTTP_METHOD_POST,
        .username = username,
        .password = password,
        .auth_type = HTTP_AUTH_TYPE_BASIC,
        .crt_bundle_attach = esp_crt_bundle_attach,
        .timeout_ms = 10000,
    };

    cJSON *json = cJSON_CreateObject();
    cJSON_AddStringToObject(json, "deviceToken", device_token);
    cJSON_AddStringToObject(json, "environment", environment);
    cJSON_AddStringToObject(json, "title", title);
    cJSON_AddStringToObject(json, "body", body);
    char *payload = cJSON_PrintUnformatted(json);

    esp_http_client_handle_t client = esp_http_client_init(&config);
    esp_http_client_set_header(client, "Content-Type", "application/json");
    esp_http_client_set_post_field(client, payload, strlen(payload));

    esp_err_t err = esp_http_client_perform(client);
    int status = esp_http_client_get_status_code(client);

    if (err == ESP_OK && status == 410) {
        // The phone is gone; forget the token so we stop trying.
        erase_notification_target();
    }

    esp_http_client_cleanup(client);
    cJSON_free(payload);
    cJSON_Delete(json);

    return (err == ESP_OK && status == 202) ? ESP_OK : ESP_FAIL;
}
```

Enable `CONFIG_MBEDTLS_CERTIFICATE_BUNDLE` (on by default) for `esp_crt_bundle_attach`.

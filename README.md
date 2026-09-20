# MCC — Matter Controller Companion

MCC is an iOS app for adding Matter devices to a Matter controller (hub) you run yourself, and for
seeing what's on it. You add one or more controllers by address, then use the system Matter setup
sheet to scan a device's QR code and commission it onto the controller you picked.

The project has two targets:

- **MCC** — the app. Lists controllers and their devices, and starts the system setup flow.
- **MCCExtension** — the Matter setup extension iOS launches during that flow. It receives the
  onboarding payload and forwards it to the controller.

The controller does the actual commissioning; the app only talks to it over the REST API below.

The Xcode project is generated from `iOS/project.yml` with [XcodeGen](https://github.com/yonaskolb/XcodeGen):

```sh
cd iOS
xcodegen generate
```

## Controller API

A controller has to implement these endpoints for MCC to work with it. This is exactly what the app
calls (see `iOS/Shared/MCCClient.swift`) and decodes (see `iOS/Shared/Models.swift`). The reference
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

Commissions a device onto the controller's fabric.

Request:

```json
{
  "inUse": false,
  "setupCode": "MT:Y.K9042C00KA0648G00"
}
```

| Field | Type | Notes |
|-------|------|-------|
| `inUse` | boolean | Always `false` from this app: the system setup flow only hands over devices that aren't on a fabric yet. |
| `setupCode` | string | The onboarding payload iOS decoded from the QR code, or the manual pairing code. |

Response (2xx):

```json
{ "nodeId": 5 }
```

The controller should hold the request open until commissioning finishes, so a 2xx means the device
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

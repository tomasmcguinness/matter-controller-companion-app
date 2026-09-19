import Foundation

/// A Matter node commissioned onto a controller's fabric.
///
/// Mirrors the JSON built by the controller's `nodes_get_handler` (see the ESP32 Heating
/// Monitor firmware's `app_main.cpp` for the reference implementation this API is based on).
struct Node: Codable, Identifiable, Hashable, Sendable {

    let nodeId: UInt64
    let isIcd: Bool
    let vendorName: String?
    let productName: String?
    let nodeName: String?
    let powerSource: Int
    let batteryPercent: Int?
    let batteryVoltage: Int?
    let extAddress: UInt64
    let hasSubscription: Bool
    let endpoints: [Endpoint]

    var id: UInt64 { nodeId }

    /// The node's battery, if it is on one and has reported since the controller last booted.
    var battery: BatteryReading? {
        BatteryReading(percent: batteryPercent, voltage: batteryVoltage)
    }

    /// What to show in a list. The controller leaves `nodeName` null until the user sets one,
    /// and `productName` null until the node has answered a Basic Information read.
    var displayName: String {
        if let nodeName, !nodeName.isEmpty { return nodeName }
        if let productName, !productName.isEmpty { return productName }
        return "Node \(nodeIdHex)"
    }

    var nodeIdHex: String {
        "0x" + String(nodeId, radix: 16, uppercase: true)
    }

    var subtitle: String? {
        let parts = [vendorName, productName].compactMap { $0 }.filter { !$0.isEmpty }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)

        // cJSON prints anything that doesn't fit an int in scientific notation, which a plain
        // UInt64 decode chokes on -- Thread extended addresses always land in that range.
        nodeId = try container.decodeLenientUInt64(forKey: .nodeId)
        extAddress = try container.decodeLenientUInt64(forKey: .extAddress)

        isIcd = try container.decodeIfPresent(Bool.self, forKey: .isIcd) ?? false
        vendorName = try container.decodeIfPresent(String.self, forKey: .vendorName)
        productName = try container.decodeIfPresent(String.self, forKey: .productName)
        nodeName = try container.decodeIfPresent(String.self, forKey: .nodeName)
        powerSource = try container.decodeIfPresent(Int.self, forKey: .powerSource) ?? 0
        batteryPercent = try container.decodeIfPresent(Int.self, forKey: .batteryPercent)
        batteryVoltage = try container.decodeIfPresent(Int.self, forKey: .batteryVoltage)
        hasSubscription = try container.decodeIfPresent(Bool.self, forKey: .hasSubscription) ?? false
        endpoints = try container.decodeIfPresent([Endpoint].self, forKey: .endpoints) ?? []
    }
}

struct Endpoint: Codable, Identifiable, Hashable, Sendable {

    let endpointId: Int
    let endpointName: String?
    let powerSource: Int
    let batteryPercent: Int?
    let batteryVoltage: Int?
    let measuredValue: Double?
    let deviceTypes: [UInt32]

    var id: Int { endpointId }

    var battery: BatteryReading? {
        BatteryReading(percent: batteryPercent, voltage: batteryVoltage)
    }

    /// The controller reports temperatures and flow rates in hundredths, the way the Matter
    /// clusters carry them.
    var scaledValue: Double? {
        measuredValue.map { $0 / 100 }
    }

    var deviceTypeNames: [String] {
        deviceTypes.map { MatterDeviceType.name(for: $0) }
    }

    init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)

        endpointId = try container.decodeIfPresent(Int.self, forKey: .endpointId) ?? 0
        endpointName = try container.decodeIfPresent(String.self, forKey: .endpointName)
        powerSource = try container.decodeIfPresent(Int.self, forKey: .powerSource) ?? 0
        batteryPercent = try container.decodeIfPresent(Int.self, forKey: .batteryPercent)
        batteryVoltage = try container.decodeIfPresent(Int.self, forKey: .batteryVoltage)
        measuredValue = try container.decodeIfPresent(Double.self, forKey: .measuredValue)
        deviceTypes = try container.decodeIfPresent([UInt32].self, forKey: .deviceTypes) ?? []
    }
}

/// A battery powered node's PowerSource state.
///
/// `BatPercentRemaining` and `BatVoltage` are both optional in Matter, so a device may report
/// either, both, or neither.
struct BatteryReading: Hashable, Sendable, CustomStringConvertible {

    /// Whole percent, 0-100.
    let percent: Int?

    /// Millivolts, as the cluster reports it.
    let millivolts: Int?

    /// Fails when there is nothing to show.
    init?(percent: Int?, voltage: Int?) {
        guard percent != nil || voltage != nil else {
            return nil
        }

        self.percent = percent.map { min(max($0, 0), 100) }
        self.millivolts = voltage
    }

    var description: String {
        var parts: [String] = []

        if let percent {
            parts.append("\(percent)%")
        }

        if let millivolts {
            parts.append(String(format: "%.2f V", Double(millivolts) / 1000))
        }

        return parts.joined(separator: " · ")
    }

    var symbolName: String {
        guard let percent else {
            return "battery.100percent"
        }

        switch percent {
        case ...15: return "battery.0percent"
        case ...37: return "battery.25percent"
        case ...62: return "battery.50percent"
        case ...87: return "battery.75percent"
        default: return "battery.100percent"
        }
    }

    enum Level: Sendable {
        case unknown, low, warning, good
    }

    var level: Level {
        guard let percent else {
            return .unknown
        }

        switch percent {
        case ...15: return .low
        case ...30: return .warning
        default: return .good
        }
    }
}

/// The subset of Matter device type ids this system runs into.
enum MatterDeviceType {

    static func name(for id: UInt32) -> String {
        switch id {
        case 14: return "Aggregator"
        case 15: return "Generic Switch"
        case 17: return "Power Source"
        case 19: return "Bridged Device"
        case 117: return "Dishwasher"
        case 266: return "On/Off Plug-in Unit"
        case 269: return "Extended Color Light"
        case 769: return "Thermostat"
        case 770: return "Temperature Sensor"
        case 773: return "Pressure Sensor"
        case 774: return "Flow Sensor"
        case 775: return "Humidity Sensor"
        case 777: return "Heat Pump"
        case 1293: return "Device Energy Manager"
        case 1296: return "Electrical Sensor"
        case 0xFFF1_0001: return "Heat Meter"
        default: return "Type \(id)"
        }
    }
}

// MARK: - Request and response bodies

struct CommissionRequest: Encodable, Sendable {
    /// Always false from this app: MatterSupport only ever hands us devices that aren't yet
    /// on a fabric.
    let inUse: Bool
    let setupCode: String
}

struct CommissionResponse: Decodable, Sendable {
    let nodeId: UInt64

    init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        nodeId = try container.decodeLenientUInt64(forKey: .nodeId)
    }

    enum CodingKeys: String, CodingKey {
        case nodeId
    }
}

struct RenameRequest: Encodable, Sendable {
    let name: String
}

// MARK: - Lenient number decoding

extension KeyedDecodingContainer {

    /// Decodes a 64-bit id that the controller may have emitted as an integer, as a float in
    /// scientific notation, or as a string. Missing or unparseable values decode as 0 rather
    /// than failing the whole response -- these are display fields, not something worth
    /// dropping a device list over.
    func decodeLenientUInt64(forKey key: Key) throws -> UInt64 {
        if let value = try? decodeIfPresent(UInt64.self, forKey: key) {
            return value
        }

        if let value = try? decodeIfPresent(Double.self, forKey: key), value >= 0,
           value < Double(UInt64.max) {
            return UInt64(value)
        }

        if let string = try? decodeIfPresent(String.self, forKey: key), let value = UInt64(string) {
            return value
        }

        return 0
    }
}

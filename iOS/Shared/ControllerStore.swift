import Foundation

/// Where MCC's controllers live, plus the handoff state used to hand a commissioning request
/// from the main app to the Matter extension and back.
///
/// The app and the Matter extension are separate processes, so this has to be shared storage
/// rather than in-memory state: the extension is launched by the system to handle a
/// commissioning request and has no other way to learn which controller it's for.
enum ControllerStore {

    static let appGroup = "group.com.tomasmcguinness.matter-controller-companion"

    private static let controllersKey = "controllers"
    private static let pendingCommissioningControllerIdKey = "pendingCommissioningControllerId"
    private static let lastCommissionedNodeIdKey = "lastCommissionedNodeId"

    private static var defaults: UserDefaults? {
        UserDefaults(suiteName: appGroup)
    }

    // MARK: - Controllers

    static var controllers: [Controller] {
        get {
            guard let data = defaults?.data(forKey: controllersKey),
                  let controllers = try? JSONDecoder().decode([Controller].self, from: data) else {
                return []
            }

            return controllers
        }
        set {
            guard let data = try? JSONEncoder().encode(newValue) else { return }
            defaults?.set(data, forKey: controllersKey)
        }
    }

    static func add(_ controller: Controller) throws {
        guard defaults != nil else {
            throw ControllerStoreError.appGroupUnavailable
        }

        var current = controllers
        current.append(controller)
        controllers = current
    }

    static func remove(id: UUID) {
        controllers.removeAll { $0.id == id }
    }

    static func controller(id: UUID) -> Controller? {
        controllers.first { $0.id == id }
    }

    // MARK: - Commissioning handoff

    /// Written by the main app right before it starts a commissioning attempt on a given
    /// controller; read by the extension so it knows which controller's API to talk to.
    static var pendingCommissioningControllerID: UUID? {
        get {
            guard let string = defaults?.string(forKey: pendingCommissioningControllerIdKey) else {
                return nil
            }

            return UUID(uuidString: string)
        }
        set {
            guard let defaults else { return }

            if let newValue {
                defaults.set(newValue.uuidString, forKey: pendingCommissioningControllerIdKey)
            } else {
                defaults.removeObject(forKey: pendingCommissioningControllerIdKey)
            }
        }
    }

    /// Written by the extension when the controller accepts a commissioning request, read by
    /// the app so it knows which node to wait for. Without this the app has no way to learn
    /// the node id -- `MatterAddDeviceRequest.perform()` returns nothing.
    ///
    /// Stored as a decimal string rather than `Int` -- node ids are unsigned 64-bit values and
    /// can have the high bit set, which a plain `Int(UInt64)` conversion traps on.
    static var lastCommissionedNodeId: UInt64? {
        get {
            guard let string = defaults?.string(forKey: lastCommissionedNodeIdKey) else {
                return nil
            }

            return UInt64(string)
        }
        set {
            guard let defaults else { return }

            if let newValue {
                defaults.set(String(newValue), forKey: lastCommissionedNodeIdKey)
            } else {
                defaults.removeObject(forKey: lastCommissionedNodeIdKey)
            }
        }
    }
}

enum ControllerStoreError: LocalizedError {
    case appGroupUnavailable

    var errorDescription: String? {
        switch self {
        case .appGroupUnavailable:
            return "The app group isn't configured, so the controller can't be saved."
        }
    }
}

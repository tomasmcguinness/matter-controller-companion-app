import Foundation
import MatterSupport
import os

/// Handles the commissioning half of the system Matter setup flow.
///
/// iOS launches this out of process when the user finishes picking a device in the sheet that
/// `MatterAddDeviceRequest.perform()` presented. By then the OS has already scanned the QR
/// code (or taken a manual pairing code) and it hands us the decoded onboarding payload.
///
/// We are not the Matter controller -- whichever controller the user picked from Home is.
/// All this does is forward the payload to that controller's `POST /api/companion/nodes`, which pairs
/// the device onto its fabric. That call blocks until commissioning finishes, so what it
/// answers with is the real outcome.
///
/// This process has no idea which controller the request is for -- the main app records that
/// in `ControllerStore.pendingCommissioningControllerID` right before starting the system
/// sheet, and `MCCClient.forPendingCommissioningTarget()` reads it back here.
final class RequestHandler: MatterAddDeviceExtensionRequestHandler {

    private let logger = Logger(subsystem: "com.tomasmcguinness.matter-controller-companion",
                                category: "Commissioning")

    override func commissionDevice(in home: MatterAddDeviceRequest.Home?,
                                   onboardingPayload: String,
                                   commissioningID: UUID) async throws {
        logger.info("Commissioning \(commissioningID, privacy: .public)")

        let client = try MCCClient.forPendingCommissioningTarget()

        do {
            // Blocks for as long as commissioning takes -- up to about 90 seconds.
            let nodeId = try await client.commission(setupCode: onboardingPayload)

            // The app has no other way to learn this: perform() returns nothing, and the
            // extension can't call back into the app. It reads this out of the App Group to
            // work out which node to wait for.
            ControllerStore.lastCommissionedNodeId = nodeId

            logger.info("Controller commissioned node \(nodeId, privacy: .public)")
        } catch {
            // Let this propagate so the system sheet reports a failure rather than claiming
            // the device was added.
            logger.error("Commissioning failed: \(error.localizedDescription, privacy: .public)")
            throw error
        }
    }

    /// iOS puts the device on its Thread network before `commissionDevice` is ever called, so
    /// the controller only pairs it once it's already on the network. This is where we say
    /// which network that is.
    ///
    /// Controllers can name one (Edit Controller → Thread Network) whose credentials MCC
    /// stored on the phone; otherwise the system's preferred network is used. A chosen network
    /// is returned even if the device's scan didn't see it, so a wrong setup fails visibly
    /// rather than quietly landing the device on the preferred network.
    override func selectThreadNetwork(from threadScanResults: [MatterAddDeviceExtensionRequestHandler.ThreadScanResult]) async throws -> MatterAddDeviceExtensionRequestHandler.ThreadNetworkAssociation {
        guard let controllerID = ControllerStore.pendingCommissioningControllerID,
              let controller = ControllerStore.controller(id: controllerID),
              let extendedPANIDBytes = controller.threadExtendedPANID else {
            logger.info("No Thread network chosen; using the system default")
            return .defaultSystemNetwork
        }

        // Stored as the 8 bytes ThreadNetwork reports; MatterSupport wants them as a
        // big-endian integer.
        guard extendedPANIDBytes.count == 8 else {
            logger.error("Stored extended PAN ID is \(extendedPANIDBytes.count) bytes, not 8; using the system default")
            return .defaultSystemNetwork
        }

        let extendedPANID = extendedPANIDBytes.reduce(UInt64(0)) { $0 << 8 | UInt64($1) }
        let xpanid = String(format: "%016llx", extendedPANID)

        if threadScanResults.contains(where: { $0.extendedPANID == extendedPANID }) {
            logger.info("Joining Thread network \(xpanid, privacy: .public)")
        } else {
            logger.error("Thread network \(xpanid, privacy: .public) wasn't in the device's scan results")
        }

        return .network(extendedPANID: extendedPANID)
    }

    /// This is an Ethernet and Thread system; Wi-Fi devices aren't expected, but the system
    /// still asks.
    override func selectWiFiNetwork(from wifiScanResults: [MatterAddDeviceExtensionRequestHandler.WiFiScanResult]) async throws -> MatterAddDeviceExtensionRequestHandler.WiFiNetworkAssociation {
        .defaultSystemNetwork
    }

    /// The controller bypasses device attestation on its side, so there is nothing to check
    /// here.
    override func validateDeviceCredential(_ deviceCredential: MatterAddDeviceExtensionRequestHandler.DeviceCredential) async throws {
    }

    /// Controllers organise devices into rooms of their own, so we don't offer a room picker
    /// during setup.
    override func rooms(in home: MatterAddDeviceRequest.Home?) async -> [MatterAddDeviceRequest.Room] {
        []
    }

    /// Called with the name the user typed in the system sheet. Push it to the controller so
    /// the device shows up with the same name in the app.
    override func configureDevice(named name: String, in room: MatterAddDeviceRequest.Room?) async {
        guard let controllerID = ControllerStore.pendingCommissioningControllerID,
              let controller = ControllerStore.controller(id: controllerID) else {
            logger.notice("No pending controller; skipping the rename to \(name, privacy: .public)")
            return
        }

        guard let nodeId = ControllerStore.lastCommissionedNodeId else {
            logger.notice("No node id recorded; skipping the rename to \(name, privacy: .public)")
            return
        }

        do {
            try await MCCClient(controller: controller).rename(nodeId: nodeId, to: name)
        } catch {
            // Naming is cosmetic -- the device is already commissioned, so don't fail setup
            // over it.
            logger.error("Couldn't rename node \(nodeId): \(error.localizedDescription, privacy: .public)")
        }
    }
}

import Foundation
import Observation

@Observable
@MainActor
final class DeviceListModel {

    private(set) var nodes: [Node] = []
    private(set) var isLoading = false
    private(set) var isWaitingForCommissioning = false

    var errorMessage: String?

    func load(controller: Controller) async {
        isLoading = true
        defer { isLoading = false }

        do {
            nodes = try await MCCClient(controller: controller).nodes().sorted { $0.nodeId < $1.nodeId }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func unpair(_ node: Node, controller: Controller) async {
        // Drop it from the list straight away; the reload below puts it back if the
        // controller disagreed.
        nodes.removeAll { $0.nodeId == node.nodeId }

        do {
            try await MCCClient(controller: controller).unpair(nodeId: node.nodeId)
        } catch {
            errorMessage = error.localizedDescription
        }

        await load(controller: controller)
    }

    /// Waits for a freshly commissioned node to show up.
    ///
    /// The controller doesn't answer `POST /api/nodes` until commissioning has finished, so
    /// the node is normally there on the first refresh; the retries only cover the case where
    /// the extension's own view of the outcome and the node list disagree. The extension
    /// leaves the node id it was given in the App Group for us to look for.
    func awaitCommissionedNode(controller: Controller) async {
        let expectedNodeId = ControllerStore.lastCommissionedNodeId

        isWaitingForCommissioning = true
        defer {
            isWaitingForCommissioning = false
            ControllerStore.lastCommissionedNodeId = nil
            ControllerStore.pendingCommissioningControllerID = nil
        }

        for attempt in 0..<20 {
            if attempt > 0 {
                try? await Task.sleep(for: .seconds(3))
            }

            await load(controller: controller)

            guard let expectedNodeId else {
                // No id to wait for -- the extension never got a response, so one refresh is
                // all we can usefully do.
                return
            }

            if nodes.contains(where: { $0.nodeId == expectedNodeId }) {
                return
            }
        }

        errorMessage = "The device didn't finish commissioning. Check the controller's logs, then pull to refresh."
    }
}

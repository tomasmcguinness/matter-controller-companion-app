import Foundation
import Observation

@Observable
@MainActor
final class ControllersModel {

    private(set) var controllers: [Controller] = []

    func load() {
        controllers = ControllerStore.controllers
    }

    func remove(at offsets: IndexSet) {
        let doomed = offsets.map { controllers[$0] }

        controllers.remove(atOffsets: offsets)

        for controller in doomed {
            ControllerStore.remove(id: controller.id)
        }
    }
}

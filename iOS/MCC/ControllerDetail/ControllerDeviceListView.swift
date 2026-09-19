import SwiftUI
import MatterSupport

struct ControllerDeviceListView: View {

    let controller: Controller

    @State private var model = DeviceListModel()

    var body: some View {
        List {
            if model.isWaitingForCommissioning {
                Section {
                    HStack(spacing: 12) {
                        ProgressView()
                        Text("Commissioning a new device…")
                            .foregroundStyle(.secondary)
                    }
                }
            }

            Section {
                ForEach(model.nodes) { node in
                    Text(node.displayName)
                }
                .onDelete(perform: unpair)
            } footer: {
                if !model.nodes.isEmpty {
                    Text("\(model.nodes.count) device\(model.nodes.count == 1 ? "" : "s") on \(controller.name).")
                }
            }
        }
        .navigationTitle(controller.name)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    Task { await addDevice() }
                } label: {
                    Label("Add Device", systemImage: "plus")
                }
                .disabled(model.isWaitingForCommissioning)
            }
        }
        .refreshable {
            await model.load(controller: controller)
        }
        .overlay {
            if model.nodes.isEmpty && !model.isLoading && !model.isWaitingForCommissioning {
                ContentUnavailableView {
                    Label("No Devices", systemImage: "sensor.fill")
                } description: {
                    Text("Tap + to commission a Matter device onto \(controller.name).")
                }
            }
        }
        .alert("Something Went Wrong",
               isPresented: Binding(get: { model.errorMessage != nil },
                                    set: { if !$0 { model.errorMessage = nil } })) {
            Button("OK") { model.errorMessage = nil }
        } message: {
            Text(model.errorMessage ?? "")
        }
        .task(id: controller.id) {
            await model.load(controller: controller)
        }
    }

    private func unpair(at offsets: IndexSet) {
        let doomed = offsets.map { model.nodes[$0] }

        Task {
            for node in doomed {
                await model.unpair(node, controller: controller)
            }
        }
    }

    /// Hands off to the system Matter setup sheet.
    ///
    /// Everything from here -- camera, QR scan, manual pairing code entry, Thread credentials
    /// -- is Apple's UI. When the user picks a device, the system launches our app extension
    /// out of process and calls its `commissionDevice` method with the onboarding payload;
    /// that's where the call to this specific controller happens. We record which controller
    /// this attempt is for beforehand, since the extension has no other way to know.
    private func addDevice() async {
        ControllerStore.pendingCommissioningControllerID = controller.id
        ControllerStore.lastCommissionedNodeId = nil

        let topology = MatterAddDeviceRequest.Topology(
            ecosystemName: "MCC",
            homes: [MatterAddDeviceRequest.Home(displayName: controller.name)])

        do {
            try await MatterAddDeviceRequest(topology: topology).perform()
        } catch {
            // The user cancelling the sheet lands here too, so only complain if the
            // controller was actually asked to do something.
            if ControllerStore.lastCommissionedNodeId != nil {
                model.errorMessage = error.localizedDescription
            }

            ControllerStore.pendingCommissioningControllerID = nil
            ControllerStore.lastCommissionedNodeId = nil

            return
        }

        await model.awaitCommissionedNode(controller: controller)
    }
}

#Preview {
    NavigationStack {
        ControllerDeviceListView(controller: Controller(name: "Heating Monitor", url: URL(string: "http://192.168.1.250")!))
    }
}

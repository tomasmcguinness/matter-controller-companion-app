import SwiftUI

struct SettingsView: View {

    @State private var push = PushManager.shared
    @State private var controllers: [Controller] = []
    @State private var threadNetworks = ThreadNetworkStore()
    @State private var isShowingAddThreadNetwork = false

    var body: some View {
        NavigationStack {
            Form {
                notificationsSection

                threadNetworksSection

                Section("About") {
                    LabeledContent("App", value: "Matter Controller Companion")
                    LabeledContent("Version", value: Bundle.main.shortVersion)
                    LabeledContent("Build", value: Bundle.main.buildNumber)
                }
            }
            .navigationTitle("Settings")
            .alert("Something Went Wrong",
                   isPresented: Binding(get: { push.errorMessage != nil },
                                        set: { if !$0 { push.errorMessage = nil } })) {
                Button("OK") { push.errorMessage = nil }
            } message: {
                Text(push.errorMessage ?? "")
            }
            .alert("Thread Network Problem",
                   isPresented: Binding(get: { threadNetworks.errorMessage != nil },
                                        set: { if !$0 { threadNetworks.errorMessage = nil } })) {
                Button("OK") { threadNetworks.errorMessage = nil }
            } message: {
                Text(threadNetworks.errorMessage ?? "")
            }
            .sheet(isPresented: $isShowingAddThreadNetwork) {
                AddThreadNetworkView(store: threadNetworks)
            }
            .task {
                controllers = ControllerStore.controllers
                await threadNetworks.load()
                await push.refreshAuthorizationStatus()

                // Not tied to this view's lifetime, so switching tabs doesn't cancel it midway.
                Task { await push.distributeWhereUnknown() }
            }
        }
    }

    private var notificationsSection: some View {
        Section {
            HStack {
                Toggle("Notifications", isOn: Binding(
                    get: { push.isEnabled },
                    set: { enabled in
                        Task {
                            if enabled {
                                await push.enable()
                            } else {
                                await push.disable()
                            }
                        }
                    }))
                    .disabled(push.isWorking)

                if push.isWorking {
                    ProgressView()
                        .padding(.leading, 8)
                }
            }

            if push.isEnabled {
                ForEach(controllers) { controller in
                    NotificationControllerRow(controller: controller,
                                              isSelected: push.isSelected(controller),
                                              hasCredentials: push.hasCredentials(controller),
                                              status: push.statuses[controller.id],
                                              isTesting: push.testing.contains(controller.id),
                                              onToggle: { Task { await push.toggle(controller) } },
                                              onTest: { Task { await push.sendTest(for: controller) } })
                }
            }
        } header: {
            Text("Notifications")
        } footer: {
            if push.authorizationStatus == .denied {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Notifications are turned off for MCC.")
                    Button("Open Settings") {
                        if let url = URL(string: UIApplication.openNotificationSettingsURLString) {
                            UIApplication.shared.open(url)
                        }
                    }
                    .font(.footnote)
                }
            } else if push.isEnabled {
                Text("Tap a controller to let it send you notifications, and tap it again to stop. Each one needs a push relay username and password, which you set when you edit it.")
            } else {
                Text("Lets your controllers send you notifications.")
            }
        }
    }
}

extension SettingsView {

    fileprivate var threadNetworksSection: some View {
        Section {
            if let preferred = threadNetworks.preferredNetwork {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(preferred.name)

                        Text(preferred.extendedPANID.hexString)
                            .font(.caption.monospaced())
                            .foregroundStyle(.secondary)
                    }

                    Spacer()

                    Text("Preferred")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            } else {
                HStack {
                    Button("Show Preferred Network") {
                        Task { await threadNetworks.loadPreferred() }
                    }
                    .disabled(threadNetworks.isLoadingPreferred)

                    if threadNetworks.isLoadingPreferred {
                        Spacer()
                        ProgressView()
                    }
                }
            }

            ForEach(threadNetworks.networks) { network in
                VStack(alignment: .leading, spacing: 2) {
                    Text(network.name)

                    Text(network.extendedPANID.hexString)
                        .font(.caption.monospaced())
                        .foregroundStyle(.secondary)
                }
            }
            .onDelete { offsets in
                let doomed = offsets.map { threadNetworks.networks[$0] }

                Task {
                    for network in doomed {
                        await threadNetworks.delete(network)
                    }
                }
            }

            Button("Add Network…") {
                isShowingAddThreadNetwork = true
            }
        } header: {
            Text("Thread Networks")
        } footer: {
            Text("Networks stored here can be chosen for a controller when you edit it, so devices commissioned onto that controller join that network instead of the phone's preferred one.")
        }
    }
}

/// One controller in the notifications list. Tapping the row ticks or unticks it.
private struct NotificationControllerRow: View {

    let controller: Controller
    let isSelected: Bool
    let hasCredentials: Bool
    let status: PushManager.DeliveryStatus?
    let isTesting: Bool
    let onToggle: () -> Void
    let onTest: () -> Void

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(controller.name)

                if let detail {
                    Text(detail)
                        .font(.caption)
                        .foregroundStyle(detailIsProblem ? .red : .secondary)
                }
            }

            Spacer()

            if status == .sending || isTesting {
                ProgressView()
            } else if isSelected {
                // Borderless so it takes its own taps instead of toggling the row.
                Button("Send Test", action: onTest)
                    .buttonStyle(.borderless)

                Image(systemName: "checkmark")
                    .foregroundStyle(.tint)
                    .fontWeight(.semibold)
                    .padding(.leading, 8)
            }
        }
        .contentShape(Rectangle())
        .onTapGesture(perform: onToggle)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : .isButton)
        .accessibilityActions {
            if isSelected {
                Button("Send Test", action: onTest)
            }
        }
    }

    private var detail: String? {
        if !hasCredentials {
            return "Needs a push relay username and password"
        }

        switch status {
        case .notSupported:
            return "This controller doesn't support notifications"
        case .failed(let message):
            return message
        case nil, .sending, .sent:
            return nil
        }
    }

    private var detailIsProblem: Bool {
        switch status {
        case .failed, .notSupported: return true
        default: return false
        }
    }
}

private extension Bundle {
    var shortVersion: String {
        infoDictionary?["CFBundleShortVersionString"] as? String ?? "—"
    }

    var buildNumber: String {
        infoDictionary?["CFBundleVersion"] as? String ?? "—"
    }
}

#Preview {
    SettingsView()
}

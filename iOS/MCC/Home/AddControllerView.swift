import SwiftUI

struct AddControllerView: View {

    @Environment(\.dismiss) private var dismiss

    /// The controller being edited, or nil when adding a new one.
    let editing: Controller?
    let onSaved: () -> Void

    @State private var name: String
    @State private var addressText: String
    @State private var relayUsername: String
    @State private var relayPassword: String
    @State private var threadExtendedPANID: Data?
    @State private var threadNetworks = ThreadNetworkStore()
    @State private var isVerifying = false
    @State private var errorMessage: String?

    init(editing: Controller? = nil, onSaved: @escaping () -> Void) {
        self.editing = editing
        self.onSaved = onSaved
        _name = State(initialValue: editing?.name ?? "")
        _addressText = State(initialValue: editing?.url.absoluteString ?? "")
        _relayUsername = State(initialValue: editing?.relayUsername ?? "")
        _relayPassword = State(initialValue: editing.flatMap { RelayCredentialStore.password(for: $0.id) } ?? "")
        _threadExtendedPANID = State(initialValue: editing?.threadExtendedPANID)
    }

    /// The password this controller had when the sheet opened, to tell whether it changed.
    private var originalPassword: String {
        editing.flatMap { RelayCredentialStore.password(for: $0.id) } ?? ""
    }

    private var isEditing: Bool { editing != nil }

    /// The name of the chosen network when its credentials are no longer stored, so the picker
    /// still has something to show for it.
    private var missingThreadNetworkName: String? {
        guard !threadNetworks.isLoading,
              let threadExtendedPANID,
              !threadNetworks.networks.contains(where: { $0.extendedPANID == threadExtendedPANID }) else {
            return nil
        }

        return editing?.threadNetworkName ?? threadExtendedPANID.hexString
    }

    /// The name to save alongside the chosen network.
    private var threadNetworkName: String? {
        guard let threadExtendedPANID else { return nil }

        return threadNetworks.networks.first { $0.extendedPANID == threadExtendedPANID }?.name
            ?? editing?.threadNetworkName
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Name") {
                    TextField("e.g. Heating Monitor", text: $name)
                        //.textInputAutocapitalization(.words)
                }

                Section("Address") {
                    TextField("http://192.168.1.250", text: $addressText)
                        //.textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        //.keyboardType(.URL)
                }

                Section {
                    TextField("Username", text: $relayUsername)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .textContentType(.username)

                    SecureField("Password", text: $relayPassword)
                        .textContentType(.password)
                } header: {
                    Text("Push Relay")
                } footer: {
                    Text("The controller signs in to the push relay with these to send you notifications. Leave them blank if this controller shouldn't send notifications.")
                }

                Section {
                    Picker("Network", selection: $threadExtendedPANID) {
                        Text("System Default").tag(Data?.none)

                        ForEach(threadNetworks.networks) { network in
                            Text(network.name).tag(Data?.some(network.extendedPANID))
                        }

                        if let missing = missingThreadNetworkName {
                            Text("\(missing) (missing)").tag(threadExtendedPANID)
                        }
                    }
                } header: {
                    Text("Thread Network")
                } footer: {
                    Text("The Thread network devices commissioned onto this controller join. Add networks in Settings.")
                }
            }
            .task {
                await threadNetworks.load()
            }
            .navigationTitle(isEditing ? "Edit Controller" : "Add Controller")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }

                ToolbarItem(placement: .confirmationAction) {
                    Button(isVerifying ? "Checking…" : (isEditing ? "Save" : "Add")) {
                        Task { await save() }
                    }
                    .disabled(!canSave || isVerifying)
                }
            }
            .alert(isEditing ? "Couldn't Save Controller" : "Couldn't Add Controller",
                   isPresented: Binding(get: { errorMessage != nil },
                                        set: { if !$0 { errorMessage = nil } })) {
                Button("OK") { errorMessage = nil }
            } message: {
                Text(errorMessage ?? "")
            }
        }
    }

    private var trimmedName: String {
        name.trimmingCharacters(in: .whitespaces)
    }

    /// Nil when blank, so a controller without notifications has no username at all.
    private var trimmedRelayUsername: String? {
        let trimmed = relayUsername.trimmingCharacters(in: .whitespaces)
        return trimmed.isEmpty ? nil : trimmed
    }

    private var canSave: Bool {
        guard !trimmedName.isEmpty,
              let url = Controller.connectionURL(from: addressText) else {
            return false
        }

        if let editing {
            return trimmedName != editing.name
                || url != editing.url
                || trimmedRelayUsername != editing.relayUsername
                || relayPassword != originalPassword
                || threadExtendedPANID != editing.threadExtendedPANID
        }

        return true
    }

    private func save() async {
        guard let url = Controller.connectionURL(from: addressText) else {
            errorMessage = "Enter a valid http or https address, e.g. http://192.168.1.250."
            return
        }

        let candidate = Controller(id: editing?.id ?? UUID(),
                                   name: trimmedName,
                                   url: url,
                                   relayUsername: trimmedRelayUsername,
                                   threadExtendedPANID: threadExtendedPANID,
                                   threadNetworkName: threadNetworkName)

        isVerifying = true
        defer { isVerifying = false }

        do {
            // A rename alone shouldn't fail just because the hub is offline right now.
            if url != editing?.url {
                try await MCCClient(controller: candidate).checkReachable()
            }

            if isEditing {
                try ControllerStore.update(candidate)
            } else {
                try ControllerStore.add(candidate)
            }

            RelayCredentialStore.setPassword(trimmedRelayUsername == nil ? nil : relayPassword, for: candidate.id)

            // Don't hold the sheet open while the controller is told; Settings shows how it went.
            Task { await PushManager.shared.controllerSaved(candidate) }

            onSaved()
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

#Preview {
    AddControllerView {}
}

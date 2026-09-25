import SwiftUI

struct AddControllerView: View {

    @Environment(\.dismiss) private var dismiss

    /// The controller being edited, or nil when adding a new one.
    let editing: Controller?
    let onSaved: () -> Void

    @State private var name: String
    @State private var addressText: String
    @State private var isVerifying = false
    @State private var errorMessage: String?

    init(editing: Controller? = nil, onSaved: @escaping () -> Void) {
        self.editing = editing
        self.onSaved = onSaved
        _name = State(initialValue: editing?.name ?? "")
        _addressText = State(initialValue: editing?.url.absoluteString ?? "")
    }

    private var isEditing: Bool { editing != nil }

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

    private var canSave: Bool {
        guard !trimmedName.isEmpty,
              let url = Controller.connectionURL(from: addressText) else {
            return false
        }

        if let editing {
            return trimmedName != editing.name || url != editing.url
        }

        return true
    }

    private func save() async {
        guard let url = Controller.connectionURL(from: addressText) else {
            errorMessage = "Enter a valid http or https address, e.g. http://192.168.1.250."
            return
        }

        let candidate = Controller(id: editing?.id ?? UUID(), name: trimmedName, url: url)

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

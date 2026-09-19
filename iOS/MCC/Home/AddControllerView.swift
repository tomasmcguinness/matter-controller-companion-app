import SwiftUI

struct AddControllerView: View {

    @Environment(\.dismiss) private var dismiss

    let onAdded: () -> Void

    @State private var name = ""
    @State private var addressText = ""
    @State private var isVerifying = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("Name") {
                    TextField("e.g. Heating Monitor", text: $name)
                        .textInputAutocapitalization(.words)
                }

                Section("Address") {
                    TextField("http://192.168.1.250", text: $addressText)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                }
            }
            .navigationTitle("Add Controller")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }

                ToolbarItem(placement: .confirmationAction) {
                    Button(isVerifying ? "Checking…" : "Add") {
                        Task { await add() }
                    }
                    .disabled(!canAdd || isVerifying)
                }
            }
            .alert("Couldn't Add Controller",
                   isPresented: Binding(get: { errorMessage != nil },
                                        set: { if !$0 { errorMessage = nil } })) {
                Button("OK") { errorMessage = nil }
            } message: {
                Text(errorMessage ?? "")
            }
        }
    }

    private var canAdd: Bool {
        !name.trimmingCharacters(in: .whitespaces).isEmpty
            && Controller.connectionURL(from: addressText) != nil
    }

    private func add() async {
        guard let url = Controller.connectionURL(from: addressText) else {
            errorMessage = "Enter a valid http or https address, e.g. http://192.168.1.250."
            return
        }

        let candidate = Controller(name: name.trimmingCharacters(in: .whitespaces), url: url)

        isVerifying = true
        defer { isVerifying = false }

        do {
            try await MCCClient(controller: candidate).checkReachable()
            try ControllerStore.add(candidate)
            onAdded()
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

#Preview {
    AddControllerView {}
}

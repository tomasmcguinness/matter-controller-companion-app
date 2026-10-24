import SwiftUI

/// Stores a Thread network's credentials on the phone so controllers can pick it.
struct AddThreadNetworkView: View {

    @Environment(\.dismiss) private var dismiss

    let store: ThreadNetworkStore

    @State private var datasetHex = ""
    @State private var borderAgentIDHex = ""
    @State private var isSaving = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HexTextView(text: $datasetHex)
                        .frame(minHeight: 120)
                        .overlay(alignment: .topLeading) {
                            if datasetHex.isEmpty {
                                Text("0e080000000000010000…")
                                    .font(.system(.body, design: .monospaced))
                                    .foregroundStyle(.tertiary)
                                    .allowsHitTesting(false)
                            }
                        }
                } header: {
                    Text("Active Dataset")
                } footer: {
                    Text("On the border router, run `ot-ctl dataset active -x` and paste the hex here.")
                }

                Section {
                    TextField("e.g. 729492de2f55115f", text: $borderAgentIDHex)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.system(.body, design: .monospaced))
                } header: {
                    Text("Extended Address")
                } footer: {
                    Text("On the same border router, run `extaddr` (`ot-ctl extaddr` on a Linux OTBR). It's the `xa` value the border router advertises. iOS needs this, not the 16-byte `ba id`, and the border router has to be online.")
                }
            }
            .navigationTitle("Add Thread Network")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }

                ToolbarItem(placement: .confirmationAction) {
                    Button(isSaving ? "Adding…" : "Add") {
                        Task { await save() }
                    }
                    .disabled(!canSave || isSaving)
                }
            }
            .alert("Couldn't Add Network",
                   isPresented: Binding(get: { errorMessage != nil },
                                        set: { if !$0 { errorMessage = nil } })) {
                Button("OK") { errorMessage = nil }
            } message: {
                Text(errorMessage ?? "")
            }
        }
    }

    private var canSave: Bool {
        !datasetHex.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !borderAgentIDHex.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private func save() async {
        isSaving = true
        defer { isSaving = false }

        do {
            try await store.add(datasetHex: datasetHex, borderAgentIDHex: borderAgentIDHex)
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

/// A multi-line field for pasting long hex strings.
///
/// SwiftUI's `TextField` wraps a long unbroken string with hyphens and applies smart
/// punctuation, and neither can be turned off from SwiftUI, so this drops down to `UITextView`.
private struct HexTextView: UIViewRepresentable {

    @Binding var text: String

    func makeUIView(context: Context) -> UITextView {
        let textView = UITextView()
        textView.delegate = context.coordinator
        textView.backgroundColor = .clear
        textView.textContainerInset = .zero
        textView.textContainer.lineFragmentPadding = 0
        textView.isScrollEnabled = true

        textView.autocapitalizationType = .none
        textView.autocorrectionType = .no
        textView.spellCheckingType = .no
        textView.smartDashesType = .no
        textView.smartQuotesType = .no
        textView.smartInsertDeleteType = .no

        // Wrap anywhere, never hyphenate.
        let paragraph = NSMutableParagraphStyle()
        paragraph.lineBreakMode = .byCharWrapping
        paragraph.hyphenationFactor = 0
        paragraph.usesDefaultHyphenation = false

        textView.typingAttributes = [
            .font: UIFont.monospacedSystemFont(ofSize: UIFont.preferredFont(forTextStyle: .body).pointSize,
                                               weight: .regular),
            .foregroundColor: UIColor.label,
            .paragraphStyle: paragraph
        ]

        return textView
    }

    func updateUIView(_ textView: UITextView, context: Context) {
        guard textView.text != text else { return }

        textView.attributedText = NSAttributedString(string: text, attributes: textView.typingAttributes)
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(text: $text)
    }

    final class Coordinator: NSObject, UITextViewDelegate {

        private let text: Binding<String>

        init(text: Binding<String>) {
            self.text = text
        }

        func textViewDidChange(_ textView: UITextView) {
            text.wrappedValue = textView.text
        }
    }
}

#Preview {
    AddThreadNetworkView(store: ThreadNetworkStore())
}

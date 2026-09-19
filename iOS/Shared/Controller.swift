import Foundation

/// A Matter controller (hub) the user has added to MCC.
///
/// Unlike the single-hub original app, this is purely local metadata the user typed in --
/// there's no QR pairing and no token, just a name and the base address to reach it at.
struct Controller: Codable, Identifiable, Equatable, Hashable, Sendable {

    let id: UUID
    var name: String
    var url: URL

    init(id: UUID = UUID(), name: String, url: URL) {
        self.id = id
        self.name = name
        self.url = url
    }
}

extension Controller {

    /// Parses free-form text into a connection URL, requiring an http/https scheme and a
    /// non-empty host. Returns nil if the text isn't a usable address.
    static func connectionURL(from text: String) -> URL? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)

        guard !trimmed.isEmpty,
              let url = URL(string: trimmed),
              let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              let host = url.host, !host.isEmpty else {
            return nil
        }

        return url
    }
}

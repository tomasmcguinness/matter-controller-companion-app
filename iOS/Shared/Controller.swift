import Foundation

/// A Matter controller (hub) the user has added to MCC.
///
/// Unlike the single-hub original app, this is purely local metadata the user typed in --
/// there's no QR pairing and no token, just a name and the base address to reach it at.
struct Controller: Codable, Identifiable, Equatable, Hashable, Sendable {

    let id: UUID
    var name: String
    var url: URL

    /// The username this controller signs in to the push relay with. The matching password is
    /// in the Keychain (see `RelayCredentialStore`); nil when notifications aren't set up for
    /// this controller.
    var relayUsername: String?

    /// The Thread network devices commissioned onto this controller should join, by extended
    /// PAN ID. Its credentials have to be stored on the phone (Settings → Thread Networks);
    /// nil means the system's preferred network.
    var threadExtendedPANID: Data?

    /// The chosen network's name, kept so it can still be shown if its credentials are removed.
    var threadNetworkName: String?

    init(id: UUID = UUID(), name: String, url: URL, relayUsername: String? = nil,
         threadExtendedPANID: Data? = nil, threadNetworkName: String? = nil) {
        self.id = id
        self.name = name
        self.url = url
        self.relayUsername = relayUsername
        self.threadExtendedPANID = threadExtendedPANID
        self.threadNetworkName = threadNetworkName
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

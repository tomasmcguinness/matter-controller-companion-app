import Foundation

/// The self-hosted relay that holds the APNs signing key and forwards controllers'
/// notifications to Apple. See `relay/README.md`.
enum PushRelay {

    /// Where controllers (and the test button) send notifications.
    static let baseURL = URL(string: "https://push.example.com")!

    /// Which APNs the device token belongs to. Debug builds are signed with the development
    /// `aps-environment`; TestFlight and App Store builds use production.
    static var environment: String {
        #if DEBUG
        "sandbox"
        #else
        "production"
        #endif
    }

    /// Sends a notification through the relay exactly the way a controller would.
    static func send(_ push: PushRequest, username: String, password: String) async throws {
        var request = URLRequest(url: baseURL.appending(path: "v1/push"))

        request.httpMethod = "POST"
        request.timeoutInterval = 20
        request.httpBody = try JSONEncoder().encode(push)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Basic " + Data("\(username):\(password)".utf8).base64EncodedString(),
                         forHTTPHeaderField: "Authorization")

        let data: Data
        let response: URLResponse

        do {
            (data, response) = try await URLSession.shared.data(for: request)
        } catch {
            throw PushRelayError.unreachable(error)
        }

        guard let http = response as? HTTPURLResponse else {
            throw PushRelayError.rejected(status: 0, message: nil)
        }

        guard (200..<300).contains(http.statusCode) else {
            let message = (try? JSONDecoder().decode(RelayErrorBody.self, from: data))?.error
            throw PushRelayError.rejected(status: http.statusCode, message: message)
        }
    }

    private struct RelayErrorBody: Decodable {
        let error: String
    }
}

enum PushRelayError: LocalizedError {
    case unreachable(any Error)
    case rejected(status: Int, message: String?)

    var errorDescription: String? {
        switch self {
        case .unreachable(let underlying):
            return "Couldn't reach the push relay (\(underlying.localizedDescription))."
        case .rejected(401, _):
            return "The push relay didn't accept this controller's username and password."
        case .rejected(let status, let message):
            if let message, !message.isEmpty {
                return "The push relay returned \(status): \(message)"
            }
            return "The push relay returned \(status)."
        }
    }
}

import Foundation

/// Talks to a Matter controller's REST API.
///
/// Used from both the app and the Matter extension, so it holds no state beyond the
/// controller it was built for. There is no authentication in this API -- every request is
/// plain, unauthenticated JSON over HTTP on the local network.
struct MCCClient: Sendable {

    let controller: Controller

    private let session: URLSession

    init(controller: Controller, session: URLSession = .shared) {
        self.controller = controller
        self.session = session
    }

    /// Builds a client for whatever controller a commissioning attempt is currently targeting,
    /// or throws if there isn't one. Used from the Matter extension, which has no controller of
    /// its own in scope.
    static func forPendingCommissioningTarget() throws -> MCCClient {
        guard let id = ControllerStore.pendingCommissioningControllerID,
              let controller = ControllerStore.controller(id: id) else {
            throw ClientError.noPendingCommissioningTarget
        }

        return MCCClient(controller: controller)
    }

    // MARK: - Endpoints

    /// Confirms the controller is reachable. Used right after the user types in an address, so
    /// a bad entry fails immediately rather than on the device list.
    func checkReachable() async throws {
        _ = try await perform(path: "/api/companion/info", method: "GET", bodyData: nil, timeout: 20)
    }

    func nodes() async throws -> [Node] {
        try await get("/api/companion/nodes")
    }

    /// Hands a Matter onboarding payload to the controller, which does the actual
    /// commissioning.
    ///
    /// The controller holds the request open until commissioning finishes, so this takes as
    /// long as pairing does and a success means the device really did join the fabric.
    /// Anything else throws, including the controller's own 504 when it gave up waiting.
    @discardableResult
    func commission(setupCode: String) async throws -> UInt64 {
        let body = try JSONEncoder().encode(CommissionRequest(inUse: false, setupCode: setupCode))
        let data = try await perform(path: "/api/companion/nodes", method: "POST", bodyData: body, timeout: 90)

        return try decode(CommissionResponse.self, from: data).nodeId
    }

    func rename(nodeId: UInt64, to name: String) async throws {
        let body = try JSONEncoder().encode(RenameRequest(name: name))
        _ = try await perform(path: "/api/companion/nodes/\(nodeId)/update", method: "PUT", bodyData: body, timeout: 20)
    }

    func unpair(nodeId: UInt64) async throws {
        _ = try await perform(path: "/api/companion/nodes/\(nodeId)", method: "DELETE", bodyData: nil, timeout: 20)
    }

    // MARK: - Transport

    /// Built by string rather than `appendingPathComponent`, which escapes and re-normalises
    /// in ways that don't survive a leading slash cleanly.
    private func makeRequest(baseURL: URL, path: String, method: String, timeout: TimeInterval) -> URLRequest? {
        guard let url = URL(string: baseURL.absoluteString + path) else {
            return nil
        }

        var request = URLRequest(url: url)

        request.httpMethod = method
        request.timeoutInterval = timeout
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        return request
    }

    /// Sends a single request to the controller's configured URL.
    private func perform(path: String, method: String, bodyData: Data?, timeout: TimeInterval) async throws -> Data {
        guard var request = makeRequest(baseURL: controller.url, path: path, method: method, timeout: timeout) else {
            throw ClientError.unreachable(controller.url.absoluteString, underlying: nil)
        }

        if let bodyData {
            request.httpBody = bodyData
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }

        let data: Data
        let response: URLResponse

        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw ClientError.unreachable(controller.url.absoluteString, underlying: error)
        }

        guard let http = response as? HTTPURLResponse else {
            throw ClientError.badResponse
        }

        guard (200..<300).contains(http.statusCode) else {
            throw ClientError.httpError(status: http.statusCode,
                                        message: String(data: data, encoding: .utf8))
        }

        return data
    }

    private func get<Response: Decodable>(_ path: String) async throws -> Response {
        let data = try await perform(path: path, method: "GET", bodyData: nil, timeout: 20)

        return try decode(Response.self, from: data)
    }

    private func decode<Response: Decodable>(_ type: Response.Type, from data: Data) throws -> Response {
        do {
            return try JSONDecoder().decode(type, from: data)
        } catch {
            throw ClientError.decodingFailed(error)
        }
    }
}

enum ClientError: LocalizedError {
    case unreachable(String, underlying: (any Error)?)
    case badResponse
    case httpError(status: Int, message: String?)
    case decodingFailed(any Error)
    case noPendingCommissioningTarget

    var errorDescription: String? {
        switch self {
        case .unreachable(let address, let underlying):
            let reason = underlying.map { " (\($0.localizedDescription))" } ?? ""
            return "Couldn't reach \(address)\(reason). Check that it's powered on and on the same network."
        case .badResponse:
            return "The controller sent back something unexpected."
        case .httpError(let status, let message):
            if let message, !message.isEmpty {
                return "The controller returned \(status): \(message)"
            }
            return "The controller returned \(status)."
        case .decodingFailed:
            return "Couldn't read the controller's response."
        case .noPendingCommissioningTarget:
            return "No controller was set up to receive this device."
        }
    }
}

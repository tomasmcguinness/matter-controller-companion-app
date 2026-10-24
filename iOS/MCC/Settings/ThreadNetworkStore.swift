import Foundation
import Observation
import ThreadNetwork
import os

/// The Thread networks MCC has stored credentials for on this phone.
///
/// iOS, not the controller, gives a Thread device its network credentials during
/// commissioning, and it can only hand over a network it has credentials for. Storing them here
/// lets a controller pick a network other than the system's preferred one (see
/// `Controller.threadExtendedPANID`).
@Observable
@MainActor
final class ThreadNetworkStore {

    /// A stored network, copied out of `THCredentials` so it can be passed around freely.
    struct Network: Identifiable, Hashable {
        let name: String
        let extendedPANID: Data
        let borderAgentID: Data

        var id: Data { extendedPANID }
    }

    private static let logger = Logger(subsystem: "com.tomasmcguinness.matter-controller-companion",
                                       category: "ThreadNetworks")

    private(set) var networks: [Network] = []
    private(set) var isLoading = false

    /// The system's preferred network (usually the one Apple's own border routers run), once
    /// the user has asked to see it. Not part of `networks` -- MCC didn't store it and can't
    /// delete it.
    private(set) var preferredNetwork: Network?
    private(set) var isLoadingPreferred = false

    var errorMessage: String?

    private let client = THClient()

    /// Reads back every network this team's apps have stored. The system's preferred network
    /// isn't included unless one of our apps stored it.
    func load() async {
        isLoading = true
        defer { isLoading = false }

        do {
            let credentials = try await client.allCredentials()

            networks = credentials
                .compactMap { credential -> Network? in
                    guard let extendedPANID = credential.extendedPANID,
                          let borderAgentID = credential.borderAgentID else {
                        return nil
                    }

                    return Network(name: credential.networkName ?? "Unnamed Network",
                                   extendedPANID: extendedPANID,
                                   borderAgentID: borderAgentID)
                }
                .sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
        } catch {
            // ThreadNetwork throws ("no active border router records found") rather than
            // returning an empty set when nothing is stored, and it has no public error codes
            // to tell that apart from a real failure. Treat it as empty; genuine problems such
            // as a missing entitlement still surface when adding a network.
            let nsError = error as NSError
            Self.logger.notice("No Thread credentials read (\(nsError.domain, privacy: .public) \(nsError.code)): \(error.localizedDescription, privacy: .public)")
            networks = []
        }
    }

    /// Reads the system's preferred network. iOS asks the user for permission first, so only
    /// call this when they've asked for it.
    func loadPreferred() async {
        isLoadingPreferred = true
        defer { isLoadingPreferred = false }

        do {
            let credential = try await client.preferredCredentials()

            guard let extendedPANID = credential.extendedPANID else {
                errorMessage = "The preferred network didn't include an extended PAN ID."
                return
            }

            preferredNetwork = Network(name: credential.networkName ?? "Unnamed Network",
                                       extendedPANID: extendedPANID,
                                       borderAgentID: credential.borderAgentID ?? Data())
        } catch {
            let nsError = error as NSError
            Self.logger.error("Couldn't read the preferred network (\(nsError.domain, privacy: .public) \(nsError.code)): \(error.localizedDescription, privacy: .public)")
            errorMessage = error.localizedDescription
        }
    }

    /// Stores a network from its active operational dataset (`ot-ctl dataset active -x`) and
    /// its border router's extended address (`ot-ctl extaddr`), both as hex.
    ///
    /// Despite the parameter name, ThreadNetwork wants the 8-byte MAC extended address -- the
    /// `xa` TXT value in the border router's `_meshcop._udp` record -- not the 16-byte border
    /// agent id (`id`, `ot-ctl ba id`). Passing the latter fails with ThreadCredentialsStore
    /// code 4, "Invalid parameter sent to server". The border router also has to be online:
    /// iOS checks the address against its mDNS record before storing anything.
    func add(datasetHex: String, borderAgentIDHex: String) async throws {
        guard let dataset = Data(hexString: datasetHex), !dataset.isEmpty else {
            throw ThreadNetworkError.invalidDataset
        }

        guard let borderAgentID = Data(hexString: borderAgentIDHex), borderAgentID.count == 8 else {
            throw ThreadNetworkError.invalidBorderAgentID
        }

        Self.logger.info("Storing \(dataset.count)-byte dataset for border agent \(borderAgentID.hexString, privacy: .public) (\(borderAgentID.count) bytes)")

        do {
            try await client.storeCredentials(forBorderAgent: borderAgentID, activeOperationalDataSet: dataset)
        } catch {
            // ThreadNetwork's messages are vague ("Invalid parameter sent to server"), so keep
            // the domain and code -- they're what tells the failures apart.
            let nsError = error as NSError
            Self.logger.error("storeCredentials failed (\(nsError.domain, privacy: .public) \(nsError.code)): \(nsError, privacy: .public)")
            throw ThreadNetworkError.storeFailed("\(error.localizedDescription) (\(nsError.domain) \(nsError.code))")
        }

        await load()
    }

    func delete(_ network: Network) async {
        do {
            try await client.deleteCredentials(forBorderAgent: network.borderAgentID)
        } catch {
            errorMessage = error.localizedDescription
        }

        await load()
    }
}

enum ThreadNetworkError: LocalizedError {
    case invalidDataset
    case invalidBorderAgentID
    case storeFailed(String)

    var errorDescription: String? {
        switch self {
        case .storeFailed(let message):
            return message
        case .invalidDataset:
            return "The dataset isn't valid hex. Copy it from `ot-ctl dataset active -x`."
        case .invalidBorderAgentID:
            return "The extended address should be 8 bytes (16 hex digits). Copy it from `extaddr` on the border router -- not `ba id`, which is twice as long."
        }
    }
}

extension Data {

    /// Parses hex, ignoring whitespace, colons and dashes. Nil if anything else is in there or
    /// there's an odd number of digits.
    init?(hexString: String) {
        let digits = hexString.filter { !$0.isWhitespace && $0 != ":" && $0 != "-" }

        guard digits.count.isMultiple(of: 2) else { return nil }

        var bytes = [UInt8]()
        bytes.reserveCapacity(digits.count / 2)

        var index = digits.startIndex

        while index < digits.endIndex {
            let next = digits.index(index, offsetBy: 2)

            guard let byte = UInt8(digits[index..<next], radix: 16) else { return nil }

            bytes.append(byte)
            index = next
        }

        self.init(bytes)
    }

    /// Lowercase hex with no separators.
    var hexString: String {
        map { String(format: "%02x", $0) }.joined()
    }
}

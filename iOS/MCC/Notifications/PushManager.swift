import Foundation
import Observation
import UIKit
import UserNotifications
import os

/// Owns this phone's push notification registration and hands it to the controllers the user
/// picked.
///
/// Controllers hold the phone's real APNs device token, plus their own relay username and
/// password, and post to the push relay themselves. Turning notifications on only gets a
/// token; the user then ticks each controller that should notify them, which sends that
/// controller the details. Unticking tells it to forget them.
@Observable
@MainActor
final class PushManager {

    static let shared = PushManager()

    enum DeliveryStatus: Equatable {
        case sending
        case sent
        case notSupported
        case failed(String)
    }

    private static let logger = Logger(subsystem: "com.tomasmcguinness.matter-controller-companion",
                                       category: "PushManager")

    private static let isEnabledKey = "pushNotificationsEnabled"
    private static let deviceTokenKey = "pushDeviceToken"
    private static let selectedControllerIdsKey = "pushSelectedControllerIds"

    private(set) var isEnabled: Bool {
        didSet { Self.defaults?.set(isEnabled, forKey: Self.isEnabledKey) }
    }

    /// The controllers the user ticked -- the ones that have been sent this phone's details.
    private(set) var selectedControllerIDs: Set<UUID> {
        didSet { Self.defaults?.set(selectedControllerIDs.map(\.uuidString), forKey: Self.selectedControllerIdsKey) }
    }

    private(set) var isWorking = false
    private(set) var authorizationStatus: UNAuthorizationStatus = .notDetermined

    /// What happened the last time this phone's details were sent to each controller.
    private(set) var statuses: [UUID: DeliveryStatus] = [:]

    /// Controllers with a test notification in flight.
    private(set) var testing: Set<UUID> = []

    var errorMessage: String?

    /// Lowercase hex, the way APNs and the relay expect it.
    private var deviceToken: String? {
        didSet { Self.defaults?.set(deviceToken, forKey: Self.deviceTokenKey) }
    }

    private var tokenContinuation: CheckedContinuation<String, any Error>?

    private static var defaults: UserDefaults? {
        UserDefaults(suiteName: ControllerStore.appGroup)
    }

    private init() {
        isEnabled = Self.defaults?.bool(forKey: Self.isEnabledKey) ?? false
        deviceToken = Self.defaults?.string(forKey: Self.deviceTokenKey)
        selectedControllerIDs = Set((Self.defaults?.stringArray(forKey: Self.selectedControllerIdsKey) ?? [])
            .compactMap(UUID.init(uuidString:)))
    }

    // MARK: - Lifecycle

    /// Called at launch. Apple asks apps to register on every launch, because the token can
    /// change (restores, reinstalls); a changed token arrives in `didRegister(deviceToken:)`.
    func handleLaunch() async {
        await refreshAuthorizationStatus()

        if isEnabled {
            UIApplication.shared.registerForRemoteNotifications()
        }
    }

    func refreshAuthorizationStatus() async {
        authorizationStatus = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
    }

    func didRegister(deviceToken data: Data) {
        let token = data.map { String(format: "%02x", $0) }.joined()

        if let continuation = tokenContinuation {
            tokenContinuation = nil
            continuation.resume(returning: token)
            return
        }

        // A launch-time re-registration. Controllers only need telling if the token moved.
        guard isEnabled, token != deviceToken else { return }

        Self.logger.info("Device token changed; updating controllers")

        deviceToken = token

        Task { await distributeToSelectedControllers() }
    }

    func didFailToRegister(error: any Error) {
        Self.logger.error("Remote notification registration failed: \(error.localizedDescription, privacy: .public)")

        if let continuation = tokenContinuation {
            tokenContinuation = nil
            continuation.resume(throwing: error)
        }
    }

    // MARK: - Turning it on and off

    func enable() async {
        isWorking = true
        defer { isWorking = false }

        do {
            let granted = try await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])

            await refreshAuthorizationStatus()

            guard granted else {
                errorMessage = "Notifications are turned off for MCC. Turn them on in the Settings app, then try again."
                return
            }

            deviceToken = try await registerForDeviceToken()
            isEnabled = true
        } catch {
            errorMessage = "Couldn't register for notifications. \(error.localizedDescription)"
        }
    }

    func disable() async {
        isWorking = true
        defer { isWorking = false }

        // Best effort: a controller that's offline has nothing we can do about it now, and
        // there's no way to retry later once we've let go of it.
        await withTaskGroup(of: Void.self) { group in
            for controller in ControllerStore.controllers where selectedControllerIDs.contains(controller.id) {
                group.addTask {
                    try? await MCCClient(controller: controller).clearNotificationTarget()
                }
            }
        }

        isEnabled = false
        deviceToken = nil
        selectedControllerIDs = []
        statuses = [:]
    }

    private func registerForDeviceToken() async throws -> String {
        try await withCheckedThrowingContinuation { continuation in
            tokenContinuation?.resume(throwing: CancellationError())
            tokenContinuation = continuation

            UIApplication.shared.registerForRemoteNotifications()
        }
    }

    // MARK: - Choosing controllers

    func isSelected(_ controller: Controller) -> Bool {
        selectedControllerIDs.contains(controller.id)
    }

    func hasCredentials(_ controller: Controller) -> Bool {
        credentials(for: controller) != nil
    }

    /// Ticks or unticks a controller: sends it this phone's details, or tells it to forget
    /// them.
    func toggle(_ controller: Controller) async {
        guard isEnabled, statuses[controller.id] != .sending else { return }

        if isSelected(controller) {
            await deselect(controller)
        } else {
            await select(controller)
        }
    }

    private func select(_ controller: Controller) async {
        guard hasCredentials(controller) else {
            errorMessage = "Add a push relay username and password to \(controller.name) first. Swipe right on it in Home to edit it."
            return
        }

        await distribute(to: controller)

        if statuses[controller.id] == .sent {
            selectedControllerIDs.insert(controller.id)
        }
    }

    private func deselect(_ controller: Controller) async {
        statuses[controller.id] = .sending

        // Untick whatever happens -- otherwise a controller that's gone for good could never
        // be removed -- but say so if it may still be sending.
        do {
            try await MCCClient(controller: controller).clearNotificationTarget()
        } catch ClientError.httpError(status: 404, _) {
            // Never supported notifications, so there's nothing to stop.
        } catch {
            errorMessage = "\(controller.name) may keep sending notifications until it can be reached. \(error.localizedDescription)"
        }

        selectedControllerIDs.remove(controller.id)
        statuses[controller.id] = nil
    }

    // MARK: - Sending details to controllers

    private func distributeToSelectedControllers() async {
        await withTaskGroup(of: Void.self) { group in
            for controller in ControllerStore.controllers where selectedControllerIDs.contains(controller.id) {
                group.addTask { await self.distribute(to: controller) }
            }
        }
    }

    /// Re-checks ticked controllers with no status yet, e.g. after a relaunch, without
    /// re-sending to the ones already known to be set up.
    func distributeWhereUnknown() async {
        await withTaskGroup(of: Void.self) { group in
            for controller in ControllerStore.controllers
            where selectedControllerIDs.contains(controller.id) && statuses[controller.id] == nil {
                group.addTask { await self.distribute(to: controller) }
            }
        }
    }

    /// Sends this phone's token and the controller's relay credentials to the controller.
    private func distribute(to controller: Controller) async {
        guard isEnabled, let deviceToken, let credentials = credentials(for: controller) else { return }

        statuses[controller.id] = .sending

        let target = NotificationTarget(relayUrl: PushRelay.baseURL.absoluteString,
                                        username: credentials.username,
                                        password: credentials.password,
                                        deviceToken: deviceToken,
                                        environment: PushRelay.environment)

        do {
            try await MCCClient(controller: controller).setNotificationTarget(target)
            statuses[controller.id] = .sent
        } catch ClientError.httpError(status: 404, _) {
            statuses[controller.id] = .notSupported
        } catch {
            statuses[controller.id] = .failed(error.localizedDescription)
        }
    }

    /// Called after a controller is edited, so a ticked one gets its new credentials or
    /// address -- or is unticked when its credentials were removed.
    func controllerSaved(_ controller: Controller) async {
        guard isEnabled, isSelected(controller) else { return }

        if hasCredentials(controller) {
            await distribute(to: controller)
        } else {
            await deselect(controller)
        }
    }

    func controllerRemoved(id: UUID) {
        selectedControllerIDs.remove(id)
        statuses[id] = nil
    }

    // MARK: - Testing

    /// Sends a notification through the relay using this controller's credentials, the same
    /// way the controller would, so it checks the relay and the credentials together.
    func sendTest(for controller: Controller) async {
        guard let deviceToken, let credentials = credentials(for: controller) else { return }

        testing.insert(controller.id)
        defer { testing.remove(controller.id) }

        let push = PushRequest(deviceToken: deviceToken,
                               environment: PushRelay.environment,
                               title: controller.name,
                               body: "This is a test notification from MCC.")

        do {
            try await PushRelay.send(push, username: credentials.username, password: credentials.password)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func credentials(for controller: Controller) -> (username: String, password: String)? {
        guard let username = controller.relayUsername, !username.isEmpty,
              let password = RelayCredentialStore.password(for: controller.id), !password.isEmpty else {
            return nil
        }

        return (username, password)
    }
}

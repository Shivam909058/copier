import AppKit
import UserNotifications

/// Native notifications: received files (click reveals them) and mirrored phone notifications
/// (with an inline Reply when the phone notification has one).
@MainActor
final class Notifier: NSObject, UNUserNotificationCenterDelegate {
    /// Lazy: `current()` throws in processes without an app bundle (the test runner), and nothing
    /// should touch notifications before `start()` anyway.
    private lazy var center = UNUserNotificationCenter.current()
    var onReply: @MainActor (_ key: String, _ text: String) -> Void = { _, _ in }
    var onOpenApp: @MainActor () -> Void = {}

    func start() {
        center.delegate = self
        let reply = UNTextInputNotificationAction(
            identifier: "reply", title: "Reply", options: [], textInputButtonTitle: "Send", textInputPlaceholder: "Message"
        )
        center.setNotificationCategories([UNNotificationCategory(identifier: "phone-reply", actions: [reply], intentIdentifiers: [])])
        center.requestAuthorization(options: [.alert, .sound]) { granted, error in
            if let error {
                Log.app.error("notification permission failed: \(String(describing: error), privacy: .public)")
            } else if !granted {
                Log.app.info("notifications are turned off for Copier")
            }
        }
    }

    func isAllowed() async -> Bool {
        let status = await center.notificationSettings().authorizationStatus
        return status == .authorized || status == .provisional
    }

    func post(title: String, body: String, revealing file: URL? = nil) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        if let file { content.userInfo = ["path": file.path] }
        center.add(UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil))
    }

    /// Same identifier per phone notification, so updates replace instead of stacking.
    func postPhone(_ notification: PhoneNotification) {
        let content = UNMutableNotificationContent()
        content.title = notification.title.isEmpty ? notification.appName : notification.title
        content.subtitle = notification.title.isEmpty ? "" : notification.appName
        content.body = notification.text
        content.userInfo = ["phoneKey": notification.key]
        if notification.replyAction != nil { content.categoryIdentifier = "phone-reply" }
        center.add(UNNotificationRequest(identifier: "phone-" + notification.key, content: content, trigger: nil))
    }

    func removePhone(key: String) {
        center.removeDeliveredNotifications(withIdentifiers: ["phone-" + key])
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let info = response.notification.request.content.userInfo
        let replyText = (response as? UNTextInputNotificationResponse)?.userText
        let phoneKey = info["phoneKey"] as? String
        let path = info["path"] as? String
        await MainActor.run {
            if let replyText, let phoneKey {
                onReply(phoneKey, replyText)
            } else if let path {
                NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: path)])
            } else {
                onOpenApp()
            }
        }
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .sound]
    }
}

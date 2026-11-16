import AppKit
import CoreImage
import CoreImage.CIFilterBuiltins
import SwiftUI

@MainActor
@Observable
final class AddPhoneModel {
    var url = ""
    var pairedPhone: String?
    var apkMissing = false
}

/// One QR code that installs the Android app and pairs it (PROTOCOL.md § QR pairing).
@MainActor
final class AddPhoneWindow: NSObject, NSWindowDelegate {
    private let model = AddPhoneModel()
    private var window: NSWindow?
    var onClose: @MainActor () -> Void = {}

    var isOpen: Bool { window?.isVisible == true }

    func show(url: String, apkMissing: Bool) {
        model.url = url
        model.pairedPhone = nil
        model.apkMissing = apkMissing
        if window == nil {
            let window = NSWindow(contentViewController: NSHostingController(rootView: AddPhoneView(model: model)))
            window.title = "Add Phone"
            window.styleMask = [.titled, .closable]
            window.isReleasedWhenClosed = false
            window.delegate = self
            self.window = window
        }
        window?.center()
        NSApp.activate()
        window?.makeKeyAndOrderFront(nil)
    }

    func showPaired(_ phoneName: String) {
        model.pairedPhone = phoneName
    }

    func windowWillClose(_ notification: Notification) {
        onClose()
    }
}

private struct AddPhoneView: View {
    let model: AddPhoneModel

    var body: some View {
        VStack(spacing: 16) {
            if let phone = model.pairedPhone {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 56))
                    .foregroundStyle(.green)
                Text("Paired with \(phone)").font(.title3.bold())
                Text("Copy on one device, paste on the other. You can close this window.")
                    .foregroundStyle(.secondary)
            } else {
                Text("Scan with your phone's camera").font(.title3.bold())
                if let qr = QRCode.image(for: model.url) {
                    Image(nsImage: qr)
                        .interpolation(.none)
                        .resizable()
                        .frame(width: 240, height: 240)
                        .accessibilityLabel("QR code to install and pair Copier")
                }
                VStack(alignment: .leading, spacing: 8) {
                    Label("Install Copier from the page that opens", systemImage: "1.circle.fill")
                    Label("Tap “Open Copier to pair”", systemImage: "2.circle.fill")
                }
                if model.apkMissing {
                    Text("This build doesn't include the Android app. Run ./gradlew assembleRelease in android/, then rebuild Copier.")
                        .font(.caption)
                        .foregroundStyle(.red)
                }
                Text("Keep both on the same Wi-Fi, or join this Mac to the phone's hotspot.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .multilineTextAlignment(.center)
        .padding(28)
        .frame(width: 340)
    }
}

enum QRCode {
    static func image(for text: String) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let code = filter.outputImage else { return nil }
        // Dark modules on white with a quiet zone, whatever the window's appearance.
        let composed = code.composited(over: CIImage(color: .white).cropped(to: code.extent.insetBy(dx: -4, dy: -4)))
        guard let image = CIContext().createCGImage(composed, from: composed.extent) else { return nil }
        return NSImage(cgImage: image, size: composed.extent.size)
    }
}

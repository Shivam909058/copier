import AppKit
import SwiftUI

/// The "it arrived" moment for photos, files, screenshots and codes: a non-activating panel under
/// the menu bar for 6 s. It works even when macOS notifications are off, and never steals focus.
@MainActor
final class ArrivalPanel {
    struct Action {
        let title: String
        let run: @MainActor () -> Void
    }

    private var panel: NSPanel?
    private var dismissal: Task<Void, Never>?

    func show(title: String, detail: String, image: NSImage?, actions: [Action], onClick: @escaping @MainActor () -> Void) {
        let panel = self.panel ?? Self.makePanel()
        self.panel = panel
        let view = ArrivalView(
            title: title, detail: detail, image: image,
            actions: actions.map { action in
                ArrivalView.Button(title: action.title) { [weak self] in
                    action.run()
                    self?.hide()
                }
            },
            onClick: { [weak self] in
                onClick()
                self?.hide()
            },
            onClose: { [weak self] in self?.hide() }
        )
        let host = NSHostingView(rootView: view)
        let size = host.fittingSize
        panel.contentView = host
        if let screen = NSScreen.main {
            let visible = screen.visibleFrame
            panel.setFrame(NSRect(x: visible.maxX - size.width - 12, y: visible.maxY - size.height - 8, width: size.width, height: size.height), display: true)
        }
        panel.orderFrontRegardless()
        dismissal?.cancel()
        dismissal = Task { [weak self] in
            try? await Task.sleep(for: .seconds(6))
            guard !Task.isCancelled else { return }
            self?.hide()
        }
    }

    func hide() {
        dismissal?.cancel()
        panel?.orderOut(nil)
    }

    private static func makePanel() -> NSPanel {
        let panel = NSPanel(contentRect: .zero, styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: true)
        panel.level = .statusBar
        panel.isFloatingPanel = true
        panel.hidesOnDeactivate = false
        panel.hasShadow = true
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .transient]
        return panel
    }
}

private struct ArrivalView: View {
    struct Button: Identifiable {
        let id = UUID()
        let title: String
        let run: @MainActor () -> Void
    }

    let title: String
    let detail: String
    let image: NSImage?
    let actions: [Button]
    let onClick: @MainActor () -> Void
    let onClose: @MainActor () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Group {
                if let image {
                    Image(nsImage: image).resizable().scaledToFill()
                } else {
                    Image(systemName: "arrow.down.circle.fill").font(.system(size: 30)).foregroundStyle(Color.carbon)
                }
            }
            .frame(width: 52, height: 52)
            .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))

            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.headline)
                Text(detail).font(.callout).foregroundStyle(.secondary).lineLimit(2).truncationMode(.middle)
                if !actions.isEmpty {
                    HStack(spacing: 8) {
                        ForEach(actions) { action in
                            SwiftUI.Button(action.title) { action.run() }.controlSize(.small)
                        }
                    }
                    .padding(.top, 4)
                }
            }
            Spacer(minLength: 0)
            SwiftUI.Button(action: onClose) {
                Image(systemName: "xmark").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("Dismiss")
        }
        .padding(14)
        .frame(width: 360)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Color.carbon.opacity(0.35)))
        .contentShape(Rectangle())
        .onTapGesture(perform: onClick)
    }
}

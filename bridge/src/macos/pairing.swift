import AppKit
import CoreImage
import Foundation

enum Pairing {
  private static var window: NSWindow?

  private struct Document: Decodable {
    let pairingUrl: String
    let expiresAt: String
  }

  static func show(documentAt path: String) throws {
    let document = try read(path)
    try present(document.pairingUrl, expiresAt: document.expiresAt)
  }

  private static func read(_ path: String) throws -> Document {
    let file = URL(fileURLWithPath: path).standardizedFileURL
    let directory = FileManager.default.homeDirectoryForCurrentUser
      .appendingPathComponent("Library/Application Support/Vibe Pocket/invitations", isDirectory: true)
      .standardizedFileURL
    guard file.deletingLastPathComponent() == directory,
          file.lastPathComponent.hasPrefix("invitation.") else {
      throw failure(22, "The pairing invitation file is invalid.")
    }
    defer { try? FileManager.default.removeItem(at: file) }
    let data = try Data(contentsOf: file, options: [.mappedIfSafe])
    guard data.count <= 4_096 else {
      throw failure(27, "The pairing invitation file is too large.")
    }
    return try JSONDecoder().decode(Document.self, from: data)
  }

  private static func present(_ value: String, expiresAt: String) throws {
    guard value.utf8.count <= 2_048,
          let filter = CIFilter(name: "CIQRCodeGenerator") else {
      throw failure(22, "The pairing invitation is invalid.")
    }
    filter.setValue(Data(value.utf8), forKey: "inputMessage")
    filter.setValue("M", forKey: "inputCorrectionLevel")
    guard let code = filter.outputImage else {
      throw failure(5, "The pairing code could not be rendered.")
    }
    let paddedExtent = code.extent.insetBy(dx: -4, dy: -4)
    let background = CIImage(color: CIColor.white).cropped(to: paddedExtent)
    let output = code.composited(over: background)
      .transformed(by: CGAffineTransform(scaleX: 9, y: 9))

    let representation = NSCIImageRep(ciImage: output)
    let image = NSImage(size: representation.size)
    image.addRepresentation(representation)

    let application = NSApplication.shared
    application.setActivationPolicy(.accessory)
    let pairingWindow = NSWindow(
      contentRect: NSRect(x: 0, y: 0, width: 390, height: 470),
      styleMask: [.titled, .closable],
      backing: .buffered,
      defer: false,
    )
    pairingWindow.title = "Pair Vibe Pocket"
    pairingWindow.isReleasedWhenClosed = false

    let title = NSTextField(labelWithString: "Scan to pair")
    title.font = .systemFont(ofSize: 24, weight: .semibold)
    title.alignment = .center
    let detail = NSTextField(labelWithString: "Open the camera on your Android phone")
    detail.font = .systemFont(ofSize: 14, weight: .regular)
    detail.textColor = .secondaryLabelColor
    detail.alignment = .center
    let imageView = NSImageView(image: image)
    imageView.imageScaling = .scaleProportionallyUpOrDown
    imageView.setContentHuggingPriority(.defaultLow, for: .vertical)
    let expiry = NSTextField(labelWithString: "Single use  |  Expires in 5 minutes")
    expiry.font = .monospacedSystemFont(ofSize: 12, weight: .medium)
    expiry.textColor = .secondaryLabelColor
    expiry.alignment = .center

    let stack = NSStackView(views: [title, detail, imageView, expiry])
    stack.orientation = .vertical
    stack.alignment = .centerX
    stack.spacing = 12
    stack.edgeInsets = NSEdgeInsets(top: 24, left: 28, bottom: 24, right: 28)
    pairingWindow.contentView = stack
    imageView.widthAnchor.constraint(equalToConstant: 300).isActive = true
    imageView.heightAnchor.constraint(equalToConstant: 300).isActive = true

    window = pairingWindow
    pairingWindow.center()
    pairingWindow.makeKeyAndOrderFront(nil)
    application.activate(ignoringOtherApps: true)
    NotificationCenter.default.addObserver(
      forName: NSWindow.willCloseNotification,
      object: pairingWindow,
      queue: .main,
    ) { _ in application.terminate(nil) }
    let expiration = ISO8601DateFormatter().date(from: expiresAt)
      ?? Date().addingTimeInterval(300)
    Timer.scheduledTimer(
      withTimeInterval: max(1, expiration.timeIntervalSinceNow),
      repeats: false,
    ) { _ in application.terminate(nil) }
    application.run()
  }

  private static func failure(_ code: Int, _ message: String) -> NSError {
    NSError(
      domain: "VibePocketBridgeHost",
      code: code,
      userInfo: [NSLocalizedDescriptionKey: message],
    )
  }
}

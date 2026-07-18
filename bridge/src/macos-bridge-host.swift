import ApplicationServices
import Foundation

private func jsonReply(_ body: [String: Any]) {
  guard let data = try? JSONSerialization.data(withJSONObject: body),
        let text = String(data: data, encoding: .utf8) else { return }
  print(text)
}

private func requestAccessibilityPermission() {
  let options = [
    kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true,
  ] as CFDictionary
  let trusted = AXIsProcessTrustedWithOptions(options)
  jsonReply([
    "ok": true,
    "available": trusted,
    "message": trusted
      ? "Vibe Pocket Bridge Host Accessibility permission is enabled."
      : "Enable Vibe Pocket Bridge Host in Privacy & Security > Accessibility, then retry.",
  ])
}

private func promptForAccessibilityIfNeeded() {
  guard !AXIsProcessTrusted() else { return }
  let options = [
    kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true,
  ] as CFDictionary
  _ = AXIsProcessTrustedWithOptions(options)
}

private func runBridge(_ scriptPath: String) throws -> Never {
  let child = Process()
  child.executableURL = URL(fileURLWithPath: "/bin/zsh")
  child.arguments = [scriptPath]
  child.standardInput = FileHandle.standardInput
  child.standardOutput = FileHandle.standardOutput
  child.standardError = FileHandle.standardError

  signal(SIGTERM, SIG_IGN)
  signal(SIGINT, SIG_IGN)
  let terminationSignals = [SIGTERM, SIGINT].map { signalNumber in
    let source = DispatchSource.makeSignalSource(signal: signalNumber, queue: .global())
    source.setEventHandler {
      if child.isRunning { child.terminate() }
    }
    source.resume()
    return source
  }

  try child.run()
  child.waitUntilExit()
  terminationSignals.forEach { $0.cancel() }
  exit(child.terminationStatus)
}

do {
  let arguments = Array(CommandLine.arguments.dropFirst())
  if arguments == ["request-accessibility"] {
    requestAccessibilityPermission()
    exit(0)
  }
  guard arguments.count == 2, arguments[0] == "run" else {
    throw NSError(
      domain: "VibePocketBridgeHost",
      code: 64,
      userInfo: [NSLocalizedDescriptionKey: "Usage: Vibe Pocket Bridge Host run <bridge-script>"],
    )
  }
  promptForAccessibilityIfNeeded()
  try runBridge(arguments[1])
} catch {
  FileHandle.standardError.write(Data("\(error.localizedDescription)\n".utf8))
  exit(1)
}

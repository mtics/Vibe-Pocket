import ApplicationServices
import AppKit
import Darwin
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

private struct CodexControlRequest: Decodable {
  let action: String
  let arguments: [String]
  let input: String
}

private let maximumControlRequestBytes = 128 * 1_024

private final class CodexControlServer {
  private let socketPath: String
  private let queue = DispatchQueue(label: "au.edu.uts.vibepocket.codex-control")
  private var listener: Int32 = -1

  init(socketPath: String) {
    self.socketPath = socketPath
  }

  func start() throws {
    let descriptor = Darwin.socket(AF_UNIX, SOCK_STREAM, 0)
    guard descriptor >= 0 else { throw posixError("create the Codex control socket") }
    listener = descriptor
    var bound = false
    var listening = false
    defer {
      if !listening {
        Darwin.close(descriptor)
        listener = -1
        if bound { Darwin.unlink(socketPath) }
      }
    }

    var address = sockaddr_un()
    address.sun_family = sa_family_t(AF_UNIX)
    let pathBytes = Array(socketPath.utf8) + [0]
    let capacity = MemoryLayout.size(ofValue: address.sun_path)
    guard pathBytes.count <= capacity else {
      throw NSError(
        domain: "VibePocketBridgeHost",
        code: 36,
        userInfo: [NSLocalizedDescriptionKey: "The Codex control socket path is too long."],
      )
    }
    withUnsafeMutableBytes(of: &address.sun_path) { target in
      target.copyBytes(from: pathBytes)
    }
    Darwin.unlink(socketPath)
    let bindResult = withUnsafePointer(to: &address) { pointer in
      pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
        Darwin.bind(descriptor, $0, socklen_t(MemoryLayout<sockaddr_un>.size))
      }
    }
    guard bindResult == 0 else { throw posixError("bind the Codex control socket") }
    bound = true
    guard Darwin.chmod(socketPath, S_IRUSR | S_IWUSR) == 0 else {
      throw posixError("protect the Codex control socket")
    }
    guard Darwin.listen(descriptor, 8) == 0 else { throw posixError("listen for Codex controls") }
    listening = true

    queue.async { [weak self] in self?.acceptLoop() }
  }

  func stop() {
    guard listener >= 0 else { return }
    Darwin.shutdown(listener, SHUT_RDWR)
    Darwin.close(listener)
    listener = -1
    Darwin.unlink(socketPath)
  }

  private func acceptLoop() {
    while listener >= 0 {
      let client = Darwin.accept(listener, nil, nil)
      if client < 0 {
        if errno == EINTR { continue }
        return
      }
      var noSigPipe: Int32 = 1
      Darwin.setsockopt(client, SOL_SOCKET, SO_NOSIGPIPE, &noSigPipe, socklen_t(MemoryLayout.size(ofValue: noSigPipe)))
      handle(client)
      Darwin.close(client)
    }
  }

  private func handle(_ client: Int32) {
    let response: [String: Any]
    do {
      let request = try readRequest(client)
      guard request.action.count <= 40,
            request.arguments.count <= 4,
            request.arguments.allSatisfy({ $0.count <= 200 }),
            request.input.count <= 12_000 else {
        throw NSError(
          domain: "VibePocketBridgeHost",
          code: 22,
          userInfo: [NSLocalizedDescriptionKey: "The Codex control request is invalid."],
        )
      }
      response = try runCodexControl(
        arguments: [request.action] + request.arguments,
        input: request.input,
      )
    } catch {
      let message = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
      response = ["ok": false, "message": message]
    }
    writeResponse(response, to: client)
  }

  private func readRequest(_ client: Int32) throws -> CodexControlRequest {
    var data = Data()
    var buffer = [UInt8](repeating: 0, count: 4_096)
    while data.count <= maximumControlRequestBytes {
      let count = buffer.withUnsafeMutableBytes { bytes in
        Darwin.recv(client, bytes.baseAddress, bytes.count, 0)
      }
      guard count > 0 else { break }
      data.append(contentsOf: buffer.prefix(count))
      if data.contains(10) { break }
    }
    guard data.count <= maximumControlRequestBytes else {
      throw NSError(
        domain: "VibePocketBridgeHost",
        code: 27,
        userInfo: [NSLocalizedDescriptionKey: "The Codex control request was too large."],
      )
    }
    guard let newline = data.firstIndex(of: 10), newline > data.startIndex else {
      throw NSError(
        domain: "VibePocketBridgeHost",
        code: 22,
        userInfo: [NSLocalizedDescriptionKey: "The Codex control request was incomplete."],
      )
    }
    return try JSONDecoder().decode(CodexControlRequest.self, from: data[..<newline])
  }

  private func writeResponse(_ body: [String: Any], to client: Int32) {
    guard var data = try? JSONSerialization.data(withJSONObject: body) else { return }
    data.append(10)
    data.withUnsafeBytes { bytes in
      var offset = 0
      while offset < bytes.count {
        let sent = Darwin.send(client, bytes.baseAddress!.advanced(by: offset), bytes.count - offset, 0)
        if sent <= 0 { return }
        offset += sent
      }
    }
  }

  private func posixError(_ operation: String) -> NSError {
    NSError(
      domain: NSPOSIXErrorDomain,
      code: Int(errno),
      userInfo: [NSLocalizedDescriptionKey: "Vibe Pocket could not \(operation): \(String(cString: strerror(errno)))."],
    )
  }
}

private func controlSocketPath() -> String {
  FileManager.default.homeDirectoryForCurrentUser
    .appendingPathComponent("Library/Application Support/Vibe Pocket/bridge-host.sock")
    .path
}

private func runBridge(_ scriptPath: String) throws -> Int32 {
  let controlServer = CodexControlServer(socketPath: controlSocketPath())
  try controlServer.start()
  defer { controlServer.stop() }
  let child = Process()
  child.executableURL = URL(fileURLWithPath: "/bin/zsh")
  child.arguments = [scriptPath]
  child.standardInput = FileHandle.standardInput
  child.standardOutput = FileHandle.standardOutput
  child.standardError = FileHandle.standardError
  var environment = ProcessInfo.processInfo.environment
  environment["VIBE_POCKET_HOST_SOCKET"] = controlSocketPath()
  child.environment = environment

  // Spawn before changing this process's dispositions so Node inherits the
  // default termination behavior across zsh's exec.
  try child.run()
  signal(SIGTERM, SIG_IGN)
  signal(SIGINT, SIG_IGN)
  let terminationSignals = [SIGTERM, SIGINT].map { signalNumber in
    let source = DispatchSource.makeSignalSource(signal: signalNumber, queue: .global())
    source.setEventHandler {
      guard child.isRunning else { return }
      child.terminate()
      let childPID = child.processIdentifier
      DispatchQueue.global().asyncAfter(deadline: .now() + 2) {
        if child.isRunning { Darwin.kill(childPID, SIGKILL) }
      }
    }
    source.resume()
    return source
  }

  child.waitUntilExit()
  terminationSignals.forEach { $0.cancel() }
  return child.terminationStatus
}

@main
private enum Host {
  static func main() {
    do {
      let arguments = Array(CommandLine.arguments.dropFirst())
      if arguments == ["request-accessibility"] {
        requestAccessibilityPermission()
        return
      }
      if arguments.count == 2, arguments[0] == "show-pairing-file" {
        try Pairing.show(documentAt: arguments[1])
        return
      }
      if arguments.first == "codex" {
        jsonReply(try runCodexControl(arguments: Array(arguments.dropFirst())))
        return
      }
      guard arguments.count == 2, arguments[0] == "run" else {
        throw NSError(
          domain: "VibePocketBridgeHost",
          code: 64,
          userInfo: [NSLocalizedDescriptionKey: "Usage: Vibe Pocket Bridge Host run <bridge-script> | codex <action> | show-pairing-file <path>"],
        )
      }
      exit(try runBridge(arguments[1]))
    } catch {
      let message = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
      FileHandle.standardError.write(Data("\(message)\n".utf8))
      exit(1)
    }
  }
}

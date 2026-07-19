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
private let maximumConcurrentControlClients = 8
private let controlClientTimeoutSeconds = 2

private struct SocketIdentity: Equatable {
  let device: dev_t
  let inode: ino_t
}

private final class CodexControlServer {
  private let socketPath: String
  private let acceptQueue = DispatchQueue(label: "au.edu.uts.vibepocket.codex-control.accept")
  private let clientQueue = DispatchQueue(
    label: "au.edu.uts.vibepocket.codex-control.clients",
    attributes: .concurrent
  )
  private let controlQueue = DispatchQueue(label: "au.edu.uts.vibepocket.codex-control.execute")
  private let clientSlots = DispatchSemaphore(value: maximumConcurrentControlClients)
  private var listener: Int32 = -1
  private var ownershipDescriptor: Int32 = -1
  private var ownedSocketIdentity: SocketIdentity?

  init(socketPath: String) {
    self.socketPath = socketPath
  }

  func start() throws {
    try acquireOwnership()
    var started = false
    defer {
      if !started { releaseOwnership() }
    }
    try prepareSocketPath()
    let descriptor = Darwin.socket(AF_UNIX, SOCK_STREAM, 0)
    guard descriptor >= 0 else { throw posixError("create the Codex control socket") }
    listener = descriptor
    var bound = false
    var listening = false
    defer {
      if !listening {
        Darwin.close(descriptor)
        listener = -1
        if bound { unlinkOwnedSocket() }
      }
    }

    var address = try socketAddress()
    let bindResult = withUnsafePointer(to: &address) { pointer in
      pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
        Darwin.bind(descriptor, $0, socklen_t(MemoryLayout<sockaddr_un>.size))
      }
    }
    guard bindResult == 0 else { throw posixError("bind the Codex control socket") }
    bound = true
    ownedSocketIdentity = try socketIdentityIfPresent()
    guard Darwin.chmod(socketPath, S_IRUSR | S_IWUSR) == 0 else {
      throw posixError("protect the Codex control socket")
    }
    guard Darwin.listen(descriptor, 8) == 0 else { throw posixError("listen for Codex controls") }
    listening = true
    started = true

    acceptQueue.async { [weak self] in self?.acceptLoop(descriptor) }
  }

  func stop() {
    let descriptor = listener
    guard descriptor >= 0 else { return }
    listener = -1
    Darwin.shutdown(descriptor, SHUT_RDWR)
    Darwin.close(descriptor)
    unlinkOwnedSocket()
    releaseOwnership()
  }

  private func acceptLoop(_ descriptor: Int32) {
    while true {
      let client = Darwin.accept(descriptor, nil, nil)
      if client < 0 {
        if errno == EINTR { continue }
        return
      }
      configure(client)
      guard clientSlots.wait(timeout: .now()) == .success else {
        writeResponse(["ok": false, "message": "The Codex control server is busy."], to: client)
        Darwin.close(client)
        continue
      }
      clientQueue.async { [weak self] in
        defer {
          Darwin.close(client)
          self?.clientSlots.signal()
        }
        self?.handle(client)
      }
    }
  }

  private func configure(_ client: Int32) {
    var noSigPipe: Int32 = 1
    Darwin.setsockopt(
      client,
      SOL_SOCKET,
      SO_NOSIGPIPE,
      &noSigPipe,
      socklen_t(MemoryLayout.size(ofValue: noSigPipe))
    )
    var timeout = timeval(tv_sec: controlClientTimeoutSeconds, tv_usec: 0)
    Darwin.setsockopt(
      client,
      SOL_SOCKET,
      SO_RCVTIMEO,
      &timeout,
      socklen_t(MemoryLayout.size(ofValue: timeout))
    )
    Darwin.setsockopt(
      client,
      SOL_SOCKET,
      SO_SNDTIMEO,
      &timeout,
      socklen_t(MemoryLayout.size(ofValue: timeout))
    )
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
      response = try controlQueue.sync {
        try runCodexControl(
          arguments: [request.action] + request.arguments,
          input: request.input,
        )
      }
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

  private func socketAddress() throws -> sockaddr_un {
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
    return address
  }

  private func socketIdentityIfPresent() throws -> SocketIdentity? {
    var information = stat()
    guard Darwin.lstat(socketPath, &information) == 0 else {
      if errno == ENOENT { return nil }
      throw posixError("inspect the Codex control socket")
    }
    guard information.st_mode & S_IFMT == S_IFSOCK else {
      throw NSError(
        domain: "VibePocketBridgeHost",
        code: 17,
        userInfo: [NSLocalizedDescriptionKey: "The Codex control socket path is occupied by a non-socket file."],
      )
    }
    return SocketIdentity(device: information.st_dev, inode: information.st_ino)
  }

  private func acquireOwnership() throws {
    let lockPath = socketPath + ".lock"
    let descriptor = Darwin.open(lockPath, O_CREAT | O_RDWR, S_IRUSR | S_IWUSR)
    guard descriptor >= 0 else { throw posixError("open the Codex control ownership lock") }
    guard Darwin.flock(descriptor, LOCK_EX | LOCK_NB) == 0 else {
      let code = errno
      Darwin.close(descriptor)
      throw posixError("acquire the Codex control ownership lock", code: code)
    }
    ownershipDescriptor = descriptor
  }

  private func releaseOwnership() {
    guard ownershipDescriptor >= 0 else { return }
    Darwin.flock(ownershipDescriptor, LOCK_UN)
    Darwin.close(ownershipDescriptor)
    ownershipDescriptor = -1
  }

  private func prepareSocketPath() throws {
    guard let observed = try socketIdentityIfPresent() else { return }
    if try socketHasActiveOwner() {
      throw NSError(
        domain: "VibePocketBridgeHost",
        code: 48,
        userInfo: [NSLocalizedDescriptionKey: "Another Vibe Pocket Bridge Host owns the Codex control socket."],
      )
    }
    guard try socketIdentityIfPresent() == observed else {
      throw NSError(
        domain: "VibePocketBridgeHost",
        code: 16,
        userInfo: [NSLocalizedDescriptionKey: "The Codex control socket changed while checking its owner."],
      )
    }
    guard Darwin.unlink(socketPath) == 0 else {
      throw posixError("remove the stale Codex control socket")
    }
  }

  private func socketHasActiveOwner() throws -> Bool {
    let descriptor = Darwin.socket(AF_UNIX, SOCK_STREAM, 0)
    guard descriptor >= 0 else { throw posixError("probe the Codex control socket") }
    defer { Darwin.close(descriptor) }
    let currentFlags = Darwin.fcntl(descriptor, F_GETFL)
    if currentFlags >= 0 { _ = Darwin.fcntl(descriptor, F_SETFL, currentFlags | O_NONBLOCK) }
    var address = try socketAddress()
    let result = withUnsafePointer(to: &address) { pointer in
      pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
        Darwin.connect(descriptor, $0, socklen_t(MemoryLayout<sockaddr_un>.size))
      }
    }
    if result == 0 { return true }
    let code = errno
    if code == EINPROGRESS || code == EALREADY || code == EISCONN { return true }
    if code == ECONNREFUSED || code == ENOENT { return false }
    throw posixError("probe the Codex control socket", code: code)
  }

  private func unlinkOwnedSocket() {
    guard let ownedSocketIdentity else { return }
    defer { self.ownedSocketIdentity = nil }
    guard (try? socketIdentityIfPresent()) == ownedSocketIdentity else { return }
    Darwin.unlink(socketPath)
  }

  private func posixError(_ operation: String, code: Int32 = errno) -> NSError {
    NSError(
      domain: NSPOSIXErrorDomain,
      code: Int(code),
      userInfo: [NSLocalizedDescriptionKey: "Vibe Pocket could not \(operation): \(String(cString: strerror(code)))."],
    )
  }
}

private func controlSocketPath() -> String {
  FileManager.default.homeDirectoryForCurrentUser
    .appendingPathComponent("Library/Application Support/Vibe Pocket/bridge-host.sock")
    .path
}

@_silgen_name("proc_listchildpids")
private func proc_listchildpids(
  _ parentPID: pid_t,
  _ buffer: UnsafeMutableRawPointer?,
  _ bufferSize: Int32
) -> Int32

private func childProcessIdentifiers(of parentPID: pid_t) -> [pid_t] {
  var identifiers = [pid_t](repeating: 0, count: 256)
  let returned = identifiers.withUnsafeMutableBytes { bytes in
    proc_listchildpids(parentPID, bytes.baseAddress, Int32(bytes.count))
  }
  guard returned > 0 else { return [] }
  return identifiers.prefix(min(Int(returned), identifiers.count)).filter { $0 > 0 }
}

private func descendantProcessIdentifiers(of rootPID: pid_t) -> [pid_t] {
  var visited = Set<pid_t>()
  var pending = childProcessIdentifiers(of: rootPID)
  var result = [pid_t]()
  while !pending.isEmpty && result.count < 512 {
    let processIdentifier = pending.removeFirst()
    guard visited.insert(processIdentifier).inserted else { continue }
    result.append(processIdentifier)
    pending.append(contentsOf: childProcessIdentifiers(of: processIdentifier))
  }
  return result
}

private final class ProcessTreeTerminator {
  private let lock = NSLock()
  private var stopping = false
  private var tracked = Set<pid_t>()

  func terminate(rootPID: pid_t) {
    lock.lock()
    guard !stopping else {
      lock.unlock()
      return
    }
    stopping = true
    let descendants = descendantProcessIdentifiers(of: rootPID)
    tracked.formUnion(descendants)
    lock.unlock()

    descendants.reversed().forEach { Darwin.kill($0, SIGTERM) }
    Darwin.kill(rootPID, SIGTERM)
    DispatchQueue.global().asyncAfter(deadline: .now() + 2) { [weak self] in
      self?.forceKill(rootPID: rootPID)
    }
  }

  func finish(rootPID: pid_t) {
    forceKill(rootPID: rootPID, includeRoot: false)
    let deadline = Date().addingTimeInterval(1)
    while Date() < deadline {
      let live = trackedProcessIdentifiers().filter(processExists)
      if live.isEmpty { return }
      live.forEach { Darwin.kill($0, SIGKILL) }
      usleep(25_000)
    }
  }

  private func forceKill(rootPID: pid_t, includeRoot: Bool = true) {
    lock.lock()
    tracked.formUnion(descendantProcessIdentifiers(of: rootPID))
    let descendants = Array(tracked)
    lock.unlock()
    descendants.forEach { Darwin.kill($0, SIGKILL) }
    if includeRoot { Darwin.kill(rootPID, SIGKILL) }
  }

  private func trackedProcessIdentifiers() -> [pid_t] {
    lock.lock()
    defer { lock.unlock() }
    return Array(tracked)
  }

  private func processExists(_ processIdentifier: pid_t) -> Bool {
    if Darwin.kill(processIdentifier, 0) == 0 { return true }
    return errno == EPERM
  }
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
  let processTree = ProcessTreeTerminator()
  let childPID = child.processIdentifier
  let terminationSignals = [SIGTERM, SIGINT].map { signalNumber in
    let source = DispatchSource.makeSignalSource(signal: signalNumber, queue: .global())
    source.setEventHandler {
      guard child.isRunning else { return }
      processTree.terminate(rootPID: childPID)
    }
    source.resume()
    return source
  }

  child.waitUntilExit()
  processTree.finish(rootPID: childPID)
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

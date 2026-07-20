import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { promisify } from "node:util";
import test from "node:test";

const hostUrl = new URL("../../src/macos/host.swift", import.meta.url);
const execFileAsync = promisify(execFile);

test("checks deadline and peer liveness after dequeue and before any Codex effect", async () => {
  const source = await readFile(hostUrl, "utf8");
  const handle = source.slice(
    source.indexOf("private func handle"),
    source.indexOf("private func readRequest"),
  );

  assert.match(
    handle,
    /controlQueue\.sync[\s\S]*?executeControlRequestIfLive[\s\S]*?nowMilliseconds:[\s\S]*?peerIsAuthorized:[\s\S]*?peerIsConnected:[\s\S]*?runCodexControl/,
  );
});

test("authorizer never nests a process-lifetime lock under its own lock", async () => {
  const source = await readFile(hostUrl, "utf8");
  const authorizer = source.slice(
    source.indexOf("private final class BridgeProcessAuthorizer"),
    source.indexOf("private struct SocketIdentity"),
  );
  const lockedRegions = [...authorizer.matchAll(/lock\.lock\(\)([\s\S]*?)lock\.unlock\(\)/g)];

  assert.ok(lockedRegions.length >= 5);
  for (const [, region] of lockedRegions) {
    assert.doesNotMatch(
      region,
      /(?:lifetime|candidate|authorization\.lifetime)\.(?:register|revoke|matches|belongs)/,
    );
  }
  assert.match(authorizer, /let candidate = current[\s\S]*?lock\.unlock\(\)[\s\S]*?candidate\.matches/);
  assert.match(authorizer, /let isCurrent = current === authorization\.lifetime[\s\S]*?lock\.unlock\(\)[\s\S]*?authorization\.lifetime\.matches/);
});

test("listener and request handling are gated by the registered Bridge instance", async () => {
  const source = await readFile(hostUrl, "utf8");
  const start = source.slice(source.indexOf("func start() throws"), source.indexOf("func stop()"));
  const accept = source.slice(source.indexOf("private func acceptLoop"), source.indexOf("private func configure"));
  const runBridge = source.slice(source.indexOf("private func runBridge"), source.indexOf("@main"));

  assert.doesNotMatch(start.slice(0, start.indexOf("func startAccepting")), /Darwin\.listen/);
  assert.match(start, /func startAccepting[\s\S]*?hasRegisteredProcess[\s\S]*?Darwin\.listen/);
  assert.match(accept, /unixPeerProcessIdentifier[\s\S]*?bridgeAuthorizer\.authorize[\s\S]*?clientQueue\.async/);
  assert.match(runBridge, /try child\.run\(\)[\s\S]*?registerBridgeProcess[\s\S]*?startAccepting/);
  assert.match(runBridge, /terminationHandler[\s\S]*?revokeBridgeProcess/);
});

test("Host rejects stale deadlines without effects and executes a live request", async () => {
  const directory = await mkdtemp(join(tmpdir(), "vibe-pocket-host-deadline-test-"));
  const harnessPath = join(directory, "deadline-contract.swift");
  const executablePath = join(directory, "deadline-contract");
  const hostSource = await readFile(hostUrl, "utf8");
  const contractSource = hostSource.slice(
    hostSource.indexOf("private struct CodexControlRequest"),
    hostSource.indexOf("private struct SocketIdentity"),
  );
  const harness = String.raw`
import Darwin
import Foundation

${contractSource}

@main
enum DeadlineContractTest {
  static func envelope(deadlineMs: Int64) throws -> Data {
    try JSONSerialization.data(withJSONObject: [
      "action": "control",
      "arguments": ["new-task"],
      "input": "",
      "deadlineMs": deadlineMs,
    ])
  }

  static func main() throws {
    let acceptedAt: Int64 = 1_700_000_000_000

    do {
      _ = try decodeControlRequest(
        envelope(deadlineMs: acceptedAt),
        nowMilliseconds: acceptedAt
      )
      fatalError("An already-expired envelope was accepted.")
    } catch CodexControlRequestError.expired {
    }

    do {
      _ = try decodeControlRequest(
        envelope(deadlineMs: acceptedAt + maximumControlRequestLifetimeMilliseconds + 1),
        nowMilliseconds: acceptedAt
      )
      fatalError("An unbounded envelope deadline was accepted.")
    } catch CodexControlRequestError.invalid {
    }

    let queued = try decodeControlRequest(
      envelope(deadlineMs: acceptedAt + 500),
      nowMilliseconds: acceptedAt
    )
    var expiredEffects = 0
    do {
      _ = try executeControlRequestIfLive(
        queued,
        nowMilliseconds: acceptedAt + 500,
        peerIsAuthorized: { true },
        peerIsConnected: { true },
        perform: {
          expiredEffects += 1
          return "unexpected"
        }
      )
      fatalError("A request that expired in the queue was executed.")
    } catch CodexControlRequestError.expired {
    }
    guard expiredEffects == 0 else {
      fatalError("The expired request crossed the effect boundary.")
    }

    var disconnectedEffects = 0
    do {
      _ = try executeControlRequestIfLive(
        queued,
        nowMilliseconds: acceptedAt + 1,
        peerIsAuthorized: { true },
        peerIsConnected: { false },
        perform: {
          disconnectedEffects += 1
          return "unexpected"
        }
      )
      fatalError("A disconnected request was executed.")
    } catch CodexControlRequestError.disconnected {
    }
    guard disconnectedEffects == 0 else {
      fatalError("The disconnected request crossed the effect boundary.")
    }

    var liveEffects = 0
    let result = try executeControlRequestIfLive(
      queued,
      nowMilliseconds: acceptedAt + 499,
      peerIsAuthorized: { true },
      peerIsConnected: { true },
      perform: {
        liveEffects += 1
        return "executed"
      }
    )
    guard result == "executed", liveEffects == 1 else {
      fatalError("A live request did not execute exactly once.")
    }

    var socketDescriptors = [Int32](repeating: -1, count: 2)
    let socketResult = socketDescriptors.withUnsafeMutableBufferPointer { descriptors in
      Darwin.socketpair(AF_UNIX, SOCK_STREAM, 0, descriptors.baseAddress)
    }
    guard socketResult == 0 else { fatalError("Could not create the peer identity socket pair.") }
    defer { socketDescriptors.forEach { Darwin.close($0) } }
    guard unixPeerProcessIdentifier(socketDescriptors[0]) == Darwin.getpid() else {
      fatalError("LOCAL_PEERPID did not identify the Unix peer process.")
    }

    let authorizer = BridgeProcessAuthorizer()
    guard authorizer.authorize(processIdentifier: Darwin.getpid()) == nil else {
      fatalError("An unregistered process received control authorization.")
    }

    let child = Process()
    child.executableURL = URL(fileURLWithPath: "/bin/sleep")
    child.arguments = ["5"]
    let lifetime = BridgeProcessLifetime(process: child)
    try child.run()
    defer {
      if child.isRunning {
        child.terminate()
        child.waitUntilExit()
      }
    }
    try authorizer.register(lifetime)
    let childPID = child.processIdentifier
    guard let authorization = authorizer.authorize(processIdentifier: childPID) else {
      fatalError("The registered Bridge process was not authorized.")
    }
    guard !authorizer.permits(authorization, processIdentifier: childPID + 1) else {
      fatalError("A mismatched peer PID was authorized.")
    }

    let start = DispatchSemaphore(value: 0)
    let finished = DispatchGroup()
    for _ in 0..<2 {
      finished.enter()
      DispatchQueue.global().async {
        start.wait()
        for _ in 0..<2_000 {
          _ = authorizer.authorize(processIdentifier: childPID)
          _ = authorizer.permits(authorization, processIdentifier: childPID)
        }
        finished.leave()
      }
    }
    finished.enter()
    DispatchQueue.global().async {
      start.wait()
      authorizer.revoke(lifetime)
      finished.leave()
    }
    for _ in 0..<3 { start.signal() }
    guard finished.wait(timeout: .now() + 2) == .success else {
      fatalError("Concurrent authorization and revocation deadlocked.")
    }

    var unauthorizedEffects = 0
    do {
      _ = try executeControlRequestIfLive(
        queued,
        nowMilliseconds: acceptedAt + 1,
        peerIsAuthorized: {
          authorizer.permits(authorization, processIdentifier: childPID)
        },
        peerIsConnected: { true },
        perform: {
          unauthorizedEffects += 1
          return "unexpected"
        }
      )
      fatalError("A revoked Bridge process executed a control request.")
    } catch CodexControlRequestError.unauthorized {
    }
    guard unauthorizedEffects == 0 else {
      fatalError("An unauthorized request crossed the effect boundary.")
    }

    let replacement = Process()
    replacement.executableURL = URL(fileURLWithPath: "/bin/sleep")
    replacement.arguments = ["5"]
    let replacementLifetime = BridgeProcessLifetime(process: replacement)
    replacement.terminationHandler = { terminatedProcess in
      guard replacementLifetime.belongs(to: terminatedProcess) else { return }
      authorizer.revoke(replacementLifetime)
    }
    try replacement.run()
    try authorizer.register(replacementLifetime)
    let replacementPID = replacement.processIdentifier
    guard let replacementAuthorization = authorizer.authorize(
      processIdentifier: replacementPID
    ), !authorizer.permits(authorization, processIdentifier: childPID) else {
      fatalError("A Bridge restart reused the previous Process instance authorization.")
    }
    replacement.terminate()
    replacement.waitUntilExit()
    guard !authorizer.permits(
      replacementAuthorization,
      processIdentifier: replacementPID
    ) else {
      fatalError("An exited Bridge process retained control authorization.")
    }
  }
}
`;

  try {
    await writeFile(harnessPath, harness);
    await execFileAsync("/usr/bin/swiftc", [
      "-parse-as-library",
      harnessPath,
      "-o",
      executablePath,
    ], {
      env: {
        ...process.env,
        CLANG_MODULE_CACHE_PATH: join(tmpdir(), "vibe-pocket-clang-module-cache"),
        SWIFT_MODULECACHE_PATH: join(tmpdir(), "vibe-pocket-swift-module-cache"),
      },
    });
    await execFileAsync(executablePath);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

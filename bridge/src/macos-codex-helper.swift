import AppKit
import ApplicationServices
import Foundation

private enum HelperFailure: LocalizedError {
  case message(String)

  var errorDescription: String? {
    switch self {
    case .message(let value): return value
    }
  }
}

private func attributeString(_ element: AXUIElement, _ attribute: CFString) -> String {
  var value: CFTypeRef?
  guard AXUIElementCopyAttributeValue(element, attribute, &value) == .success, let value else {
    return ""
  }
  return value as? String ?? ""
}

private func attributeBool(_ element: AXUIElement, _ attribute: CFString) -> Bool {
  var value: CFTypeRef?
  guard AXUIElementCopyAttributeValue(element, attribute, &value) == .success, let value else {
    return false
  }
  return (value as? Bool) == true || (value as? NSNumber)?.boolValue == true
}

private func children(of element: AXUIElement) -> [AXUIElement] {
  var value: CFTypeRef?
  guard AXUIElementCopyAttributeValue(element, kAXChildrenAttribute as CFString, &value) == .success else {
    return []
  }
  return value as? [AXUIElement] ?? []
}

private func descendant(
  of element: AXUIElement,
  maxDepth: Int = 40,
  where predicate: (AXUIElement) -> Bool
) -> AXUIElement? {
  func visit(_ candidate: AXUIElement, depth: Int) -> AXUIElement? {
    if predicate(candidate) { return candidate }
    guard depth < maxDepth else { return nil }
    for child in children(of: candidate) {
      if let found = visit(child, depth: depth + 1) { return found }
    }
    return nil
  }
  return visit(element, depth: 0)
}

private func descendants(
  of element: AXUIElement,
  maxDepth: Int = 40,
  where predicate: (AXUIElement) -> Bool
) -> [AXUIElement] {
  var result = [AXUIElement]()
  func visit(_ candidate: AXUIElement, depth: Int) {
    if predicate(candidate) { result.append(candidate) }
    guard depth < maxDepth else { return }
    children(of: candidate).forEach { visit($0, depth: depth + 1) }
  }
  visit(element, depth: 0)
  return result
}

private func findChatGPT() throws -> NSRunningApplication {
  let applications = NSWorkspace.shared.runningApplications
  if let app = applications.first(where: { $0.bundleURL?.path == "/Applications/ChatGPT.app" }) {
    return app
  }
  if let app = applications.first(where: { $0.localizedName == "ChatGPT" }) {
    return app
  }
  throw HelperFailure.message("ChatGPT is not running on the M5.")
}

private func codexArea(for application: NSRunningApplication) throws -> AXUIElement {
  guard AXIsProcessTrusted() else {
    throw HelperFailure.message("Grant Accessibility permission to the Vibe Pocket Bridge host, then retry.")
  }
  let root = AXUIElementCreateApplication(application.processIdentifier)
  let areas = descendants(of: root, where: {
    attributeString($0, kAXRoleAttribute as CFString) == "AXWebArea"
      && attributeString($0, kAXTitleAttribute as CFString) == "Codex"
  })
  guard !areas.isEmpty else {
    throw HelperFailure.message("Open the Codex view in ChatGPT on the M5 before using Vibe Pocket.")
  }
  var selected = areas[0]
  var bestScore = -1
  for (index, area) in areas.enumerated() {
    let inputs = descendants(of: area, where: {
      attributeString($0, kAXRoleAttribute as CFString) == "AXTextArea"
    })
    let score = (inputs.count * 10_000)
      + (inputs.contains { attributeBool($0, kAXFocusedAttribute as CFString) } ? 1_000 : 0)
      + index
    if score >= bestScore {
      selected = area
      bestScore = score
    }
  }
  return selected
}

private func verifyForeground(_ application: NSRunningApplication) throws {
  guard let frontmost = NSWorkspace.shared.frontmostApplication else {
    throw HelperFailure.message("macOS did not report a frontmost application for desktop control.")
  }
  guard frontmost.processIdentifier == application.processIdentifier else {
    let name = frontmost.localizedName ?? "another application"
    if name.localizedCaseInsensitiveContains("uuremote") || name.localizedCaseInsensitiveContains("网易UU") {
      throw HelperFailure.message("NetEase UU Remote currently owns the foreground. Disconnect or close its remote-control window before using Vibe Pocket.")
    }
    throw HelperFailure.message("ChatGPT lost the foreground to \(name). Return ChatGPT to the front, then retry.")
  }
}

private func activate(_ application: NSRunningApplication) throws {
  guard application.activate(options: []) else {
    throw HelperFailure.message("ChatGPT could not be activated on the M5.")
  }
  usleep(350_000)
  try verifyForeground(application)
}

private func desktop(activateDesktop: Bool) throws -> (NSRunningApplication, AXUIElement) {
  let application = try findChatGPT()
  if activateDesktop { try activate(application) }
  return (application, try codexArea(for: application))
}

private enum DesktopControl: String, CaseIterable {
  case voice
  case stop
  case newTask = "new-task"
  case approve
  case reject

  var labels: Set<String> {
    switch self {
    case .voice: return ["听写", "dictation"]
    case .stop: return ["停止", "stop"]
    case .newTask: return ["新建任务", "new task"]
    case .approve: return ["允许", "批准", "allow", "approve"]
    case .reject: return ["拒绝", "deny", "reject"]
    }
  }

  var unavailableMessage: String {
    switch self {
    case .voice: return "The ChatGPT Codex Dictation control is not currently visible."
    case .stop: return "The ChatGPT Codex Stop control is not currently visible."
    case .newTask: return "The ChatGPT Codex New Task control is not currently visible."
    case .approve: return "There is no visible ChatGPT Codex approval control to approve."
    case .reject: return "There is no visible ChatGPT Codex approval control to reject."
    }
  }
}

private func controlButton(_ control: DesktopControl, in area: AXUIElement) -> AXUIElement? {
  descendant(of: area, where: {
    guard attributeString($0, kAXRoleAttribute as CFString) == "AXButton" else { return false }
    guard attributeBool($0, kAXEnabledAttribute as CFString) else { return false }
    let title = attributeString($0, kAXTitleAttribute as CFString)
      .trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    let description = attributeString($0, kAXDescriptionAttribute as CFString)
      .trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    return control.labels.contains(title) || control.labels.contains(description)
  })
}

private func prompt(in area: AXUIElement) -> AXUIElement? {
  let inputs = descendants(of: area, where: {
    attributeString($0, kAXRoleAttribute as CFString) == "AXTextArea"
  })
  return inputs.first(where: { attributeBool($0, kAXFocusedAttribute as CFString) }) ?? inputs.last
}

private let accessModeLabels: Set<String> = [
  "只读", "自动", "完全访问", "read only", "read-only", "auto", "full access",
]

private func accessModeButton(in area: AXUIElement) -> AXUIElement? {
  descendant(of: area, where: {
    guard attributeString($0, kAXRoleAttribute as CFString) == "AXButton",
          attributeBool($0, kAXEnabledAttribute as CFString) else { return false }
    let title = attributeString($0, kAXTitleAttribute as CFString)
      .trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    return accessModeLabels.contains(title)
  })
}

private func reasoningPopup(in area: AXUIElement) -> AXUIElement? {
  let elements = descendants(of: area, where: { _ in true })
  guard let inputIndex = elements.lastIndex(where: {
    attributeString($0, kAXRoleAttribute as CFString) == "AXTextArea"
  }) else { return nil }
  let end = min(elements.count, inputIndex + 24)
  guard inputIndex + 1 < end else { return nil }
  return elements[(inputIndex + 1)..<end].first(where: {
    attributeString($0, kAXRoleAttribute as CFString) == "AXPopUpButton"
      && attributeBool($0, kAXEnabledAttribute as CFString)
      && !attributeString($0, kAXTitleAttribute as CFString).isEmpty
  })
}

private struct AgentTarget {
  let element: AXUIElement
  let label: String
  let state: String
  let focused: Bool
}

private func agentTargets(in area: AXUIElement) -> [AgentTarget] {
  let suffixes = [
    (" 运行中", "executing"),
    (" running", "executing"),
    (" executing", "executing"),
    (" 等待中", "waiting"),
    (" waiting", "waiting"),
    (" 已完成", "complete"),
    (" complete", "complete"),
    (" done", "complete"),
    (" 错误", "error"),
    (" error", "error"),
  ]
  let forbidden = CharacterSet(charactersIn: "=/$\\\"'")
  var seen = Set<String>()
  var result = [AgentTarget]()
  for element in descendants(of: area, where: {
    attributeString($0, kAXRoleAttribute as CFString) == "AXButton"
  }) {
    let title = attributeString(element, kAXTitleAttribute as CFString)
      .trimmingCharacters(in: .whitespacesAndNewlines)
    let lowercased = title.lowercased()
    guard let match = suffixes.first(where: { lowercased.hasSuffix($0.0) }) else { continue }
    let label = String(title.dropLast(match.0.count)).trimmingCharacters(in: .whitespacesAndNewlines)
    guard !label.isEmpty, label.count <= 64,
          label.rangeOfCharacter(from: forbidden) == nil,
          !seen.contains(label) else { continue }
    seen.insert(label)
    result.append(AgentTarget(
      element: element,
      label: label,
      state: match.1,
      focused: attributeBool(element, kAXSelectedAttribute as CFString)
        || attributeBool(element, kAXFocusedAttribute as CFString)
    ))
  }
  return result
}

private func controlAvailability(in area: AXUIElement) -> [String: Bool] {
  let direct = Dictionary(uniqueKeysWithValues: DesktopControl.allCases.map { control in
    (control.rawValue, controlButton(control, in: area) != nil)
  })
  let agents = agentTargets(in: area)
  let isExecuting = controlButton(.stop, in: area) != nil
  return direct.merging([
    "clear-input": prompt(in: area) != nil,
    "focus-agent": !agents.isEmpty,
    "mode-cycle": accessModeButton(in: area) != nil && !isExecuting,
    "navigate": true,
    "reasoning": reasoningPopup(in: area) != nil && !isExecuting,
    "workflow": prompt(in: area) != nil && !isExecuting,
  ]) { _, new in new }
}

private func modeLabel(in area: AXUIElement) -> String? {
  guard let button = accessModeButton(in: area) else { return nil }
  return attributeString(button, kAXTitleAttribute as CFString)
    .trimmingCharacters(in: .whitespacesAndNewlines)
}

private func taskState(in area: AXUIElement, agents: [AgentTarget]) -> String {
  if agents.contains(where: { $0.state == "error" }) { return "error" }
  if controlButton(.approve, in: area) != nil || controlButton(.reject, in: area) != nil { return "waiting" }
  if controlButton(.stop, in: area) != nil || agents.contains(where: { $0.state == "executing" }) { return "executing" }
  if !agents.isEmpty && agents.allSatisfy({ $0.state == "complete" }) { return "complete" }
  return "idle"
}

private func statusReply(in area: AXUIElement) -> [String: Any] {
  let agents = agentTargets(in: area)
  let reasoning = reasoningPopup(in: area)
  let controls = controlAvailability(in: area)
  return [
    "ok": true,
    "available": true,
    "message": "Ready to control the visible ChatGPT Codex task.",
    "taskState": taskState(in: area, agents: agents),
    "controls": controls,
    "mode": ["available": controls["mode-cycle"] == true, "label": modeLabel(in: area) ?? ""],
    "reasoning": [
      "available": controls["reasoning"] == true,
      "label": reasoning.map { attributeString($0, kAXTitleAttribute as CFString) } ?? "",
    ],
    "agents": agents.enumerated().map { index, agent in
      ["id": "agent-\(index)", "label": agent.label, "state": agent.state, "focused": agent.focused]
    },
  ]
}

private func press(_ control: DesktopControl, in area: AXUIElement) throws {
  guard let button = controlButton(control, in: area) else {
    throw HelperFailure.message(control.unavailableMessage)
  }
  try clickElement(button)
}

private func postKey(_ code: CGKeyCode) throws {
  guard let source = CGEventSource(stateID: .hidSystemState),
        let down = CGEvent(keyboardEventSource: source, virtualKey: code, keyDown: true),
        let up = CGEvent(keyboardEventSource: source, virtualKey: code, keyDown: false) else {
    throw HelperFailure.message("macOS could not create the requested keyboard event.")
  }
  down.post(tap: .cghidEventTap)
  up.post(tap: .cghidEventTap)
}

private func elementFrame(_ element: AXUIElement) throws -> CGRect {
  var positionValue: CFTypeRef?
  var sizeValue: CFTypeRef?
  guard AXUIElementCopyAttributeValue(element, kAXPositionAttribute as CFString, &positionValue) == .success,
        AXUIElementCopyAttributeValue(element, kAXSizeAttribute as CFString, &sizeValue) == .success,
        let positionValue, let sizeValue,
        CFGetTypeID(positionValue) == AXValueGetTypeID(),
        CFGetTypeID(sizeValue) == AXValueGetTypeID() else {
    throw HelperFailure.message("ChatGPT did not expose the requested control position.")
  }
  var position = CGPoint.zero
  var size = CGSize.zero
  guard AXValueGetValue(positionValue as! AXValue, .cgPoint, &position),
        AXValueGetValue(sizeValue as! AXValue, .cgSize, &size),
        size.width > 0, size.height > 0 else {
    throw HelperFailure.message("ChatGPT exposed an invalid control frame.")
  }
  return CGRect(origin: position, size: size)
}

private func clickElement(_ element: AXUIElement) throws {
  AXUIElementPerformAction(element, "AXScrollToVisible" as CFString)
  let frame = try elementFrame(element)
  let point = CGPoint(x: frame.midX, y: frame.midY)
  let previousLocation = CGEvent(source: nil)?.location
  guard let source = CGEventSource(stateID: .hidSystemState),
        let move = CGEvent(mouseEventSource: source, mouseType: .mouseMoved, mouseCursorPosition: point, mouseButton: .left),
        let down = CGEvent(mouseEventSource: source, mouseType: .leftMouseDown, mouseCursorPosition: point, mouseButton: .left),
        let up = CGEvent(mouseEventSource: source, mouseType: .leftMouseUp, mouseCursorPosition: point, mouseButton: .left) else {
    throw HelperFailure.message("macOS could not create the requested pointer event.")
  }
  CGWarpMouseCursorPosition(point)
  move.post(tap: .cghidEventTap)
  usleep(100_000)
  down.post(tap: .cghidEventTap)
  usleep(80_000)
  up.post(tap: .cghidEventTap)
  usleep(180_000)
  if let previousLocation { CGWarpMouseCursorPosition(previousLocation) }
}

private func keyCode(for direction: String) throws -> CGKeyCode {
  switch direction {
  case "up": return 126
  case "down": return 125
  case "left": return 123
  case "right": return 124
  default: throw HelperFailure.message("Unsupported navigation direction.")
  }
}

private func stepPopup(_ popup: AXUIElement, direction: String) throws {
  try clickElement(popup)
  try postKey(125)
  usleep(60_000)
  try postKey(125)
  usleep(60_000)
  try postKey(124)
  usleep(120_000)
  try postKey(try keyCode(for: direction))
  usleep(60_000)
  try postKey(36)
  usleep(180_000)
}

private func stepButtonMenu(_ button: AXUIElement) throws {
  try clickElement(button)
  try postKey(125)
  usleep(60_000)
  try postKey(36)
  usleep(180_000)
}

private func clearInput(in area: AXUIElement) throws {
  guard let input = prompt(in: area) else {
    throw HelperFailure.message("The current Codex task has no accessible message input.")
  }
  guard AXUIElementSetAttributeValue(input, kAXValueAttribute as CFString, "" as CFTypeRef) == .success else {
    throw HelperFailure.message("ChatGPT did not clear the current input line.")
  }
}

private func launchWorkflow(_ text: String) throws {
  guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, text.count <= 4_000 else {
    throw HelperFailure.message("The configured Vibe Pocket workflow is invalid.")
  }
  let (application, area) = try desktop(activateDesktop: true)
  try press(.newTask, in: area)
  usleep(500_000)
  let nextArea = try codexArea(for: application)
  guard let input = prompt(in: nextArea) else {
    throw HelperFailure.message("The new Codex task did not expose a message input.")
  }
  guard AXUIElementSetAttributeValue(input, kAXFocusedAttribute as CFString, kCFBooleanTrue) == .success,
        AXUIElementSetAttributeValue(input, kAXValueAttribute as CFString, text as CFTypeRef) == .success else {
    throw HelperFailure.message("ChatGPT did not accept the configured workflow.")
  }
  usleep(150_000)
  try verifyForeground(application)
  try postKey(36)
}

private func readStandardInput() -> String {
  let data = try? FileHandle.standardInput.readToEnd()
  return data.flatMap { String(data: $0, encoding: .utf8) } ?? ""
}

private func reply(_ body: [String: Any]) {
  guard let data = try? JSONSerialization.data(withJSONObject: body),
        let text = String(data: data, encoding: .utf8) else { return }
  print(text)
}

do {
  let action = CommandLine.arguments.dropFirst().first ?? "status"
  switch action {
  case "status":
    let (_, area) = try desktop(activateDesktop: false)
    reply(statusReply(in: area))
  case "attach":
    _ = try desktop(activateDesktop: true)
    reply(["ok": true, "message": "Focused the visible ChatGPT Codex task."])
  case "control":
    guard let rawControl = CommandLine.arguments.dropFirst(2).first,
          let control = DesktopControl(rawValue: rawControl) else {
      throw HelperFailure.message("Unsupported Vibe Pocket desktop control.")
    }
    let (_, area) = try desktop(activateDesktop: true)
    try press(control, in: area)
    usleep(120_000)
    reply(["ok": true, "message": "Pressed the ChatGPT Codex \(control.rawValue) control."])
  case "navigate":
    guard let direction = CommandLine.arguments.dropFirst(2).first else {
      throw HelperFailure.message("A navigation direction is required.")
    }
    _ = try desktop(activateDesktop: true)
    try postKey(try keyCode(for: direction))
    reply(["ok": true, "message": "Navigated \(direction) in ChatGPT Codex."])
  case "mode-cycle":
    let (application, area) = try desktop(activateDesktop: true)
    guard controlButton(.stop, in: area) == nil,
          let button = accessModeButton(in: area) else {
      throw HelperFailure.message("The ChatGPT Codex access mode is not currently adjustable.")
    }
    let before = modeLabel(in: area) ?? ""
    try stepButtonMenu(button)
    let after = modeLabel(in: try codexArea(for: application)) ?? ""
    guard !after.isEmpty, after != before else {
      throw HelperFailure.message("The ChatGPT Codex access mode did not change.")
    }
    reply(["ok": true, "message": "Changed the ChatGPT Codex access mode to \(after)."])
  case "reasoning":
    guard let rawDelta = CommandLine.arguments.dropFirst(2).first,
          let delta = Int(rawDelta), delta == -1 || delta == 1 else {
      throw HelperFailure.message("Reasoning depth must move by one step.")
    }
    let (application, area) = try desktop(activateDesktop: true)
    guard controlButton(.stop, in: area) == nil,
          let popup = reasoningPopup(in: area) else {
      throw HelperFailure.message("The ChatGPT Codex reasoning level is not currently adjustable.")
    }
    let before = attributeString(popup, kAXTitleAttribute as CFString)
    try stepPopup(popup, direction: delta > 0 ? "down" : "up")
    let afterArea = try codexArea(for: application)
    let after = reasoningPopup(in: afterArea).map {
      attributeString($0, kAXTitleAttribute as CFString)
    } ?? ""
    guard !after.isEmpty, after != before else {
      throw HelperFailure.message("The ChatGPT Codex reasoning level did not change.")
    }
    reply(["ok": true, "message": "Changed the ChatGPT Codex reasoning level to \(after)."])
  case "clear-input":
    let (_, area) = try desktop(activateDesktop: true)
    try clearInput(in: area)
    reply(["ok": true, "message": "Cleared the ChatGPT Codex input line."])
  case "focus-agent":
    guard let rawIndex = CommandLine.arguments.dropFirst(2).first,
          let index = Int(rawIndex), index >= 0 else {
      throw HelperFailure.message("A valid Codex agent slot is required.")
    }
    let (_, area) = try desktop(activateDesktop: true)
    let agents = agentTargets(in: area)
    guard agents.indices.contains(index) else {
      throw HelperFailure.message("That Codex agent slot is not currently visible.")
    }
    try clickElement(agents[index].element)
    reply(["ok": true, "message": "Focused Codex agent \(agents[index].label)."])
  case "workflow":
    try launchWorkflow(readStandardInput())
    reply(["ok": true, "message": "Started the configured workflow in a new Codex task."])
  default:
    throw HelperFailure.message("Unsupported Vibe Pocket desktop action.")
  }
} catch {
  let message = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
  FileHandle.standardError.write(Data(message.utf8))
  FileHandle.standardError.write(Data("\n".utf8))
  exit(1)
}

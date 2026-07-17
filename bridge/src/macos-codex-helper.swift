import AppKit
import ApplicationServices
import CryptoKit
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

private func visibleChildren(of element: AXUIElement) -> [AXUIElement] {
  var value: CFTypeRef?
  if AXUIElementCopyAttributeValue(element, kAXVisibleChildrenAttribute as CFString, &value) == .success,
     let visible = value as? [AXUIElement] {
    return visible
  }
  return children(of: element)
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

private func codexWebAreas(of root: AXUIElement, maxDepth: Int = 16) -> [AXUIElement] {
  var result = [AXUIElement]()
  func visit(_ candidate: AXUIElement, depth: Int) {
    let role = attributeString(candidate, kAXRoleAttribute as CFString)
    if role == "AXWebArea" {
      if attributeString(candidate, kAXTitleAttribute as CFString) == "Codex" {
        result.append(candidate)
      }
      return
    }
    guard depth < maxDepth else { return }
    children(of: candidate).forEach { visit($0, depth: depth + 1) }
  }
  visit(root, depth: 0)
  return result
}

private func requestAccessibilityPermission() -> Bool {
  let options = [
    kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true,
  ] as CFDictionary
  return AXIsProcessTrustedWithOptions(options)
}

private func verifyDesktopSessionUnlocked() throws {
  let session = CGSessionCopyCurrentDictionary() as? [String: Any]
  let locked = session?["CGSSessionScreenIsLocked"] as? Bool == true
  let loginWindowIsFrontmost = NSWorkspace.shared.frontmostApplication?.bundleIdentifier == "com.apple.loginwindow"
  if locked || loginWindowIsFrontmost {
    throw HelperFailure.message("Unlock the M5 before using Vibe Pocket desktop controls.")
  }
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
  let areas = codexWebAreas(of: root)
  guard !areas.isEmpty else {
    throw HelperFailure.message("Open the Codex view in ChatGPT on the M5 before using Vibe Pocket.")
  }
  return areas.last(where: { attributeBool($0, kAXFocusedAttribute as CFString) }) ?? areas.last!
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
  try verifyDesktopSessionUnlocked()
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
    case .voice: return [
      "听写", "开始听写", "停止听写", "语音输入", "开始语音输入", "停止语音输入",
      "dictation", "start dictation", "stop dictation", "voice input", "start voice input",
      "stop voice input", "record", "stop recording",
    ]
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

private struct AreaIndex {
  let elements: [AXUIElement]
  let roles: [String]
  let buttons: [AXUIElement]
  let textAreas: [AXUIElement]

  init(_ area: AXUIElement) {
    let leafRoles: Set<String> = [
      "AXButton", "AXCheckBox", "AXHeading", "AXImage", "AXLink", "AXMenuItem",
      "AXPopUpButton", "AXRadioButton", "AXStaticText", "AXTextArea",
    ]
    var collectedElements = [AXUIElement]()
    var collectedRoles = [String]()
    var collectedButtons = [AXUIElement]()
    var collectedTextAreas = [AXUIElement]()

    func visit(_ candidate: AXUIElement, depth: Int) {
      let role = attributeString(candidate, kAXRoleAttribute as CFString)
      collectedElements.append(candidate)
      collectedRoles.append(role)
      if role == "AXButton" { collectedButtons.append(candidate) }
      if role == "AXTextArea" { collectedTextAreas.append(candidate) }
      guard depth < 28, !leafRoles.contains(role) else { return }
      visibleChildren(of: candidate).forEach { visit($0, depth: depth + 1) }
    }

    visit(area, depth: 0)
    elements = collectedElements
    roles = collectedRoles
    buttons = collectedButtons
    textAreas = collectedTextAreas
  }
}

private func controlButton(_ control: DesktopControl, in area: AXUIElement) -> AXUIElement? {
  controlButton(control, in: AreaIndex(area))
}

private func controlButton(_ control: DesktopControl, in index: AreaIndex) -> AXUIElement? {
  index.buttons.first(where: {
    guard attributeBool($0, kAXEnabledAttribute as CFString) else { return false }
    let title = attributeString($0, kAXTitleAttribute as CFString)
      .trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    let description = attributeString($0, kAXDescriptionAttribute as CFString)
      .trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    return control.labels.contains(title) || control.labels.contains(description)
  })
}

private func prompt(in area: AXUIElement) -> AXUIElement? {
  prompt(in: AreaIndex(area))
}

private func prompt(in index: AreaIndex) -> AXUIElement? {
  index.textAreas.first(where: { attributeBool($0, kAXFocusedAttribute as CFString) }) ?? index.textAreas.last
}

private let accessModeLabels: Set<String> = [
  "只读", "自动", "完全访问", "read only", "read-only", "auto", "full access",
]

private func accessModeButton(in area: AXUIElement) -> AXUIElement? {
  accessModeButton(in: AreaIndex(area))
}

private func accessModeButton(in index: AreaIndex) -> AXUIElement? {
  index.buttons.first(where: {
    guard attributeBool($0, kAXEnabledAttribute as CFString) else { return false }
    let title = attributeString($0, kAXTitleAttribute as CFString)
      .trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    return accessModeLabels.contains(title)
  })
}

private func reasoningPopup(in area: AXUIElement) -> AXUIElement? {
  reasoningPopup(in: AreaIndex(area))
}

private func reasoningPopup(in index: AreaIndex) -> AXUIElement? {
  guard let inputIndex = index.roles.lastIndex(of: "AXTextArea") else { return nil }
  let end = min(index.elements.count, inputIndex + 24)
  guard inputIndex + 1 < end else { return nil }
  for candidateIndex in (inputIndex + 1)..<end {
    let candidate = index.elements[candidateIndex]
    let title = attributeString(candidate, kAXTitleAttribute as CFString)
    if index.roles[candidateIndex] == "AXPopUpButton",
       attributeBool(candidate, kAXEnabledAttribute as CFString),
       isReasoningLabel(title) {
      return candidate
    }
  }
  return nil
}

private func isReasoningLabel(_ value: String) -> Bool {
  let label = value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
  let englishLevels = ["minimal", "low", "medium", "high", "extra high", "xhigh", "max"]
  let chineseLevels = ["最低", "低", "中", "高", "极高", "最高"]
  return englishLevels.contains(where: { label.range(of: "\\b\($0)\\b", options: .regularExpression) != nil })
    || chineseLevels.contains(where: label.contains)
}

private struct AgentTarget {
  let id: String
  let element: AXUIElement
  let label: String
  let state: String
  let focused: Bool
}

private func agentTargets(in area: AXUIElement) -> [AgentTarget] {
  agentTargets(in: AreaIndex(area))
}

private func agentTargets(in index: AreaIndex) -> [AgentTarget] {
  let suffixes = [
    (" 空闲", "idle"),
    (" idle", "idle"),
    (" 思考中", "thinking"),
    (" thinking", "thinking"),
    (" 运行中", "executing"),
    (" running", "executing"),
    (" executing", "executing"),
    (" 等待中", "waiting"),
    (" waiting", "waiting"),
    (" 需要用户批准/疑问", "waiting"),
    (" 需要用户批准", "waiting"),
    (" needs user approval / question", "waiting"),
    (" needs user approval", "waiting"),
    (" needs input", "waiting"),
    (" 已完成", "complete"),
    (" complete", "complete"),
    (" done", "complete"),
    (" 未读聊天", "unread"),
    (" 未读", "unread"),
    (" unread chat", "unread"),
    (" unread", "unread"),
    (" 错误", "error"),
    (" error", "error"),
  ]
  var seen = Set<String>()
  var result = [AgentTarget]()
  for element in index.buttons {
    let title = attributeString(element, kAXTitleAttribute as CFString)
      .trimmingCharacters(in: .whitespacesAndNewlines)
    let lowercased = title.lowercased()
    guard let match = suffixes.first(where: { lowercased.hasSuffix($0.0) }) else { continue }
    let label = String(title.dropLast(match.0.count)).trimmingCharacters(in: .whitespacesAndNewlines)
    guard !label.isEmpty, label.count <= 64,
          label.rangeOfCharacter(from: .controlCharacters) == nil else { continue }
    let id = stableAgentID(for: element, label: label)
    guard !seen.contains(id) else { continue }
    seen.insert(id)
    result.append(AgentTarget(
      id: id,
      element: element,
      label: label,
      state: match.1,
      focused: attributeBool(element, kAXSelectedAttribute as CFString)
        || attributeBool(element, kAXFocusedAttribute as CFString)
    ))
  }
  return result
}

private func stableAgentID(for element: AXUIElement, label: String) -> String {
  let identifier = attributeString(element, kAXIdentifierAttribute as CFString)
    .trimmingCharacters(in: .whitespacesAndNewlines)
  let source = "\(identifier.isEmpty ? "label" : identifier)\u{0}\(label)"
  let digest = SHA256.hash(data: Data(source.utf8))
  let hex = digest.prefix(12).map { String(format: "%02x", $0) }.joined()
  return "agent-\(hex)"
}

private let activeVoiceLabels: Set<String> = [
  "停止听写", "停止语音输入", "stop dictation", "stop voice input", "stop recording",
]

private func voiceIsActive(in index: AreaIndex) -> Bool {
  guard let button = controlButton(.voice, in: index) else { return false }
  let title = attributeString(button, kAXTitleAttribute as CFString)
    .trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
  let description = attributeString(button, kAXDescriptionAttribute as CFString)
    .trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
  return activeVoiceLabels.contains(title) || activeVoiceLabels.contains(description)
}

private func voiceIsActive(in area: AXUIElement) -> Bool {
  voiceIsActive(in: AreaIndex(area))
}

private func controlAvailability(in index: AreaIndex) -> [String: Bool] {
  let direct = Dictionary(uniqueKeysWithValues: DesktopControl.allCases.map { control in
    (control.rawValue, controlButton(control, in: index) != nil)
  })
  let agents = agentTargets(in: index)
  let isExecuting = controlButton(.stop, in: index) != nil
  return direct.merging([
    "approve": direct["approve"] == true || prompt(in: index) != nil,
    "reject": true,
    "clear-input": prompt(in: index) != nil,
    "focus-agent": !agents.isEmpty,
    "mode-cycle": accessModeButton(in: index) != nil && !isExecuting,
    "navigate": true,
    "reasoning": reasoningPopup(in: index) != nil && !isExecuting,
    "workflow": prompt(in: index) != nil && !isExecuting,
  ]) { _, new in new }
}

private func modeLabel(in area: AXUIElement) -> String? {
  modeLabel(in: AreaIndex(area))
}

private func modeLabel(in index: AreaIndex) -> String? {
  guard let button = accessModeButton(in: index) else { return nil }
  return attributeString(button, kAXTitleAttribute as CFString)
    .trimmingCharacters(in: .whitespacesAndNewlines)
}

private func taskState(in index: AreaIndex, agents: [AgentTarget]) -> String {
  if agents.contains(where: { $0.state == "error" }) { return "error" }
  if controlButton(.approve, in: index) != nil || controlButton(.reject, in: index) != nil { return "waiting" }
  if controlButton(.stop, in: index) != nil || agents.contains(where: { $0.state == "executing" || $0.state == "thinking" }) {
    return "executing"
  }
  if !agents.isEmpty && agents.allSatisfy({ $0.state == "complete" || $0.state == "unread" }) { return "complete" }
  return "idle"
}

private func statusReply(in area: AXUIElement) -> [String: Any] {
  let index = AreaIndex(area)
  let agents = agentTargets(in: index)
  let reasoning = reasoningPopup(in: index)
  let controls = controlAvailability(in: index)
  return [
    "ok": true,
    "available": true,
    "message": "Ready to control the visible ChatGPT Codex task.",
    "taskState": taskState(in: index, agents: agents),
    "controls": controls,
    "voice": ["available": controls["voice"] == true, "active": voiceIsActive(in: index)],
    "mode": ["available": controls["mode-cycle"] == true, "label": modeLabel(in: index) ?? ""],
    "reasoning": [
      "available": controls["reasoning"] == true,
      "label": reasoning.map { attributeString($0, kAXTitleAttribute as CFString) } ?? "",
    ],
    "agents": agents.enumerated().map { index, agent in
      ["id": agent.id, "label": agent.label, "state": agent.state, "focused": agent.focused]
    },
  ]
}

private func press(_ control: DesktopControl, in area: AXUIElement) throws {
  if let button = controlButton(control, in: area) {
    try clickElement(button)
    return
  }
  switch control {
  case .approve:
    try postKey(36)
  case .reject:
    try postKey(53)
  default:
    throw HelperFailure.message(control.unavailableMessage)
  }
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
  for _ in 0..<8 {
    usleep(250_000)
    let submittedArea = try codexArea(for: application)
    if controlButton(.stop, in: submittedArea) != nil { return }
    guard let submittedPrompt = prompt(in: submittedArea) else { return }
    let visibleDraft = attributeString(submittedPrompt, kAXValueAttribute as CFString)
    if visibleDraft != text { return }
  }
  throw HelperFailure.message("The configured workflow remained in the Codex draft and was not submitted.")
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
  case "request-accessibility":
    let trusted = requestAccessibilityPermission()
    reply([
      "ok": true,
      "available": trusted,
      "message": trusted
        ? "Vibe Pocket Accessibility permission is enabled."
        : "Approve Vibe Pocket in the macOS Accessibility prompt, then retry.",
    ])
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
  case "voice-start", "voice-stop":
    let desiredActive = action == "voice-start"
    let (application, area) = try desktop(activateDesktop: true)
    guard controlButton(.voice, in: area) != nil else {
      throw HelperFailure.message(DesktopControl.voice.unavailableMessage)
    }
    if voiceIsActive(in: area) != desiredActive {
      try press(.voice, in: area)
      var reachedTargetState = false
      for _ in 0..<8 {
        usleep(200_000)
        let updatedArea = try codexArea(for: application)
        if voiceIsActive(in: updatedArea) == desiredActive {
          reachedTargetState = true
          break
        }
      }
      guard reachedTargetState else {
        throw HelperFailure.message("The ChatGPT Codex dictation state did not change.")
      }
    }
    reply([
      "ok": true,
      "message": desiredActive ? "Started Codex dictation." : "Stopped Codex dictation.",
    ])
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
    guard let agentID = CommandLine.arguments.dropFirst(2).first,
          agentID.hasPrefix("agent-"), agentID.count <= 80 else {
      throw HelperFailure.message("A valid Codex agent ID is required.")
    }
    let (_, area) = try desktop(activateDesktop: true)
    let agents = agentTargets(in: area)
    guard let agent = agents.first(where: { $0.id == agentID }) else {
      throw HelperFailure.message("That Codex agent is no longer visible.")
    }
    try clickElement(agent.element)
    reply(["ok": true, "message": "Focused Codex agent \(agent.label)."])
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

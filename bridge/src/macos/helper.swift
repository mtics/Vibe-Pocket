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

private func attributeStrings(_ element: AXUIElement, _ attribute: CFString) -> [String] {
  var value: CFTypeRef?
  guard AXUIElementCopyAttributeValue(element, attribute, &value) == .success, let value else {
    return []
  }
  if let strings = value as? [String] { return strings }
  if let string = value as? String { return string.split(separator: " ").map(String.init) }
  return []
}

private func scalarAttributeString(_ element: AXUIElement, _ attribute: CFString) -> String {
  var value: CFTypeRef?
  guard AXUIElementCopyAttributeValue(element, attribute, &value) == .success, let value else {
    return ""
  }
  if let string = value as? String { return string }
  if let number = value as? NSNumber { return number.stringValue }
  return ""
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

private func codexWebAreas(
  of root: AXUIElement,
  maxDepth: Int = 16,
  using childElements: (AXUIElement) -> [AXUIElement] = visibleChildren
) -> [AXUIElement] {
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
    childElements(candidate).forEach { visit($0, depth: depth + 1) }
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

private let chatGPTBundleIdentifier = "com.openai.codex"
private let chatGPTApplicationPath = "/Applications/ChatGPT.app"

private func findChatGPT() throws -> NSRunningApplication {
  let candidates = NSRunningApplication.runningApplications(withBundleIdentifier: chatGPTBundleIdentifier)
  let app = candidates.first { application in
    guard let bundleURL = application.bundleURL else { return true }
    return bundleURL.standardizedFileURL.path == chatGPTApplicationPath
  }
  if let app { return app }
  throw HelperFailure.message("ChatGPT is not running on the M5.")
}

private func codexArea(for application: NSRunningApplication) throws -> AXUIElement {
  guard AXIsProcessTrusted() else {
    throw HelperFailure.message("Grant Accessibility permission to the Vibe Pocket Bridge host, then retry.")
  }
  return try codexArea(in: focusedWindow(for: application))
}

private func applicationWindows(_ root: AXUIElement) -> [AXUIElement] {
  var value: CFTypeRef?
  guard AXUIElementCopyAttributeValue(root, kAXWindowsAttribute as CFString, &value) == .success else {
    return []
  }
  return value as? [AXUIElement] ?? []
}

private func windowAttribute(_ root: AXUIElement, _ attribute: CFString) -> AXUIElement? {
  var value: CFTypeRef?
  guard AXUIElementCopyAttributeValue(root, attribute, &value) == .success else { return nil }
  return value as! AXUIElement?
}

private func isCodexContentWindow(_ window: AXUIElement) -> Bool {
  let subrole = attributeString(window, kAXSubroleAttribute as CFString)
  let title = attributeString(window, kAXTitleAttribute as CFString)
  guard attributeString(window, kAXRoleAttribute as CFString) == kAXWindowRole,
        [kAXStandardWindowSubrole, kAXDialogSubrole].contains(subrole),
        !title.hasPrefix("Codex Pet"),
        codexWebAreas(of: window).first != nil else { return false }
  return true
}

private func matchingWindow(_ requested: AXUIElement?, in candidates: [AXUIElement]) -> AXUIElement? {
  guard let requested else { return nil }
  return candidates.first { CFEqual($0, requested) }
}

private func focusedWindow(for application: NSRunningApplication) throws -> AXUIElement {
  let root = AXUIElementCreateApplication(application.processIdentifier)
  let candidates = applicationWindows(root).filter(isCodexContentWindow)
  if let focused = matchingWindow(
    windowAttribute(root, kAXFocusedWindowAttribute as CFString),
    in: candidates
  ) {
    return focused
  }
  if let main = matchingWindow(
    windowAttribute(root, kAXMainWindowAttribute as CFString),
    in: candidates
  ) {
    return main
  }
  let restored = candidates.filter {
    !attributeBool($0, kAXMinimizedAttribute as CFString)
  }
  if restored.count == 1 { return restored[0] }
  if candidates.count == 1 { return candidates[0] }
  if candidates.isEmpty {
    throw HelperFailure.message("ChatGPT did not expose a visible Codex window.")
  }
  throw HelperFailure.message(
    "ChatGPT has multiple visible Codex windows but none is focused. Focus the intended window, then retry."
  )
}

private func windowDiagnostics(for application: NSRunningApplication) -> [String: Any] {
  let root = AXUIElementCreateApplication(application.processIdentifier)
  let focused = windowAttribute(root, kAXFocusedWindowAttribute as CFString)
  let main = windowAttribute(root, kAXMainWindowAttribute as CFString)
  let windows = applicationWindows(root).map { window -> [String: Any] in
    [
      "title": attributeString(window, kAXTitleAttribute as CFString),
      "role": attributeString(window, kAXRoleAttribute as CFString),
      "subrole": attributeString(window, kAXSubroleAttribute as CFString),
      "minimized": attributeBool(window, kAXMinimizedAttribute as CFString),
      "focused": focused.map { CFEqual($0, window) } ?? false,
      "main": main.map { CFEqual($0, window) } ?? false,
      "children": children(of: window).count,
      "visibleChildren": visibleChildren(of: window).count,
      "structuralCodexAreas": codexWebAreas(of: window, using: children).count,
      "visibleCodexAreas": codexWebAreas(of: window).count,
    ]
  }
  return ["ok": true, "windows": windows]
}

private func diagnosticControls(in area: AXUIElement) -> [[String: Any]] {
  let roles: Set<String> = [
    "AXButton", "AXCheckBox", "AXLink", "AXMenuItem", "AXPopUpButton",
    "AXRadioButton", "AXTextArea", "AXWebArea",
  ]
  return descendants(of: area, maxDepth: 28) { element in
    roles.contains(attributeString(element, kAXRoleAttribute as CFString))
  }.prefix(240).map { element -> [String: Any] in
    let role = attributeString(element, kAXRoleAttribute as CFString)
    return [
      "role": role,
      "title": attributeString(element, kAXTitleAttribute as CFString),
      "description": attributeString(element, kAXDescriptionAttribute as CFString),
      "help": attributeString(element, kAXHelpAttribute as CFString),
      "identifier": attributeString(element, kAXIdentifierAttribute as CFString),
      "domIdentifier": scalarAttributeString(element, "AXDOMIdentifier" as CFString),
      "url": role == "AXWebArea"
        ? attributeString(element, kAXURLAttribute as CFString)
        : "",
      "enabled": attributeBool(element, kAXEnabledAttribute as CFString),
      "classes": attributeStrings(element, "AXDOMClassList" as CFString),
    ]
  }
}

private func controlDiagnostics(for application: NSRunningApplication) -> [String: Any] {
  let root = AXUIElementCreateApplication(application.processIdentifier)
  let windows = applicationWindows(root).compactMap { window -> [String: Any]? in
    guard let area = codexWebAreas(of: window, using: children).first else { return nil }
    return [
      "title": attributeString(window, kAXTitleAttribute as CFString),
      "subrole": attributeString(window, kAXSubroleAttribute as CFString),
      "minimized": attributeBool(window, kAXMinimizedAttribute as CFString),
      "controls": diagnosticControls(in: area),
    ]
  }
  return ["ok": true, "windows": windows]
}

private func selectorDiagnostics(for application: NSRunningApplication) throws -> [String: Any] {
  let area = try codexArea(for: application)
  guard let control = reasoningControl(in: area) else {
    throw HelperFailure.message("ChatGPT did not expose its model and reasoning selector.")
  }
  var valueSettable = DarwinBoolean(false)
  let settableResult = AXUIElementIsAttributeSettable(
    control.element,
    kAXValueAttribute as CFString,
    &valueSettable
  )
  var actionNames: CFArray?
  AXUIElementCopyActionNames(control.element, &actionNames)
  return [
    "ok": true,
    "role": attributeString(control.element, kAXRoleAttribute as CFString),
    "title": attributeString(control.element, kAXTitleAttribute as CFString),
    "value": attributeString(control.element, kAXValueAttribute as CFString),
    "description": attributeString(control.element, kAXDescriptionAttribute as CFString),
    "actions": (actionNames as? [String]) ?? [],
    "valueSettable": settableResult == .success && valueSettable.boolValue,
  ]
}

private func codexArea(in root: AXUIElement) throws -> AXUIElement {
  let areas = codexWebAreas(of: root)
  guard !areas.isEmpty else {
    throw HelperFailure.message("Open the Codex view in ChatGPT on the M5 before using Vibe Pocket.")
  }
  return areas.max(by: { codexAreaScore($0) < codexAreaScore($1) })!
}

private func codexAreaScore(_ area: AXUIElement) -> Int {
  let index = AreaIndex(area)
  let composerWeight = index.textAreas.isEmpty ? 0 : 1_000_000
  return composerWeight + index.buttons.count * 1_000 + index.elements.count
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

private func isForeground(_ application: NSRunningApplication) -> Bool {
  NSWorkspace.shared.frontmostApplication?.processIdentifier == application.processIdentifier
}

@discardableResult
private func requestForeground(
  _ application: NSRunningApplication,
  window: AXUIElement? = nil
) -> Bool {
  _ = application.unhide()
  let activated = application.activate(options: [])
  let applicationElement = AXUIElementCreateApplication(application.processIdentifier)
  let promoted = AXUIElementSetAttributeValue(
    applicationElement,
    kAXFrontmostAttribute as CFString,
    kCFBooleanTrue
  ) == .success
  if let window {
    _ = AXUIElementSetAttributeValue(
      window,
      kAXMainAttribute as CFString,
      kCFBooleanTrue
    )
    _ = AXUIElementSetAttributeValue(
      window,
      kAXFocusedAttribute as CFString,
      kCFBooleanTrue
    )
    _ = AXUIElementPerformAction(window, kAXRaiseAction as CFString)
  }
  return activated || promoted
}

private func waitForStableForeground(
  _ application: NSRunningApplication,
  window: AXUIElement? = nil
) -> Bool {
  var consecutiveObservations = 0
  for attempt in 0..<18 {
    if isForeground(application) {
      consecutiveObservations += 1
      if consecutiveObservations >= 3 { return true }
    } else {
      consecutiveObservations = 0
      if attempt == 6 || attempt == 12 {
        _ = requestForeground(application, window: window)
      }
    }
    if attempt + 1 < 18 { usleep(100_000) }
  }
  return false
}

private func activate(
  _ application: NSRunningApplication,
  window: AXUIElement? = nil
) throws {
  if isForeground(application) { return }
  guard requestForeground(application, window: window) else {
    throw HelperFailure.message("ChatGPT could not be activated on the M5.")
  }
  if !waitForStableForeground(application, window: window) { try verifyForeground(application) }
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
  let buttonPaths: [String]
  let textAreas: [AXUIElement]

  init(
    _ area: AXUIElement,
    using childElements: (AXUIElement) -> [AXUIElement] = children
  ) {
    let leafRoles: Set<String> = [
      "AXButton", "AXCheckBox", "AXHeading", "AXImage", "AXLink", "AXMenuItem",
      "AXList", "AXPopUpButton", "AXRadioButton", "AXStaticText", "AXTextArea",
    ]
    var collectedElements = [AXUIElement]()
    var collectedRoles = [String]()
    var collectedButtons = [AXUIElement]()
    var collectedButtonPaths = [String]()
    var collectedTextAreas = [AXUIElement]()

    func visit(_ candidate: AXUIElement, depth: Int, path: String) {
      let role = attributeString(candidate, kAXRoleAttribute as CFString)
      collectedElements.append(candidate)
      collectedRoles.append(role)
      if role == "AXButton" {
        collectedButtons.append(candidate)
        collectedButtonPaths.append(path)
      }
      if role == "AXTextArea" { collectedTextAreas.append(candidate) }
      guard depth < 28, !leafRoles.contains(role) else { return }
      for (childIndex, child) in childElements(candidate).enumerated() {
        visit(child, depth: depth + 1, path: "\(path).\(childIndex)")
      }
    }

    visit(area, depth: 0, path: "root")
    elements = collectedElements
    roles = collectedRoles
    buttons = collectedButtons
    buttonPaths = collectedButtonPaths
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

private func draftText(in index: AreaIndex) -> String {
  guard let input = prompt(in: index) else { return "" }
  return attributeString(input, kAXValueAttribute as CFString)
    .trimmingCharacters(in: .whitespacesAndNewlines)
}

private func hasDraft(in index: AreaIndex) -> Bool {
  !draftText(in: index).isEmpty
}

private let accessModeLabels: Set<String> = [
  "只读", "自动", "请求批准", "替我审批", "完全访问", "完全访问权限", "自定义", "自定义 (config.toml)",
  "read only", "read-only", "auto", "auto approve", "approve for me",
  "full access", "ask approval", "ask for approval", "request approval", "custom", "custom (config.toml)",
]

private func accessModeKey(for label: String) -> String? {
  switch label.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
  case "只读", "read only", "read-only": return "read-only"
  case "请求批准", "ask approval", "ask for approval", "request approval": return "request"
  case "自动", "替我审批", "auto", "auto approve", "approve for me": return "auto"
  case "完全访问", "完全访问权限", "full access": return "full"
  case "自定义", "自定义 (config.toml)", "custom", "custom (config.toml)": return "custom"
  default: return nil
  }
}

private func accessModeLabel(of element: AXUIElement) -> String? {
  [
    attributeString(element, kAXTitleAttribute as CFString),
    attributeString(element, kAXDescriptionAttribute as CFString),
    attributeString(element, kAXValueAttribute as CFString),
  ].lazy.compactMap { value -> String? in
    let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
    return accessModeLabels.contains(trimmed.lowercased()) ? trimmed : nil
  }.first
}

private func accessMenuOptionLabel(in item: AXUIElement) -> String? {
  accessModeLabel(of: item) ?? descendant(of: item, maxDepth: 3) {
    accessModeLabel(of: $0) != nil
  }.flatMap { accessModeLabel(of: $0) }
}

private func accessModeButton(in area: AXUIElement) -> AXUIElement? {
  accessModeButton(in: AreaIndex(area))
}

private func accessModeButton(in index: AreaIndex) -> AXUIElement? {
  index.buttons.first(where: {
    guard attributeBool($0, kAXEnabledAttribute as CFString) else { return false }
    return accessModeLabel(of: $0) != nil
  })
}

private enum ReasoningLevel: String, CaseIterable {
  case minimal
  case low
  case medium
  case high
  case xhigh
  case max
  case ultra

  private static let aliases: [(ReasoningLevel, [String])] = [
    (.ultra, ["ultra"]),
    (.max, ["maximum", "max", "最大", "最高"]),
    (.xhigh, ["extra high", "xhigh", "极高"]),
    (.minimal, ["minimal", "最小", "最低"]),
    (.medium, ["medium", "中"]),
    (.high, ["high", "高"]),
    (.low, ["low", "轻度", "低"]),
  ]

  static func parse(from label: String) -> ReasoningLevel? {
    let normalized = label.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    for (level, candidates) in aliases {
      for candidate in candidates.sorted(by: { $0.count > $1.count }) {
        if normalized == candidate || normalized.hasSuffix(" \(candidate)") { return level }
      }
    }
    return nil
  }

  static func modelLabel(from label: String) -> String {
    let trimmed = label.trimmingCharacters(in: .whitespacesAndNewlines)
    let normalized = trimmed.lowercased()
    let candidates = aliases.flatMap(\.1).sorted(by: { $0.count > $1.count })
    for candidate in candidates {
      if normalized == candidate { return "" }
      let suffix = " \(candidate)"
      if normalized.hasSuffix(suffix) {
        return String(trimmed.dropLast(suffix.count))
          .trimmingCharacters(in: .whitespacesAndNewlines)
      }
    }
    return ""
  }
}

private struct ReasoningControl {
  let element: AXUIElement
  let label: String
  let modelLabel: String
  let level: ReasoningLevel?
  let ambiguous: Bool
}

private let reasoningRoleHints = ["reasoning", "reasoning effort", "推理", "推理强度"]

private func reasoningControlLabel(_ element: AXUIElement) -> String {
  [
    attributeString(element, kAXTitleAttribute as CFString),
    attributeString(element, kAXValueAttribute as CFString),
    attributeString(element, kAXDescriptionAttribute as CFString),
  ].lazy.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
    .first(where: { !$0.isEmpty }) ?? ""
}

private func hasReasoningRoleHint(_ element: AXUIElement) -> Bool {
  let semantics = [
    attributeString(element, kAXDescriptionAttribute as CFString),
    attributeString(element, kAXHelpAttribute as CFString),
    attributeString(element, kAXIdentifierAttribute as CFString),
    scalarAttributeString(element, "AXDOMIdentifier" as CFString),
  ].joined(separator: " ").lowercased()
  return reasoningRoleHints.contains(where: semantics.contains)
}

private func reasoningControl(in area: AXUIElement) -> ReasoningControl? {
  reasoningControl(in: AreaIndex(area))
}

private func reasoningControl(in index: AreaIndex) -> ReasoningControl? {
  guard let inputIndex = index.roles.lastIndex(of: "AXTextArea") else { return nil }
  let end = min(index.elements.count, inputIndex + 32)
  guard inputIndex + 1 < end else { return nil }
  let candidates = ((inputIndex + 1)..<end).compactMap { candidateIndex -> ReasoningControl? in
    let element = index.elements[candidateIndex]
    guard index.roles[candidateIndex] == "AXPopUpButton",
          attributeBool(element, kAXEnabledAttribute as CFString) else { return nil }
    let label = reasoningControlLabel(element)
    let level = ReasoningLevel.parse(from: label)
    return ReasoningControl(
      element: element,
      label: label,
      modelLabel: level == nil ? "" : ReasoningLevel.modelLabel(from: label),
      level: level,
      ambiguous: level == .xhigh
    )
  }
  if let parsed = candidates.first(where: { $0.level != nil }) { return parsed }
  let hinted = candidates.filter { hasReasoningRoleHint($0.element) }
  if hinted.count == 1 { return hinted[0] }
  // The composer currently exposes one popup for model + reasoning. Its role
  // and position establish capability even while Electron republishes an
  // empty or localized title during a selector transition.
  return candidates.count == 1 ? candidates[0] : nil
}

private struct AgentTarget {
  let id: String
  let element: AXUIElement
  let label: String
  let state: String
  let focused: Bool
}

private let taskListLabels: Set<String> = ["任务", "tasks"]
private let taskRowActionLabels: Set<String> = [
  "置顶任务",
  "归档任务",
  "置顶任务 归档任务",
  "pin task",
  "archive task",
  "pin task archive task",
]

private func normalizedAttribute(_ element: AXUIElement, _ attribute: CFString) -> String {
  attributeString(element, attribute)
    .trimmingCharacters(in: .whitespacesAndNewlines)
    .lowercased()
}

private func taskLabel(in button: AXUIElement) -> String? {
  let labels = descendants(of: button, maxDepth: 7) {
    attributeString($0, kAXRoleAttribute as CFString) == "AXStaticText"
  }.compactMap { element -> String? in
    let value = attributeString(element, kAXValueAttribute as CFString)
      .trimmingCharacters(in: .whitespacesAndNewlines)
    let title = attributeString(element, kAXTitleAttribute as CFString)
      .trimmingCharacters(in: .whitespacesAndNewlines)
    let label = value.isEmpty ? title : value
    guard !label.isEmpty, label.count <= 200,
          label.rangeOfCharacter(from: .controlCharacters) == nil else { return nil }
    return label
  }
  return labels.first(where: isTaskTitleLabel)
}

private func isTaskTitleLabel(_ value: String) -> Bool {
  let normalized = value
    .trimmingCharacters(in: .whitespacesAndNewlines)
    .lowercased()
  return !normalized.isEmpty && !taskRowActionLabels.contains(normalized)
}

private struct TaskRow {
  let content: AXUIElement
  let target: AXUIElement
}

private func taskRows(in list: AXUIElement) -> [TaskRow] {
  var result = [TaskRow]()
  func visit(_ element: AXUIElement, depth: Int, clickableAncestor: AXUIElement?) {
    guard depth <= 10 else { return }
    let isButton = attributeString(element, kAXRoleAttribute as CFString) == "AXButton"
    let nextClickable = isButton && (try? elementFrame(element)) != nil
      ? element
      : clickableAncestor
    let classes = Set(attributeStrings(element, "AXDOMClassList" as CFString))
    if classes.contains("cursor-interaction"), taskLabel(in: element) != nil {
      let textTarget = descendants(of: element, maxDepth: 7) { candidate in
        attributeString(candidate, kAXRoleAttribute as CFString) == "AXStaticText"
          && (try? elementFrame(candidate)) != nil
      }.first
      if let target = nextClickable ?? textTarget,
         !result.contains(where: { CFEqual($0.content, element) }) {
        result.append(TaskRow(content: element, target: target))
      }
    }
    children(of: element).forEach {
      visit($0, depth: depth + 1, clickableAncestor: nextClickable)
    }
  }
  visit(list, depth: 0, clickableAncestor: nil)
  return result
}

private struct TaskListSnapshot {
  let rows: [TaskRow]
  let currentRow: TaskRow?
}

private let maxAgentTargets = 24

private func taskListSnapshot(in root: AXUIElement) -> TaskListSnapshot? {
  let lists = descendants(of: root, maxDepth: 22) {
    attributeString($0, kAXRoleAttribute as CFString) == "AXList"
  }
  let candidates = lists.compactMap { list -> (rows: [TaskRow], named: Bool)? in
    let rows = taskRows(in: list)
    guard !rows.isEmpty else { return nil }
    let named = taskListLabels.contains(normalizedAttribute(list, kAXDescriptionAttribute as CFString))
      || taskListLabels.contains(normalizedAttribute(list, kAXTitleAttribute as CFString))
    return (rows, named)
  }
  guard !candidates.isEmpty else { return nil }
  let orderedCandidates = candidates.sorted { left, right in
    if left.named != right.named { return left.named && !right.named }
    return left.rows.count > right.rows.count
  }
  var rows = [TaskRow]()
  for candidate in orderedCandidates {
    for row in candidate.rows where !rows.contains(where: { CFEqual($0.content, row.content) }) {
      rows.append(row)
    }
  }
  let currentRow = candidates.lazy.flatMap(\.rows).first { row in
    taskIsFocused(row.content) || taskIsFocused(row.target)
  }
  return TaskListSnapshot(rows: rows, currentRow: currentRow)
}

private func taskIsFocused(_ element: AXUIElement) -> Bool {
  let classes = Set(attributeStrings(element, "AXDOMClassList" as CFString))
  return classes.contains("bg-token-list-hover-background")
    || attributeBool(element, kAXSelectedAttribute as CFString)
    || attributeBool(element, kAXFocusedAttribute as CFString)
}

private func focusedTaskRow(in root: AXUIElement) -> TaskRow? {
  let focusedContent = descendant(of: root, maxDepth: 22) { element in
    let classes = Set(attributeStrings(element, "AXDOMClassList" as CFString))
    return classes.contains("cursor-interaction")
      && taskIsFocused(element)
      && taskLabel(in: element) != nil
  }
  if let focusedContent {
    return TaskRow(content: focusedContent, target: focusedContent)
  }
  return taskListSnapshot(in: root)?.currentRow
}

private func elementText(_ element: AXUIElement) -> String {
  let value = attributeString(element, kAXValueAttribute as CFString)
    .trimmingCharacters(in: .whitespacesAndNewlines)
  if !value.isEmpty { return value }
  let title = attributeString(element, kAXTitleAttribute as CFString)
    .trimmingCharacters(in: .whitespacesAndNewlines)
  if !title.isEmpty { return title }
  return attributeString(element, kAXDescriptionAttribute as CFString)
    .trimmingCharacters(in: .whitespacesAndNewlines)
}

private func taskLabelsMatch(_ visibleText: String, _ taskLabel: String) -> Bool {
  if visibleText == taskLabel { return true }
  let prefix = taskLabel
    .replacingOccurrences(of: "...", with: "")
    .trimmingCharacters(in: CharacterSet(charactersIn: "… "))
  return prefix.count >= 8 && visibleText.hasPrefix(prefix)
}

private func taskIndicatorState(in button: AXUIElement) -> String {
  let content = descendant(of: button, maxDepth: 4) { element in
    let classes = Set(attributeStrings(element, "AXDOMClassList" as CFString))
    return classes.contains("cursor-interaction")
  } ?? button
  let statusContainers = children(of: content).filter { child in
    guard attributeString(child, kAXRoleAttribute as CFString) == "AXGroup" else { return false }
    let hasImage = descendant(of: child, maxDepth: 4) {
      attributeString($0, kAXRoleAttribute as CFString) == "AXImage"
    } != nil
    let hasButton = descendant(of: child, maxDepth: 4) {
      attributeString($0, kAXRoleAttribute as CFString) == "AXButton"
    } != nil
    return hasImage && !hasButton
  }
  let stateLabels: [(Set<String>, String)] = [
    (["错误", "error", "failed"], "error"),
    (["需要用户批准", "需要输入", "等待中", "needs approval", "needs input", "waiting"], "waiting"),
    (["未读", "unread"], "unread"),
    (["已完成", "complete", "completed", "done"], "complete"),
    (["思考中", "thinking"], "thinking"),
    (["运行中", "running", "executing"], "executing"),
  ]
  for container in statusContainers {
    let elements = descendants(of: container, maxDepth: 5) { _ in true }
    let text = elements.flatMap { element in
      [
        attributeString(element, kAXTitleAttribute as CFString),
        attributeString(element, kAXDescriptionAttribute as CFString),
        attributeString(element, kAXHelpAttribute as CFString),
      ]
    }.joined(separator: " ").lowercased()
    if let match = stateLabels.first(where: { labels, _ in labels.contains(where: text.contains) }) {
      return match.1
    }
  }
  return statusContainers.isEmpty ? "idle" : "executing"
}

private func focusedTaskTitle(in root: AXUIElement) -> String? {
  focusedTaskRow(in: root).flatMap { taskLabel(in: $0.content) }
}

private func focusedAgentTarget(in root: AXUIElement, currentTaskState: String) -> AgentTarget? {
  guard let row = focusedTaskRow(in: root),
        let label = taskLabel(in: row.content) else { return nil }
  return AgentTarget(
    id: stableAgentID(for: row.content, fallbackPath: "focused-sidebar-task\u{0}\(label)"),
    element: row.target,
    label: label,
    state: currentTaskState,
    focused: true
  )
}

private func agentTargets(
  in root: AXUIElement,
  currentTaskState: String,
  focusedTaskTitle: String? = nil
) -> [AgentTarget] {
  guard let snapshot = taskListSnapshot(in: root) else { return [] }
  let visibleTaskTitle = focusedTaskTitle ?? snapshot.currentRow.flatMap { taskLabel(in: $0.content) }
  let currentRow = visibleTaskTitle.flatMap { title in
    snapshot.rows.first { row in
      guard let label = taskLabel(in: row.content) else { return false }
      return taskLabelsMatch(title, label) || taskLabelsMatch(label, title)
    }
  } ?? snapshot.currentRow
  var orderedRows = [TaskRow]()
  if let currentRow { orderedRows.append(currentRow) }
  for row in snapshot.rows {
    guard let label = taskLabel(in: row.content),
          !orderedRows.contains(where: { existing in
            taskLabel(in: existing.content).map { taskLabelsMatch($0, label) } == true
          }) else { continue }
    orderedRows.append(row)
  }
  var seen = Set<String>()
  var candidates = [(id: String, element: AXUIElement, stateElement: AXUIElement, label: String)]()
  for (taskIndex, row) in orderedRows.enumerated() {
    guard let label = taskLabel(in: row.content) else { continue }
    let id = stableAgentID(for: row.content, fallbackPath: "sidebar-task-\(taskIndex)\u{0}\(label)")
    guard seen.insert(id).inserted else { continue }
    candidates.append((id: id, element: row.target, stateElement: row.content, label: label))
  }
  let targets = candidates.map { candidate in
    let focused = visibleTaskTitle.map { taskLabelsMatch($0, candidate.label) } ?? false
    return AgentTarget(
      id: candidate.id,
      element: candidate.element,
      label: candidate.label,
      state: focused ? currentTaskState : taskIndicatorState(in: candidate.stateElement),
      focused: focused
    )
  }
  return targets.enumerated().sorted { left, right in
    let leftPriority = agentStatePriority(left.element.state)
    let rightPriority = agentStatePriority(right.element.state)
    if leftPriority != rightPriority { return leftPriority < rightPriority }
    if left.element.focused != right.element.focused { return left.element.focused }
    return left.offset < right.offset
  }.prefix(maxAgentTargets).map(\.element)
}

private func agentStatePriority(_ state: String) -> Int {
  switch state {
  case "waiting": return 0
  case "error": return 1
  case "executing": return 2
  case "thinking": return 3
  case "unread": return 4
  case "complete": return 6
  default: return 7
  }
}

private func stableAgentID(for element: AXUIElement, fallbackPath: String) -> String {
  let identifier = attributeString(element, kAXIdentifierAttribute as CFString)
    .trimmingCharacters(in: .whitespacesAndNewlines)
  let domIdentifier = scalarAttributeString(element, "AXDOMIdentifier" as CFString)
    .trimmingCharacters(in: .whitespacesAndNewlines)
  let chromeNodeID = scalarAttributeString(element, "ChromeAXNodeId" as CFString)
    .trimmingCharacters(in: .whitespacesAndNewlines)
  let source: String
  if !identifier.isEmpty {
    source = "identifier\u{0}\(identifier)"
  } else if !domIdentifier.isEmpty {
    source = "dom\u{0}\(domIdentifier)"
  } else if !chromeNodeID.isEmpty {
    source = "chrome\u{0}\(chromeNodeID)"
  } else {
    source = "path\u{0}\(fallbackPath)"
  }
  let digest = SHA256.hash(data: Data(source.utf8))
  let hex = digest.prefix(12).map { String(format: "%02x", $0) }.joined()
  return "agent-\(hex)"
}

private struct MutationDesktopSnapshot {
  let application: NSRunningApplication
  let window: AXUIElement
  let area: AXUIElement
  let token: String
}

private struct FocusedDesktopTarget {
  let application: NSRunningApplication
  let window: AXUIElement
  let area: AXUIElement
  let mutationToken: String?
}

private func mutationToken(
  application: NSRunningApplication,
  window: AXUIElement,
  agent: AgentTarget
) -> String {
  let title = attributeString(window, kAXTitleAttribute as CFString)
    .trimmingCharacters(in: .whitespacesAndNewlines)
  let frame = (try? elementFrame(window)).map {
    "\(Int($0.origin.x)),\(Int($0.origin.y)),\(Int($0.width)),\(Int($0.height))"
  } ?? ""
  let windowID = stableAgentID(for: window, fallbackPath: "window\u{0}\(title)\u{0}\(frame)")
  let source = "\(application.processIdentifier)\u{0}\(windowID)\u{0}\(agent.id)\u{0}\(agent.label)"
  let digest = SHA256.hash(data: Data(source.utf8))
  let hex = digest.prefix(16).map { String(format: "%02x", $0) }.joined()
  return "desktop-\(hex)"
}

private func focusedDesktopTarget(
  for application: NSRunningApplication,
  requireForeground: Bool = false
) throws -> FocusedDesktopTarget {
  try verifyDesktopSessionUnlocked()
  if requireForeground { try verifyForeground(application) }
  let window = try focusedWindow(for: application)
  let area = try codexArea(in: window)
  let currentState = taskState(in: AreaIndex(area))
  let token = focusedAgentTarget(in: window, currentTaskState: currentState).map {
    mutationToken(application: application, window: window, agent: $0)
  }
  guard CFEqual(try focusedWindow(for: application), window) else {
    throw HelperFailure.message("ChatGPT changed its focused window while observing the Codex task.")
  }
  return FocusedDesktopTarget(
    application: application,
    window: window,
    area: area,
    mutationToken: token
  )
}

@discardableResult
private func revalidateFocusedDesktopTarget(
  _ expected: FocusedDesktopTarget,
  requireForeground: Bool = false
) throws -> FocusedDesktopTarget {
  let current = try focusedDesktopTarget(
    for: expected.application,
    requireForeground: requireForeground
  )
  guard CFEqual(current.window, expected.window) else {
    throw HelperFailure.message("ChatGPT changed its focused window before desktop control.")
  }
  if let expectedToken = expected.mutationToken, current.mutationToken != expectedToken {
    throw HelperFailure.message("The focused Codex task changed before desktop control.")
  }
  return current
}

private func mutationDesktopSnapshot(
  for application: NSRunningApplication,
  requireForeground: Bool = true
) throws -> MutationDesktopSnapshot {
  let target = try focusedDesktopTarget(
    for: application,
    requireForeground: requireForeground
  )
  guard let token = target.mutationToken else {
    throw HelperFailure.message("ChatGPT did not expose one focused Codex task for this settings mutation.")
  }
  return MutationDesktopSnapshot(
    application: application,
    window: target.window,
    area: target.area,
    token: token
  )
}

private func expectedMutationToken(_ arguments: [String], at index: Int) throws -> String {
  guard arguments.indices.contains(index) else {
    throw HelperFailure.message("An observed Codex desktop identity is required.")
  }
  let token = arguments[index]
  let hex = token.dropFirst("desktop-".count)
  guard token.hasPrefix("desktop-"), (24...64).contains(hex.count),
        hex.allSatisfy({ $0.isHexDigit && !$0.isUppercase }) else {
    throw HelperFailure.message("A valid observed Codex desktop identity is required.")
  }
  return token
}

private func boundMutationDesktop(expectedToken: String) throws -> MutationDesktopSnapshot {
  try verifyDesktopSessionUnlocked()
  let snapshot = try mutationDesktopSnapshot(for: findChatGPT(), requireForeground: false)
  guard snapshot.token == expectedToken else {
    throw HelperFailure.message("The focused Codex task or window changed before the settings mutation.")
  }
  return snapshot
}

private func withInteractiveMutationDesktop<Value>(
  expectedToken: String,
  perform operation: (MutationDesktopSnapshot) throws -> Value
) throws -> Value {
  let initial = try boundMutationDesktop(expectedToken: expectedToken)
  let previousApplication = NSWorkspace.shared.frontmostApplication
  let wasMinimized = attributeBool(initial.window, kAXMinimizedAttribute as CFString)
  if wasMinimized {
    let result = AXUIElementSetAttributeValue(
      initial.window,
      kAXMinimizedAttribute as CFString,
      kCFBooleanFalse
    )
    guard result == .success else {
      throw HelperFailure.message("ChatGPT did not make its Codex control surface available in the background.")
    }
    usleep(180_000)
  }
  let ready = try boundMutationDesktop(expectedToken: expectedToken)
  defer {
    if wasMinimized {
      _ = AXUIElementSetAttributeValue(
        initial.window,
        kAXMinimizedAttribute as CFString,
        kCFBooleanTrue
      )
    }
    if let previousApplication,
       previousApplication.processIdentifier != initial.application.processIdentifier,
       !previousApplication.isTerminated {
      _ = requestForeground(previousApplication)
    }
  }
  if previousApplication?.processIdentifier != initial.application.processIdentifier {
    try activate(initial.application, window: ready.window)
  }
  return try operation(ready)
}

private let activeVoiceLabels: Set<String> = [
  "停止听写", "结束听写", "停止语音输入", "结束语音输入", "停止录音", "结束录音",
  "stop dictation", "stop voice input", "stop recording",
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

private func controlAvailability(in index: AreaIndex, hasAgents: Bool) -> [String: Bool] {
  let direct = Dictionary(uniqueKeysWithValues: DesktopControl.allCases.map { control in
    (control.rawValue, controlButton(control, in: index) != nil)
  })
  let hasMessageDraft = hasDraft(in: index)
  let hasMessageInput = prompt(in: index) != nil
  let isExecuting = controlButton(.stop, in: index) != nil
  return direct.merging([
    "approve": direct["approve"] == true,
    // Escape is not a reliable Codex rejection primitive. Only enable Reject
    // when ChatGPT exposes an explicit semantic rejection button.
    "reject": direct["reject"] == true,
    "clear-input": hasMessageDraft,
    "focus-agent": hasAgents,
    "mode-cycle": !isExecuting,
    "model-picker": reasoningControl(in: index) != nil && !isExecuting,
    "access-cycle": accessModeButton(in: index) != nil && !isExecuting,
    "navigate": true,
    "reasoning": reasoningControl(in: index) != nil && !isExecuting,
    "workflow": direct[DesktopControl.newTask.rawValue] == true && hasMessageInput,
  ]) { _, new in new }
}

private func accessModeLabel(in area: AXUIElement) -> String? {
  accessModeLabel(in: AreaIndex(area))
}

private func accessModeLabel(in index: AreaIndex) -> String? {
  guard let button = accessModeButton(in: index) else { return nil }
  return accessModeLabel(of: button)
}

private let planModeLabels: Set<String> = ["计划", "plan"]

private func planModeIsActive(in index: AreaIndex) -> Bool {
  guard let inputIndex = index.roles.lastIndex(of: "AXTextArea") else { return false }
  let start = max(0, inputIndex - 32)
  let end = min(index.elements.count, inputIndex + 33)
  return (start..<end).contains { elementIndex in
    let role = index.roles[elementIndex]
    guard role == "AXButton" || role == "AXStaticText" else { return false }
    let element = index.elements[elementIndex]
    let labels = [
      attributeString(element, kAXTitleAttribute as CFString),
      attributeString(element, kAXDescriptionAttribute as CFString),
      attributeString(element, kAXValueAttribute as CFString),
    ].map { $0.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }
    return labels.contains(where: planModeLabels.contains)
  }
}

private func taskState(in index: AreaIndex) -> String {
  if controlButton(.approve, in: index) != nil || controlButton(.reject, in: index) != nil { return "waiting" }
  if controlButton(.stop, in: index) != nil { return "executing" }
  return "idle"
}

private func statusReply(application: NSRunningApplication, area: AXUIElement) throws -> [String: Any] {
  let index = AreaIndex(area)
  let currentState = taskState(in: index)
  let scope = try focusedWindow(for: application)
  let focusedAgent = focusedAgentTarget(in: scope, currentTaskState: currentState)
  let agents = focusedAgent.map { [$0] } ?? []
  let identity: [String: String]
  if let focusedAgent, let window = try? focusedWindow(for: application), CFEqual(window, scope) {
    let token = mutationToken(application: application, window: window, agent: focusedAgent)
    let confirmed = try mutationDesktopSnapshot(for: application, requireForeground: false)
    guard confirmed.token == token else {
      throw HelperFailure.message("The focused Codex task changed while reading its status.")
    }
    identity = ["mutationToken": token]
  } else {
    identity = [:]
  }
  let reasoning = reasoningControl(in: index)
  let controls = controlAvailability(in: index, hasAgents: !agents.isEmpty)
  let reasoningAvailable = controls["reasoning"] == true
  return [
    "ok": true,
    "available": true,
    "foreground": NSWorkspace.shared.frontmostApplication?.processIdentifier == application.processIdentifier,
    "message": "Ready to control the visible ChatGPT Codex task.",
    "identity": identity,
    "taskState": currentState,
    "controls": controls,
    "voice": ["available": controls["voice"] == true, "active": voiceIsActive(in: index)],
    "mode": [
      "available": controls["mode-cycle"] == true,
      "label": planModeIsActive(in: index) ? "Plan" : "Default",
    ],
    "access": [
      "available": controls["access-cycle"] == true,
      "label": accessModeLabel(in: index) ?? "",
    ],
    "reasoning": [
      "available": reasoningAvailable,
      "label": reasoning?.label ?? "",
      "modelLabel": reasoning?.modelLabel ?? "",
      "level": reasoning?.level?.rawValue ?? "",
      "ambiguous": reasoning?.ambiguous ?? false,
      "canIncrease": reasoningAvailable,
      "canDecrease": reasoningAvailable,
    ],
    "agents": agents.enumerated().map { index, agent in
      ["id": agent.id, "label": agent.label, "state": agent.state, "focused": agent.focused]
    },
  ]
}

private func press(
  _ control: DesktopControl,
  in target: FocusedDesktopTarget,
  requireForeground: Bool = false
) throws {
  guard let button = controlButton(control, in: target.area) else {
    throw HelperFailure.message(control.unavailableMessage)
  }
  try revalidateFocusedDesktopTarget(target, requireForeground: requireForeground)
  try performPress(button)
}

private func postKey(_ code: CGKeyCode, to processIdentifier: pid_t) throws {
  guard let source = CGEventSource(stateID: .privateState),
        let down = CGEvent(keyboardEventSource: source, virtualKey: code, keyDown: true),
        let up = CGEvent(keyboardEventSource: source, virtualKey: code, keyDown: false) else {
    throw HelperFailure.message("macOS could not create the requested keyboard event.")
  }
  down.flags = []
  up.flags = []
  down.postToPid(processIdentifier)
  up.postToPid(processIdentifier)
}

private func postChord(
  _ code: CGKeyCode,
  flags: CGEventFlags,
  to processIdentifier: pid_t
) throws {
  guard let source = CGEventSource(stateID: .privateState),
        let down = CGEvent(keyboardEventSource: source, virtualKey: code, keyDown: true),
        let up = CGEvent(keyboardEventSource: source, virtualKey: code, keyDown: false) else {
    throw HelperFailure.message("macOS could not create the requested keyboard shortcut.")
  }
  down.flags = flags
  up.flags = flags
  down.postToPid(processIdentifier)
  usleep(60_000)
  up.postToPid(processIdentifier)
}

private func postHardwareKey(_ code: CGKeyCode) throws {
  guard let source = CGEventSource(stateID: .privateState),
        let down = CGEvent(keyboardEventSource: source, virtualKey: code, keyDown: true),
        let up = CGEvent(keyboardEventSource: source, virtualKey: code, keyDown: false) else {
    throw HelperFailure.message("macOS could not create the requested hardware keyboard event.")
  }
  down.flags = []
  up.flags = []
  down.post(tap: .cghidEventTap)
  up.post(tap: .cghidEventTap)
}

private func postHardwareChord(_ code: CGKeyCode, flags: CGEventFlags) throws {
  guard let source = CGEventSource(stateID: .privateState),
        let down = CGEvent(keyboardEventSource: source, virtualKey: code, keyDown: true),
        let up = CGEvent(keyboardEventSource: source, virtualKey: code, keyDown: false) else {
    throw HelperFailure.message("macOS could not create the requested hardware keyboard shortcut.")
  }
  down.flags = flags
  up.flags = flags
  down.post(tap: .cghidEventTap)
  usleep(60_000)
  up.post(tap: .cghidEventTap)
}

private let selectableRoles: Set<String> = [
  "AXButton", "AXLink", "AXMenuItem", "AXPopUpButton", "AXRadioButton",
]

private func exactControl(
  in root: AXUIElement,
  labels: Set<String>,
  maxDepth: Int = 28
) -> AXUIElement? {
  let normalizedLabels = Set(labels.map { $0.lowercased() })
  return descendants(of: root, maxDepth: maxDepth) { element in
    guard selectableRoles.contains(attributeString(element, kAXRoleAttribute as CFString)),
          attributeBool(element, kAXEnabledAttribute as CFString) else { return false }
    let ownLabels = [
      attributeString(element, kAXTitleAttribute as CFString),
      attributeString(element, kAXValueAttribute as CFString),
      attributeString(element, kAXDescriptionAttribute as CFString),
    ]
    let childLabels = descendants(of: element, maxDepth: 3) { child in
      attributeString(child, kAXRoleAttribute as CFString) == "AXStaticText"
    }.flatMap { child in
      [
        attributeString(child, kAXTitleAttribute as CFString),
        attributeString(child, kAXValueAttribute as CFString),
      ]
    }
    return (ownLabels + childLabels).contains { label in
      let normalized = label.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
      return normalizedLabels.contains { expected in normalized.contains(expected) }
    }
  }.first
}

private struct LocatedControl {
  let window: AXUIElement
  let element: AXUIElement
}

private func locatedControl(
  for application: NSRunningApplication,
  labels: Set<String>
) -> LocatedControl? {
  let root = AXUIElementCreateApplication(application.processIdentifier)
  for window in applicationWindows(root) {
    if let element = exactControl(in: window, labels: labels) {
      return LocatedControl(window: window, element: element)
    }
  }
  return nil
}

private func exactControl(
  for application: NSRunningApplication,
  labels: Set<String>
) -> AXUIElement? {
  locatedControl(for: application, labels: labels)?.element
}

private func microSettingDiagnostics(for application: NSRunningApplication) -> [String: Any] {
  let root = AXUIElementCreateApplication(application.processIdentifier)
  let keywords = ["codex micro", "knob", "reasoning only", "composer navigation", "旋钮", "仅推理", "编辑器导航"]
  var settingsAreas = 0
  var matches = [[String: Any]]()
  for window in applicationWindows(root) {
    let windowTitle = attributeString(window, kAXTitleAttribute as CFString).lowercased()
    let settingsWindow = windowTitle.contains("settings") || windowTitle.contains("设置")
    for area in codexWebAreas(of: window, using: children) {
      let url = attributeString(area, kAXURLAttribute as CFString).lowercased()
      if url.contains("/settings") { settingsAreas += 1 }
      for element in descendants(of: area, maxDepth: 28, where: { candidate in
        selectableRoles.contains(attributeString(candidate, kAXRoleAttribute as CFString))
      }) {
        let values = [
          attributeString(element, kAXTitleAttribute as CFString),
          attributeString(element, kAXValueAttribute as CFString),
          attributeString(element, kAXDescriptionAttribute as CFString),
          attributeString(element, kAXHelpAttribute as CFString),
        ]
        let normalized = values.joined(separator: " ").lowercased()
        guard keywords.contains(where: normalized.contains) else { continue }
        var actionNames: CFArray?
        AXUIElementCopyActionNames(element, &actionNames)
        let frame = try? elementFrame(element)
        matches.append([
          "role": attributeString(element, kAXRoleAttribute as CFString),
          "title": values[0],
          "value": values[1],
          "description": values[2],
          "help": values[3],
          "enabled": attributeBool(element, kAXEnabledAttribute as CFString),
          "actions": (actionNames as? [String]) ?? [],
          "settingsWindow": settingsWindow,
          "frameWidth": frame?.width ?? 0,
          "frameHeight": frame?.height ?? 0,
        ])
      }
    }
  }
  return ["ok": true, "settingsAreas": settingsAreas, "matches": matches]
}

private func requestMicroConnection(
  for application: NSRunningApplication,
  in window: AXUIElement
) throws {
  let connect = waitForValue(attempts: 10, intervalMicroseconds: 100_000) {
    exactControl(in: window, labels: ["Connect", "连接"])
  }
  guard let connect else { return }
  try activate(application, window: window)
  try performPress(connect)
}

private func settingsMenuItem(for application: NSRunningApplication) -> AXUIElement? {
  let root = AXUIElementCreateApplication(application.processIdentifier)
  let labels = Set(["settings", "settings...", "settings…", "preferences", "preferences...", "preferences…", "设置", "设置...", "设置…"])
  return descendants(of: root, maxDepth: 8) { element in
    guard attributeString(element, kAXRoleAttribute as CFString) == "AXMenuItem",
          attributeBool(element, kAXEnabledAttribute as CFString) else { return false }
    let values = [
      attributeString(element, kAXTitleAttribute as CFString),
      attributeString(element, kAXValueAttribute as CFString),
      attributeString(element, kAXDescriptionAttribute as CFString),
    ]
    return values.contains { value in
      labels.contains(value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased())
    }
  }.first
}

private func configureMicroReasoningKnob() throws {
  try verifyDesktopSessionUnlocked()
  let application = try findChatGPT()
  let previousApplication = NSWorkspace.shared.frontmostApplication
  defer {
    if let previousApplication,
       previousApplication.processIdentifier != application.processIdentifier {
      _ = previousApplication.activate(options: [])
    }
  }
  if let menuItem = settingsMenuItem(for: application) {
    try performPress(menuItem)
  } else {
    try postChord(43, flags: [.maskCommand], to: application.processIdentifier)
  }

  let settings = waitForValue(attempts: 20, intervalMicroseconds: 100_000) {
    locatedControl(for: application, labels: ["Codex Micro"])
  }
  guard let settings else {
    throw HelperFailure.message("ChatGPT did not expose the Codex Micro settings control.")
  }
  try activate(application, window: settings.window)
  try performPress(settings.element)

  let findCurrentMode = {
    exactControl(
      for: application,
      labels: ["Composer navigation", "Reasoning only", "编辑器导航", "仅推理"]
    )
  }
  var currentMode = waitForValue(attempts: 10, intervalMicroseconds: 100_000, find: findCurrentMode)
  if currentMode == nil {
    try focus(settings.element)
    try postKey(36, to: application.processIdentifier)
    currentMode = waitForValue(attempts: 20, intervalMicroseconds: 100_000, find: findCurrentMode)
  }
  guard let currentMode else {
    throw HelperFailure.message("ChatGPT did not expose the Codex Micro knob setting.")
  }
  try requestMicroConnection(for: application, in: settings.window)
  let currentLabel = [
    attributeString(currentMode, kAXTitleAttribute as CFString),
    attributeString(currentMode, kAXValueAttribute as CFString),
    attributeString(currentMode, kAXDescriptionAttribute as CFString),
  ].joined(separator: " ").lowercased()
  if currentLabel.contains("reasoning only") || currentLabel.contains("仅推理") { return }

  try activate(application, window: settings.window)
  let interaction = try MenuInteraction(application: application)
  defer { interaction.cleanup() }
  try interaction.openOptions(currentMode)
  let findReasoning = { window in
    exactControl(in: window, labels: ["Reasoning only", "仅推理"])
  }
  var reasoning = try interaction.waitForValue(
    attempts: 10,
    intervalMicroseconds: 100_000,
    find: findReasoning
  )
  if reasoning == nil {
    try interaction.openOptionsFromKeyboard(currentMode)
    reasoning = try interaction.waitForValue(
      attempts: 20,
      intervalMicroseconds: 100_000,
      find: findReasoning
    )
  }
  guard let reasoning else {
    throw HelperFailure.message("ChatGPT did not expose the reasoning-only knob option.")
  }
  try interaction.chooseMenuItem(reasoning)

  let observeReasoningMode: () -> AXUIElement? = {
    guard let control = findCurrentMode(),
          attributeString(control, kAXRoleAttribute as CFString) != "AXMenuItem" else { return nil }
    let label = [
      attributeString(control, kAXTitleAttribute as CFString),
      attributeString(control, kAXValueAttribute as CFString),
      attributeString(control, kAXDescriptionAttribute as CFString),
    ].joined(separator: " ").lowercased()
    return label.contains("reasoning only") || label.contains("仅推理") ? control : nil
  }
  var confirmed = waitForValue(attempts: 10, intervalMicroseconds: 100_000, find: observeReasoningMode)
  if confirmed == nil {
    try interaction.openOptionsFromKeyboard(currentMode)
    guard let keyboardReasoning = try interaction.waitForValue(
      attempts: 10,
      intervalMicroseconds: 100_000,
      find: findReasoning
    ) else {
      throw HelperFailure.message("ChatGPT did not reopen the reasoning-only knob option.")
    }
    try interaction.chooseMenuItemFromKeyboard(keyboardReasoning)
    confirmed = waitForValue(attempts: 20, intervalMicroseconds: 100_000, find: observeReasoningMode)
  }
  guard confirmed != nil else {
    throw HelperFailure.message("ChatGPT did not confirm the reasoning-only knob mode.")
  }
}

private func focus(_ element: AXUIElement) throws {
  let result = AXUIElementSetAttributeValue(element, kAXFocusedAttribute as CFString, kCFBooleanTrue)
  guard result == .success else {
    throw HelperFailure.message("ChatGPT did not focus the requested selector.")
  }
}

private final class MenuInteraction {
  private let application: NSRunningApplication
  private let window: AXUIElement
  private let expectedMutationToken: String?
  private let requireForeground: Bool
  private var opened = false

  init(
    application: NSRunningApplication,
    expectedMutationToken: String? = nil,
    requireForeground: Bool = true
  ) throws {
    if requireForeground { try verifyForeground(application) }
    self.application = application
    self.window = try focusedWindow(for: application)
    self.expectedMutationToken = expectedMutationToken
    self.requireForeground = requireForeground
    try revalidate()
  }

  func openComposerMenu(_ control: ReasoningControl) throws {
    try showMenu(control.element, fallbackKey: 49)
    opened = true
  }

  func openMenu(_ element: AXUIElement) throws {
    try perform(element, fallbackKey: 49)
    opened = true
  }

  func openOptions(_ element: AXUIElement) throws {
    try showMenu(element, fallbackKey: 49)
    opened = true
  }

  func openOptionsFromKeyboard(_ element: AXUIElement) throws {
    try revalidate()
    try focus(element)
    try revalidate()
    try postKey(49, to: application.processIdentifier)
    opened = true
  }

  func openComposerSubmenuFromKeyboard(row: Int) throws {
    guard row > 0 && row <= 3 else {
      throw HelperFailure.message("The requested Codex composer menu row is invalid.")
    }
    try revalidate()
    if requireForeground {
      try postHardwareChord(46, flags: [.maskControl, .maskShift])
    } else {
      try postChord(
        46,
        flags: [.maskControl, .maskShift],
        to: application.processIdentifier
      )
    }
    opened = true
    usleep(120_000)
    for _ in 0..<row {
      try revalidate()
      if requireForeground {
        try postHardwareKey(125)
      } else {
        try postKey(125, to: application.processIdentifier)
      }
      usleep(45_000)
    }
    try revalidate()
    if requireForeground {
      try postHardwareKey(36)
    } else {
      try postKey(36, to: application.processIdentifier)
    }
  }

  func openSubmenu(_ item: AXUIElement) throws {
    try revalidate()
    if AXUIElementPerformAction(item, kAXShowMenuAction as CFString) == .success { return }
    try revalidate()
    if AXUIElementPerformAction(item, kAXPressAction as CFString) == .success { return }
    try revalidate()
    try focus(item)
    try revalidate()
    try postKey(124, to: application.processIdentifier)
  }

  func chooseMenuItem(_ item: AXUIElement) throws {
    try revalidate()
    if AXUIElementPerformAction(item, kAXPressAction as CFString) == .success {
      opened = false
      return
    }
    try revalidate()
    try focus(item)
    try revalidate()
    try postKey(36, to: application.processIdentifier)
  }

  func chooseMenuItemFromKeyboard(_ item: AXUIElement) throws {
    try revalidate()
    try focus(item)
    try revalidate()
    try postKey(36, to: application.processIdentifier)
    opened = false
  }

  func waitForValue<Value>(
    attempts: Int,
    intervalMicroseconds: UInt32,
    find: (AXUIElement) -> Value?
  ) throws -> Value? {
    for attempt in 0..<attempts {
      try revalidate()
      if let value = find(window) { return value }
      if attempt + 1 < attempts { usleep(intervalMicroseconds) }
    }
    return nil
  }

  func leaveOpen() {
    opened = false
  }

  func cleanup() {
    guard opened, (try? revalidate()) != nil else { return }
    try? postKey(53, to: application.processIdentifier)
    opened = false
  }

  private func perform(_ element: AXUIElement, fallbackKey: CGKeyCode) throws {
    try revalidate()
    if AXUIElementPerformAction(element, kAXPressAction as CFString) == .success { return }
    try revalidate()
    try focus(element)
    try revalidate()
    try postKey(fallbackKey, to: application.processIdentifier)
  }

  private func showMenu(_ element: AXUIElement, fallbackKey: CGKeyCode) throws {
    try revalidate()
    if AXUIElementPerformAction(element, kAXShowMenuAction as CFString) == .success { return }
    try revalidate()
    if AXUIElementPerformAction(element, kAXPressAction as CFString) == .success { return }
    try revalidate()
    try focus(element)
    try revalidate()
    try postKey(fallbackKey, to: application.processIdentifier)
  }

  private func revalidate() throws {
    if requireForeground { try verifyForeground(application) }
    let currentWindow = try focusedWindow(for: application)
    guard CFEqual(currentWindow, window) else {
      throw HelperFailure.message("ChatGPT changed its focused window during menu interaction.")
    }
    if let expectedMutationToken {
      let current = try mutationDesktopSnapshot(
        for: application,
        requireForeground: requireForeground
      )
      guard current.token == expectedMutationToken else {
        throw HelperFailure.message("The focused Codex task changed during menu interaction.")
      }
    }
  }
}

private func elementFrame(_ element: AXUIElement) throws -> CGRect {
  var frameValue: CFTypeRef?
  if AXUIElementCopyAttributeValue(element, "AXFrame" as CFString, &frameValue) == .success,
     let frameValue, CFGetTypeID(frameValue) == AXValueGetTypeID() {
    var frame = CGRect.zero
    if AXValueGetValue(frameValue as! AXValue, .cgRect, &frame), frame.width > 0, frame.height > 0 {
      return frame
    }
  }
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

private func performPress(_ element: AXUIElement) throws {
  let result = AXUIElementPerformAction(element, kAXPressAction as CFString)
  guard result == .success else {
    throw HelperFailure.message("ChatGPT did not accept the requested semantic control action.")
  }
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

private func selectNextAccessMode(
  from button: AXUIElement,
  in application: NSRunningApplication,
  currentLabel: String,
  expectedMutationToken: String?
) throws {
  let buttonFrame = try elementFrame(button)
  let interaction = try MenuInteraction(
    application: application,
    expectedMutationToken: expectedMutationToken,
    requireForeground: true
  )
  defer { interaction.cleanup() }
  try interaction.openMenu(button)
  guard let menu = try interaction.waitForValue(attempts: 8, intervalMicroseconds: 50_000, find: { window in
    let menus = descendants(of: window) {
      attributeString($0, kAXRoleAttribute as CFString) == "AXMenu"
    }.compactMap { menu -> (AXUIElement, CGFloat)? in
      guard let frame = try? elementFrame(menu) else { return nil }
      return (menu, hypot(frame.midX - buttonFrame.midX, frame.midY - buttonFrame.midY))
    }
    return menus.min(by: { $0.1 < $1.1 })?.0
  }), let currentKey = accessModeKey(for: currentLabel) else {
    throw HelperFailure.message("ChatGPT did not expose its access mode menu.")
  }
  let options = children(of: menu).compactMap { item -> (AXUIElement, String)? in
    guard attributeString(item, kAXRoleAttribute as CFString) == "AXMenuItem",
          let label = accessMenuOptionLabel(in: item),
          let key = accessModeKey(for: label) else { return nil }
    return (item, key)
  }
  let target: AXUIElement?
  if let currentIndex = options.firstIndex(where: { $0.1 == currentKey }), !options.isEmpty {
    target = options[(currentIndex + 1) % options.count].0
  } else {
    target = options.first(where: { $0.1 != currentKey })?.0
  }
  guard let target else {
    throw HelperFailure.message("ChatGPT did not expose another access mode in its menu.")
  }
  try interaction.chooseMenuItem(target)
  usleep(180_000)
}

private func waitForValue<Value>(
  attempts: Int = 6,
  intervalMicroseconds: UInt32 = 80_000,
  find: () -> Value?
) -> Value? {
  for attempt in 0..<attempts {
    if let value = find() { return value }
    if attempt + 1 < attempts { usleep(intervalMicroseconds) }
  }
  return nil
}

private func modelKey(_ value: String) -> String {
  let compact = value.lowercased().filter { $0.isLetter || $0.isNumber }
  return compact.hasPrefix("gpt") ? String(compact.dropFirst(3)) : compact
}

private func modelMatches(requested: String, candidate: String) -> Bool {
  let target = modelKey(requested)
  return !target.isEmpty && modelKey(candidate) == target
}

private func modelLabel(of element: AXUIElement) -> String {
  let own = [
    attributeString(element, kAXTitleAttribute as CFString),
    attributeString(element, kAXValueAttribute as CFString),
    attributeString(element, kAXDescriptionAttribute as CFString),
  ]
  let nested = descendants(of: element, maxDepth: 4) {
    attributeString($0, kAXRoleAttribute as CFString) == "AXStaticText"
  }.flatMap { element in
    [
      attributeString(element, kAXTitleAttribute as CFString),
      attributeString(element, kAXValueAttribute as CFString),
    ]
  }
  return (own + nested)
    .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
    .filter { !$0.isEmpty }
    .joined(separator: " ")
}

private func modelOptions(in root: AXUIElement, matching requested: String) -> [AXUIElement] {
  let selectableRoles: Set<String> = ["AXButton", "AXMenuItem", "AXRadioButton"]
  return descendants(of: root, maxDepth: 28) { element in
    let role = attributeString(element, kAXRoleAttribute as CFString)
    guard selectableRoles.contains(role), attributeBool(element, kAXEnabledAttribute as CFString) else {
      return false
    }
    return modelMatches(requested: requested, candidate: reasoningControlLabel(element))
  }
}

private func modelOptionReports(in root: AXUIElement) -> [[String: Any]] {
  let selectableRoles: Set<String> = ["AXButton", "AXMenuItem", "AXRadioButton"]
  let menus = descendants(of: root, maxDepth: 28) {
    attributeString($0, kAXRoleAttribute as CFString) == "AXMenu"
  }
  let scoped = menus.flatMap { menu in
    descendants(of: menu, maxDepth: 12) { element in
      selectableRoles.contains(attributeString(element, kAXRoleAttribute as CFString))
    }
  }
  let candidates = scoped.isEmpty
    ? Array(descendants(of: root, maxDepth: 28) { element in
      selectableRoles.contains(attributeString(element, kAXRoleAttribute as CFString))
    }.suffix(80))
    : scoped
  return candidates.filter { element in
    selectableRoles.contains(attributeString(element, kAXRoleAttribute as CFString))
      && attributeBool(element, kAXEnabledAttribute as CFString)
  }.prefix(80).map { element in
    var actionNames: CFArray?
    AXUIElementCopyActionNames(element, &actionNames)
    return [
      "role": attributeString(element, kAXRoleAttribute as CFString),
      "title": attributeString(element, kAXTitleAttribute as CFString),
      "value": attributeString(element, kAXValueAttribute as CFString),
      "description": attributeString(element, kAXDescriptionAttribute as CFString),
      "label": modelLabel(of: element),
      "actions": (actionNames as? [String]) ?? [],
      "focused": attributeBool(element, kAXFocusedAttribute as CFString),
      "selected": attributeBool(element, kAXSelectedAttribute as CFString),
    ]
  }.filter { report in
    report.values.contains { value in
      guard let text = value as? String else { return false }
      return !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
  }
}

private func modelMenuItem(in root: AXUIElement) -> AXUIElement? {
  let selectableRoles: Set<String> = ["AXButton", "AXMenuItem"]
  return descendants(of: root, maxDepth: 28) { element in
    let role = attributeString(element, kAXRoleAttribute as CFString)
    guard selectableRoles.contains(role), attributeBool(element, kAXEnabledAttribute as CFString) else {
      return false
    }
    let label = modelLabel(of: element).lowercased()
    let words = label.split { !$0.isLetter && !$0.isNumber }
    return label.contains("模型") || words.contains(where: { $0 == "model" })
  }.first
}

private func openModelSubmenu(_ interaction: MenuInteraction) throws {
  try interaction.openComposerSubmenuFromKeyboard(row: 1)
}

private func reasoningMenuItem(in root: AXUIElement) -> AXUIElement? {
  descendants(of: root, maxDepth: 28) { element in
    guard attributeString(element, kAXRoleAttribute as CFString) == "AXMenuItem",
          attributeBool(element, kAXEnabledAttribute as CFString) else { return false }
    let label = reasoningControlLabel(element).lowercased()
    return label.contains("推理强度") || label.contains("reasoning")
  }.first
}

private func reasoningMenuLevel(_ element: AXUIElement) -> ReasoningLevel? {
  let label = reasoningControlLabel(element).trimmingCharacters(in: .whitespacesAndNewlines)
  let normalized = label.lowercased()
  if normalized.contains("更快消耗使用额度")
      || normalized.contains("automatic task delegation") {
    return .ultra
  }
  return ReasoningLevel.parse(from: label)
}

private func reasoningOption(in root: AXUIElement, matching level: ReasoningLevel) -> AXUIElement? {
  descendants(of: root, maxDepth: 28) { element in
    attributeString(element, kAXRoleAttribute as CFString) == "AXMenuItem"
      && attributeBool(element, kAXEnabledAttribute as CFString)
      && reasoningMenuLevel(element) == level
  }.first
}

private func selectModel(
  _ requested: String,
  from control: ReasoningControl,
  in application: NSRunningApplication,
  expectedMutationToken: String
) throws {
  let target = modelKey(requested)
  guard !target.isEmpty, requested.count <= 128 else {
    throw HelperFailure.message("A valid Codex model ID is required.")
  }
  if modelKey(control.modelLabel) == target { return }

  let interaction = try MenuInteraction(
    application: application,
    expectedMutationToken: expectedMutationToken,
    requireForeground: true
  )
  defer { interaction.cleanup() }
  try openModelSubmenu(interaction)
  guard let matchingElements = try interaction.waitForValue(
    attempts: 12,
    intervalMicroseconds: 80_000,
    find: { window in
      let matches = modelOptions(in: window, matching: requested)
      return matches.isEmpty ? nil : matches
    }
  ) else {
    throw HelperFailure.message("ChatGPT did not expose the requested model in its model menu.")
  }
  guard matchingElements.count == 1, let targetElement = matchingElements.first else {
    throw HelperFailure.message("The requested Codex model is ambiguous in the desktop model menu.")
  }
  try interaction.chooseMenuItem(targetElement)
  let confirmed = try interaction.waitForValue(attempts: 16, intervalMicroseconds: 100_000) { window -> Bool? in
    guard let area = try? codexArea(in: window),
          let observed = reasoningControl(in: area) else { return nil }
    return modelKey(observed.modelLabel) == target ? true : nil
  }
  guard confirmed != nil else {
    throw HelperFailure.message("ChatGPT did not confirm the selected Codex model.")
  }
}

private func selectReasoning(
  _ requested: ReasoningLevel,
  current: ReasoningLevel,
  options: [ReasoningLevel],
  in application: NSRunningApplication,
  expectedMutationToken: String
) throws -> Bool {
  guard options.contains(current),
        options.contains(requested),
        Set(options).count == options.count else {
    throw HelperFailure.message("The advertised Codex reasoning path is invalid or stale.")
  }
  if current == requested { return true }

  let interaction = try MenuInteraction(
    application: application,
    expectedMutationToken: expectedMutationToken,
    requireForeground: true
  )
  defer { interaction.cleanup() }
  guard let snapshot = try? boundMutationDesktop(expectedToken: expectedMutationToken),
        controlButton(.stop, in: snapshot.area) == nil,
        reasoningControl(in: snapshot.area) != nil else {
    throw HelperFailure.message("Reasoning cannot change while the visible Codex task is running.")
  }
  try interaction.openComposerSubmenuFromKeyboard(row: 2)
  guard let target = try interaction.waitForValue(attempts: 12, intervalMicroseconds: 80_000, find: { window in
    reasoningOption(in: window, matching: requested)
  }) else {
    throw HelperFailure.message("ChatGPT did not expose the requested reasoning level.")
  }
  try interaction.chooseMenuItem(target)
  let confirmed: Bool? = try interaction.waitForValue(attempts: 20, intervalMicroseconds: 80_000) { window in
    guard let area = try? codexArea(in: window),
          let observed = reasoningControl(in: area) else { return nil }
    return observed.level == requested ? true : nil
  }
  guard confirmed != nil else {
    throw HelperFailure.message("ChatGPT did not confirm the selected reasoning level.")
  }
  return true
}

private func focusPrompt(in area: AXUIElement) throws -> AXUIElement {
  guard let input = prompt(in: area) else {
    throw HelperFailure.message("The current Codex task has no accessible message input.")
  }
  let result = AXUIElementSetAttributeValue(input, kAXFocusedAttribute as CFString, kCFBooleanTrue)
  guard result == .success && attributeBool(input, kAXFocusedAttribute as CFString) else {
    throw HelperFailure.message("ChatGPT did not focus the current Codex input line semantically.")
  }
  return input
}

private func togglePlanMode(in application: NSRunningApplication) throws {
  try postChord(
    35,
    flags: [.maskControl, .maskAlternate, .maskShift],
    to: application.processIdentifier
  )
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
  let (application, _) = try desktop(activateDesktop: true)
  let initialTarget = try focusedDesktopTarget(for: application, requireForeground: true)
  try press(.newTask, in: initialTarget, requireForeground: true)
  usleep(500_000)
  let nextTarget = try focusedDesktopTarget(for: application, requireForeground: true)
  guard let input = prompt(in: nextTarget.area) else {
    throw HelperFailure.message("The new Codex task did not expose a message input.")
  }
  try revalidateFocusedDesktopTarget(nextTarget, requireForeground: true)
  guard AXUIElementSetAttributeValue(input, kAXFocusedAttribute as CFString, kCFBooleanTrue) == .success else {
    throw HelperFailure.message("ChatGPT did not focus the configured workflow input.")
  }
  try revalidateFocusedDesktopTarget(nextTarget, requireForeground: true)
  guard AXUIElementSetAttributeValue(input, kAXValueAttribute as CFString, text as CFTypeRef) == .success else {
    throw HelperFailure.message("ChatGPT did not accept the configured workflow.")
  }
  usleep(150_000)
  try revalidateFocusedDesktopTarget(nextTarget, requireForeground: true)
  try postKey(36, to: application.processIdentifier)
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

func runCodexControl(arguments: [String], input: String? = nil) throws -> [String: Any] {
  let action = arguments.first ?? "status"
  switch action {
  case "request-accessibility":
    let trusted = requestAccessibilityPermission()
    return [
      "ok": true,
      "available": trusted,
      "message": trusted
        ? "Vibe Pocket Accessibility permission is enabled."
        : "Approve Vibe Pocket in the macOS Accessibility prompt, then retry.",
    ]
  case "window-diagnostics":
    return windowDiagnostics(for: try findChatGPT())
  case "control-diagnostics":
    return controlDiagnostics(for: try findChatGPT())
  case "selector-diagnostics":
    return try selectorDiagnostics(for: findChatGPT())
  case "micro-settings-diagnostics":
    return microSettingDiagnostics(for: try findChatGPT())
  case "configure-micro-reasoning":
    try configureMicroReasoningKnob()
    return ["ok": true, "message": "Configured the Codex Micro knob for reasoning."]
  case "model-options":
    let (application, area) = try desktop(activateDesktop: false)
    guard reasoningControl(in: area) != nil else {
      throw HelperFailure.message("The ChatGPT Codex model is not currently adjustable.")
    }
    let interaction = try MenuInteraction(application: application, requireForeground: false)
    defer { interaction.cleanup() }
    try openModelSubmenu(interaction)
    guard let options = try interaction.waitForValue(
      attempts: 10,
      intervalMicroseconds: 80_000,
      find: { window in
        let reports = modelOptionReports(in: window)
        return reports.isEmpty ? nil : reports
      }
    ) else {
      throw HelperFailure.message("ChatGPT did not expose model option semantics.")
    }
    return ["ok": true, "options": options]
  case "status":
    let (application, area) = try desktop(activateDesktop: false)
    return try statusReply(application: application, area: area)
  case "attach":
    let (application, _) = try desktop(activateDesktop: true)
    let target = try focusedDesktopTarget(for: application, requireForeground: true)
    try revalidateFocusedDesktopTarget(target, requireForeground: true)
    _ = try focusPrompt(in: target.area)
    return ["ok": true, "message": "Focused the visible ChatGPT Codex input line."]
  case "control":
    guard let rawControl = arguments.dropFirst().first,
          let control = DesktopControl(rawValue: rawControl) else {
      throw HelperFailure.message("Unsupported Vibe Pocket desktop control.")
    }
    let (application, _) = try desktop(activateDesktop: false)
    let target = try focusedDesktopTarget(for: application)
    try press(control, in: target)
    usleep(120_000)
    return ["ok": true, "message": "Pressed the ChatGPT Codex \(control.rawValue) control."]
  case "voice-start", "voice-stop":
    let desiredActive = action == "voice-start"
    let (application, _) = try desktop(activateDesktop: false)
    let target = try focusedDesktopTarget(for: application)
    guard controlButton(.voice, in: target.area) != nil else {
      throw HelperFailure.message(DesktopControl.voice.unavailableMessage)
    }
    if desiredActive {
      if !voiceIsActive(in: target.area) { try press(.voice, in: target) }
    } else {
      // A short press can release before ChatGPT has published the microphone
      // state. Re-read once so the release still closes the just-started PTT.
      let currentTarget: FocusedDesktopTarget
      if voiceIsActive(in: target.area) {
        currentTarget = target
      } else {
        usleep(100_000)
        currentTarget = try focusedDesktopTarget(for: application)
      }
      if voiceIsActive(in: currentTarget.area) { try press(.voice, in: currentTarget) }
    }
    return [
      "ok": true,
      "message": desiredActive ? "Started Codex dictation." : "Stopped Codex dictation.",
    ]
  case "navigate":
    guard let direction = arguments.dropFirst().first else {
      throw HelperFailure.message("A navigation direction is required.")
    }
    let (application, _) = try desktop(activateDesktop: true)
    let target = try focusedDesktopTarget(for: application, requireForeground: true)
    try revalidateFocusedDesktopTarget(target, requireForeground: true)
    try postKey(try keyCode(for: direction), to: application.processIdentifier)
    return ["ok": true, "message": "Navigated \(direction) in ChatGPT Codex."]
  case "access-cycle":
    let (application, _) = try desktop(activateDesktop: false)
    let target = try focusedDesktopTarget(for: application, requireForeground: true)
    guard controlButton(.stop, in: target.area) == nil,
          let button = accessModeButton(in: target.area) else {
      throw HelperFailure.message("The ChatGPT Codex access mode is not currently adjustable.")
    }
    let before = accessModeLabel(in: target.area) ?? ""
    try selectNextAccessMode(
      from: button,
      in: application,
      currentLabel: before,
      expectedMutationToken: target.mutationToken
    )
    return ["ok": true, "message": "Requested the next ChatGPT Codex access mode."]
  case "plan-mode":
    let expectedToken = try expectedMutationToken(arguments, at: 1)
    return try withInteractiveMutationDesktop(expectedToken: expectedToken) { snapshot in
      guard controlButton(.stop, in: snapshot.area) == nil else {
        throw HelperFailure.message("Plan mode cannot be changed while the visible Codex task is running.")
      }
      let wasActive = planModeIsActive(in: AreaIndex(snapshot.area))
      try togglePlanMode(in: snapshot.application)
      let confirmed: String? = waitForValue(attempts: 10, intervalMicroseconds: 100_000) {
        guard let current = try? boundMutationDesktop(expectedToken: expectedToken) else { return nil }
        let isActive = planModeIsActive(in: AreaIndex(current.area))
        return isActive != wasActive ? (isActive ? "Plan" : "Default") : nil
      }
      guard let confirmed else {
        throw HelperFailure.message("ChatGPT did not confirm the requested Codex collaboration mode.")
      }
      return [
        "ok": true,
        "message": "Selected the next Codex collaboration mode.",
        "settings": ["mode": ["available": true, "label": confirmed]],
      ]
    }
  case "model-picker":
    let (application, _) = try desktop(activateDesktop: false)
    let target = try focusedDesktopTarget(for: application, requireForeground: true)
    guard controlButton(.stop, in: target.area) == nil,
          reasoningControl(in: target.area) != nil else {
      throw HelperFailure.message("The ChatGPT Codex model is not currently adjustable.")
    }
    let interaction = try MenuInteraction(
      application: application,
      expectedMutationToken: target.mutationToken
    )
    try openModelSubmenu(interaction)
    interaction.leaveOpen()
    return ["ok": true, "message": "Opened the ChatGPT Codex model picker."]
  case "delete-backward":
    let (application, _) = try desktop(activateDesktop: false)
    let target = try focusedDesktopTarget(for: application, requireForeground: true)
    try revalidateFocusedDesktopTarget(target, requireForeground: true)
    _ = try focusPrompt(in: target.area)
    try revalidateFocusedDesktopTarget(target, requireForeground: true)
    try postKey(51, to: application.processIdentifier)
    return ["ok": true, "message": "Deleted one character from the ChatGPT Codex input line."]
  case "clear-input":
    let (application, _) = try desktop(activateDesktop: false)
    let target = try focusedDesktopTarget(for: application)
    try revalidateFocusedDesktopTarget(target)
    try clearInput(in: target.area)
    return ["ok": true, "message": "Cleared the ChatGPT Codex input line."]
  case "focus-agent":
    guard let agentID = arguments.dropFirst().first,
          agentID.hasPrefix("agent-"), agentID.count <= 80 else {
      throw HelperFailure.message("A valid Codex agent ID is required.")
    }
    let (application, _) = try desktop(activateDesktop: false)
    let target = try focusedDesktopTarget(for: application)
    let selectedTaskTitle = focusedTaskTitle(in: target.window)
    let agents = agentTargets(
      in: target.window,
      currentTaskState: taskState(in: AreaIndex(target.area)),
      focusedTaskTitle: selectedTaskTitle
    )
    guard let agent = agents.first(where: { $0.id == agentID }) else {
      throw HelperFailure.message("That Codex agent is no longer visible.")
    }
    if !agent.focused {
      func taskBecameFocused(attempts: Int) throws -> Bool {
        for _ in 0..<attempts {
          usleep(150_000)
          let updatedArea = try codexArea(for: application)
          let updatedScope = try focusedWindow(for: application)
          let updatedSelectedTaskTitle = focusedTaskTitle(in: updatedScope)
          let updatedAgents = agentTargets(
            in: updatedScope,
            currentTaskState: taskState(in: AreaIndex(updatedArea)),
            focusedTaskTitle: updatedSelectedTaskTitle
          )
          if updatedAgents.contains(where: { $0.focused && $0.label == agent.label }) {
            return true
          }
        }
        return false
      }

      try revalidateFocusedDesktopTarget(target)
      let pressResult = AXUIElementPerformAction(agent.element, kAXPressAction as CFString)
      let semanticPressWorked: Bool
      if pressResult == .success {
        semanticPressWorked = try taskBecameFocused(attempts: 4)
      } else {
        semanticPressWorked = false
      }
      guard semanticPressWorked else {
        throw HelperFailure.message("ChatGPT did not accept the selected Codex task through Accessibility.")
      }
    }
    return ["ok": true, "message": "Focused Codex task \(agent.label)."]
  case "workflow":
    try launchWorkflow(input ?? readStandardInput())
    return ["ok": true, "message": "Started the configured workflow in a new Codex task."]
  default:
    throw HelperFailure.message("Unsupported Vibe Pocket desktop action.")
  }
}

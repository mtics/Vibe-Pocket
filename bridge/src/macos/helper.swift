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
  return try codexArea(in: codexScope(for: application))
}

private func codexScope(for application: NSRunningApplication) -> AXUIElement {
  let root = AXUIElementCreateApplication(application.processIdentifier)
  var value: CFTypeRef?
  if AXUIElementCopyAttributeValue(root, kAXFocusedWindowAttribute as CFString, &value) == .success,
     let focusedWindow = value as! AXUIElement? {
    return focusedWindow
  }
  return root
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

private func activate(_ application: NSRunningApplication) throws {
  if NSWorkspace.shared.frontmostApplication?.processIdentifier == application.processIdentifier {
    return
  }
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
  let buttonPaths: [String]
  let textAreas: [AXUIElement]

  init(_ area: AXUIElement) {
    let leafRoles: Set<String> = [
      "AXButton", "AXCheckBox", "AXHeading", "AXImage", "AXLink", "AXMenuItem",
      "AXPopUpButton", "AXRadioButton", "AXStaticText", "AXTextArea",
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
      for (childIndex, child) in visibleChildren(of: candidate).enumerated() {
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

  private static let aliases: [(ReasoningLevel, [String])] = [
    (.xhigh, ["extra high", "xhigh", "max", "极高", "最高"]),
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

  func shifted(by delta: Int) -> ReasoningLevel? {
    guard delta == -1 || delta == 1,
          let currentIndex = Self.allCases.firstIndex(of: self) else { return nil }
    let targetIndex = currentIndex + delta
    return Self.allCases.indices.contains(targetIndex) ? Self.allCases[targetIndex] : nil
  }

  var canIncrease: Bool { self != .xhigh }
  var canDecrease: Bool { self != .minimal }
}

private struct ReasoningControl {
  let element: AXUIElement
  let label: String
  let modelLabel: String
  let level: ReasoningLevel?
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
      level: level
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
  return labels.first
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
    "approve": direct["approve"] == true || hasMessageDraft,
    // Escape is not a reliable Codex rejection primitive. Only enable Reject
    // when ChatGPT exposes an explicit semantic rejection button.
    "reject": direct["reject"] == true,
    "clear-input": hasMessageDraft,
    "focus-agent": hasAgents,
    "mode-cycle": hasMessageInput && !isExecuting,
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

private func statusReply(application: NSRunningApplication, area: AXUIElement) -> [String: Any] {
  let index = AreaIndex(area)
  let currentState = taskState(in: index)
  let scope = codexScope(for: application)
  let agents = focusedAgentTarget(in: scope, currentTaskState: currentState).map { [$0] } ?? []
  let reasoning = reasoningControl(in: index)
  let controls = controlAvailability(in: index, hasAgents: !agents.isEmpty)
  let reasoningAvailable = controls["reasoning"] == true
  return [
    "ok": true,
    "available": true,
    "foreground": NSWorkspace.shared.frontmostApplication?.processIdentifier == application.processIdentifier,
    "message": "Ready to control the visible ChatGPT Codex task.",
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
      "canIncrease": reasoningAvailable && (reasoning?.level?.canIncrease ?? true),
      "canDecrease": reasoningAvailable && (reasoning?.level?.canDecrease ?? true),
    ],
    "agents": agents.enumerated().map { index, agent in
      ["id": agent.id, "label": agent.label, "state": agent.state, "focused": agent.focused]
    },
  ]
}

private func press(_ control: DesktopControl, in area: AXUIElement) throws {
  if let button = controlButton(control, in: area) {
    try performPress(button)
    return
  }
  switch control {
  case .approve:
    guard hasDraft(in: AreaIndex(area)) else {
      throw HelperFailure.message("There is no visible Codex approval control or message draft to submit.")
    }
    _ = try focusPrompt(in: area)
    try postKey(36)
  case .reject:
    throw HelperFailure.message(DesktopControl.reject.unavailableMessage)
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

private func postChord(_ code: CGKeyCode, flags: CGEventFlags) throws {
  guard let source = CGEventSource(stateID: .hidSystemState),
        let down = CGEvent(keyboardEventSource: source, virtualKey: code, keyDown: true),
        let up = CGEvent(keyboardEventSource: source, virtualKey: code, keyDown: false) else {
    throw HelperFailure.message("macOS could not create the requested keyboard shortcut.")
  }
  down.flags = flags
  up.flags = flags
  down.post(tap: .cghidEventTap)
  usleep(60_000)
  up.post(tap: .cghidEventTap)
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
  currentLabel: String
) throws {
  let buttonFrame = try elementFrame(button)
  try performPress(button)
  usleep(200_000)
  let root = AXUIElementCreateApplication(application.processIdentifier)
  let menus = descendants(of: root) {
    attributeString($0, kAXRoleAttribute as CFString) == "AXMenu"
  }.compactMap { menu -> (AXUIElement, CGFloat)? in
    guard let frame = try? elementFrame(menu) else { return nil }
    return (menu, hypot(frame.midX - buttonFrame.midX, frame.midY - buttonFrame.midY))
  }
  guard let menu = menus.min(by: { $0.1 < $1.1 })?.0,
        let currentKey = accessModeKey(for: currentLabel) else {
    try? postKey(53)
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
    try? postKey(53)
    throw HelperFailure.message("ChatGPT did not expose another access mode in its menu.")
  }
  try performPress(target)
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

private func adjustReasoningLevel(
  from control: ReasoningControl,
  in application: NSRunningApplication,
  delta: Int
) throws {
  let requestedLevel = control.level?.shifted(by: delta)
  if control.level != nil && requestedLevel == nil {
    throw HelperFailure.message(delta > 0
      ? "Codex reasoning is already at its highest level."
      : "Codex reasoning is already at its lowest level.")
  }
  let shortcutKey: CGKeyCode = delta > 0 ? 32 : 38
  try postChord(shortcutKey, flags: [.maskControl, .maskAlternate, .maskShift])
  let confirmed = waitForValue(attempts: 16, intervalMicroseconds: 100_000) { () -> Bool? in
    guard let area = try? codexArea(for: application),
          let observed = reasoningControl(in: area) else { return nil }
    if let requestedLevel { return observed.level == requestedLevel ? true : nil }
    return !observed.label.isEmpty && observed.label != control.label ? true : nil
  }
  guard confirmed != nil else {
    throw HelperFailure.message(
      "Codex did not confirm the reasoning shortcut. Reload ChatGPT once so it reads the Vibe Pocket keybindings."
    )
  }
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

private func togglePlanMode() throws {
  try postChord(35, flags: [.maskControl, .maskAlternate, .maskShift])
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
  let (application, area) = try desktop(activateDesktop: false)
  try verifyForeground(application)
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
  case "status":
    let (application, area) = try desktop(activateDesktop: false)
    return statusReply(application: application, area: area)
  case "attach":
    let (_, area) = try desktop(activateDesktop: true)
    _ = try focusPrompt(in: area)
    return ["ok": true, "message": "Focused the visible ChatGPT Codex input line."]
  case "control":
    guard let rawControl = arguments.dropFirst().first,
          let control = DesktopControl(rawValue: rawControl) else {
      throw HelperFailure.message("Unsupported Vibe Pocket desktop control.")
    }
    let (application, area) = try desktop(activateDesktop: false)
    if control == .approve && controlButton(.approve, in: area) == nil {
      try verifyForeground(application)
    }
    try press(control, in: area)
    usleep(120_000)
    return ["ok": true, "message": "Pressed the ChatGPT Codex \(control.rawValue) control."]
  case "voice-start", "voice-stop":
    let desiredActive = action == "voice-start"
    let (application, area) = try desktop(activateDesktop: false)
    guard controlButton(.voice, in: area) != nil else {
      throw HelperFailure.message(DesktopControl.voice.unavailableMessage)
    }
    if desiredActive {
      if !voiceIsActive(in: area) { try press(.voice, in: area) }
    } else {
      // A short press can release before ChatGPT has published the microphone
      // state. Re-read once so the release still closes the just-started PTT.
      let currentArea: AXUIElement
      if voiceIsActive(in: area) {
        currentArea = area
      } else {
        usleep(100_000)
        currentArea = try codexArea(for: application)
      }
      if voiceIsActive(in: currentArea) { try press(.voice, in: currentArea) }
    }
    return [
      "ok": true,
      "message": desiredActive ? "Started Codex dictation." : "Stopped Codex dictation.",
    ]
  case "navigate":
    guard let direction = arguments.dropFirst().first else {
      throw HelperFailure.message("A navigation direction is required.")
    }
    let (application, _) = try desktop(activateDesktop: false)
    try verifyForeground(application)
    try postKey(try keyCode(for: direction))
    return ["ok": true, "message": "Navigated \(direction) in ChatGPT Codex."]
  case "access-cycle":
    let (application, area) = try desktop(activateDesktop: false)
    guard controlButton(.stop, in: area) == nil,
          let button = accessModeButton(in: area) else {
      throw HelperFailure.message("The ChatGPT Codex access mode is not currently adjustable.")
    }
    let before = accessModeLabel(in: area) ?? ""
    try selectNextAccessMode(from: button, in: application, currentLabel: before)
    return ["ok": true, "message": "Requested the next ChatGPT Codex access mode."]
  case "plan-mode":
    let (application, area) = try desktop(activateDesktop: false)
    try verifyForeground(application)
    guard controlButton(.stop, in: area) == nil else {
      throw HelperFailure.message("Plan mode cannot be changed while the visible Codex task is running.")
    }
    try togglePlanMode()
    return ["ok": true, "message": "Requested the next Codex collaboration mode."]
  case "reasoning":
    guard let rawDelta = arguments.dropFirst().first,
          let delta = Int(rawDelta), delta == -1 || delta == 1 else {
      throw HelperFailure.message("Reasoning depth must move by one step.")
    }
    let (application, area) = try desktop(activateDesktop: false)
    try verifyForeground(application)
    guard let control = reasoningControl(in: area) else {
      throw HelperFailure.message("The ChatGPT Codex reasoning level is not currently adjustable.")
    }
    try adjustReasoningLevel(
      from: control,
      in: application,
      delta: delta
    )
    return ["ok": true, "message": "Requested the next ChatGPT Codex reasoning level."]
  case "clear-input":
    let (_, area) = try desktop(activateDesktop: false)
    try clearInput(in: area)
    return ["ok": true, "message": "Cleared the ChatGPT Codex input line."]
  case "focus-agent":
    guard let agentID = arguments.dropFirst().first,
          agentID.hasPrefix("agent-"), agentID.count <= 80 else {
      throw HelperFailure.message("A valid Codex agent ID is required.")
    }
    let (application, area) = try desktop(activateDesktop: false)
    let scope = codexScope(for: application)
    let selectedTaskTitle = focusedTaskTitle(in: scope)
    let agents = agentTargets(
      in: scope,
      currentTaskState: taskState(in: AreaIndex(area)),
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
          let updatedScope = codexScope(for: application)
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

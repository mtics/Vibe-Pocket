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
  let root = AXUIElementCreateApplication(application.processIdentifier)
  return try codexArea(in: root)
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

private let taskListLabels: Set<String> = ["任务", "tasks"]

private func normalizedAttribute(_ element: AXUIElement, _ attribute: CFString) -> String {
  attributeString(element, attribute)
    .trimmingCharacters(in: .whitespacesAndNewlines)
    .lowercased()
}

private func taskList(in root: AXUIElement) -> AXUIElement? {
  let lists = descendants(of: root, maxDepth: 22) {
    attributeString($0, kAXRoleAttribute as CFString) == "AXList"
  }
  let candidates = lists.compactMap { list -> (list: AXUIElement, rows: Int, named: Bool)? in
    let rows = taskRows(in: list).count
    guard rows > 0 else { return nil }
    let named = taskListLabels.contains(normalizedAttribute(list, kAXDescriptionAttribute as CFString))
      || taskListLabels.contains(normalizedAttribute(list, kAXTitleAttribute as CFString))
    return (list, rows, named)
  }
  let namedCandidates = candidates.filter(\.named)
  return (namedCandidates.isEmpty ? candidates : namedCandidates)
    .max(by: { $0.rows < $1.rows })?.list
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

private func taskIsFocused(_ element: AXUIElement) -> Bool {
  let classes = Set(attributeStrings(element, "AXDOMClassList" as CFString))
  return classes.contains("bg-token-list-hover-background")
    || attributeBool(element, kAXSelectedAttribute as CFString)
    || attributeBool(element, kAXFocusedAttribute as CFString)
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

private func agentTargets(in root: AXUIElement, currentTaskState: String) -> [AgentTarget] {
  guard let list = taskList(in: root) else { return [] }
  let allLists = descendants(of: root, maxDepth: 22) {
    attributeString($0, kAXRoleAttribute as CFString) == "AXList"
  }
  let currentRow = allLists.lazy.flatMap(taskRows(in:)).first(where: { row in
    taskIsFocused(row.content) || taskIsFocused(row.target)
  })
  let visibleTaskTitle = currentRow.flatMap { taskLabel(in: $0.content) }
  var orderedRows = [TaskRow]()
  if let currentRow { orderedRows.append(currentRow) }
  for row in taskRows(in: list) {
    guard let label = taskLabel(in: row.content),
          !orderedRows.contains(where: { existing in
            taskLabel(in: existing.content).map { taskLabelsMatch($0, label) } == true
          }) else { continue }
    orderedRows.append(row)
    if orderedRows.count == 6 { break }
  }
  var seen = Set<String>()
  var candidates = [(id: String, element: AXUIElement, stateElement: AXUIElement, label: String)]()
  for (taskIndex, row) in orderedRows.enumerated() {
    guard let label = taskLabel(in: row.content) else { continue }
    let id = stableAgentID(for: row.content, fallbackPath: "sidebar-task-\(taskIndex)\u{0}\(label)")
    guard seen.insert(id).inserted else { continue }
    candidates.append((id: id, element: row.target, stateElement: row.content, label: label))
    if candidates.count == 6 { break }
  }
  return candidates.map { candidate in
    let focused = visibleTaskTitle.map { taskLabelsMatch($0, candidate.label) } ?? false
    return AgentTarget(
      id: candidate.id,
      element: candidate.element,
      label: candidate.label,
      state: focused ? currentTaskState : taskIndicatorState(in: candidate.stateElement),
      focused: focused
    )
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
    "reasoning": reasoningPopup(in: index) != nil,
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
  let root = AXUIElementCreateApplication(application.processIdentifier)
  let agents = agentTargets(in: root, currentTaskState: currentState)
  let reasoning = reasoningPopup(in: index)
  let controls = controlAvailability(in: index, hasAgents: !agents.isEmpty)
  return [
    "ok": true,
    "available": true,
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

private func hoverElement(_ element: AXUIElement) throws -> CGPoint? {
  AXUIElementPerformAction(element, "AXScrollToVisible" as CFString)
  let frame = try elementFrame(element)
  let point = CGPoint(x: frame.midX, y: frame.midY)
  let previousLocation = CGEvent(source: nil)?.location
  guard let source = CGEventSource(stateID: .hidSystemState),
        let move = CGEvent(
          mouseEventSource: source,
          mouseType: .mouseMoved,
          mouseCursorPosition: point,
          mouseButton: .left
        ) else {
    throw HelperFailure.message("macOS could not move the pointer to the requested menu item.")
  }
  CGWarpMouseCursorPosition(point)
  move.post(tap: .cghidEventTap)
  usleep(350_000)
  return previousLocation
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
  try clickElement(button)
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
  try clickElement(target)
  usleep(180_000)
}

private func menuItemLabel(_ item: AXUIElement) -> String {
  [
    attributeString(item, kAXTitleAttribute as CFString),
    attributeString(item, kAXDescriptionAttribute as CFString),
    attributeString(item, kAXValueAttribute as CFString),
  ].lazy.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
    .first(where: { !$0.isEmpty }) ?? ""
}

private func selectCompactMenuOption(
  from popup: AXUIElement,
  in application: NSRunningApplication,
  categoryPrefixes: [String],
  targetIndex: ([String]) throws -> Int
) throws -> String {
  try clickElement(popup)
  usleep(200_000)
  var shouldCancelMenu = true
  defer {
    if shouldCancelMenu { try? postKey(53) }
  }
  let initialRoot = AXUIElementCreateApplication(application.processIdentifier)
  guard let categoryItem = descendant(of: initialRoot, where: { candidate in
    guard attributeString(candidate, kAXRoleAttribute as CFString) == "AXMenuItem" else { return false }
    let description = attributeString(candidate, kAXDescriptionAttribute as CFString).lowercased()
    return categoryPrefixes.contains(where: description.hasPrefix)
  }) else {
    throw HelperFailure.message("ChatGPT did not expose the requested compact selector category.")
  }
  let categoryFrame = try elementFrame(categoryItem)
  let previousLocation = try hoverElement(categoryItem)
  defer {
    if let previousLocation { CGWarpMouseCursorPosition(previousLocation) }
  }
  usleep(500_000)
  let root = AXUIElementCreateApplication(application.processIdentifier)
  let submenus = descendants(of: root) {
    attributeString($0, kAXRoleAttribute as CFString) == "AXMenu"
  }.compactMap { menu -> (AXUIElement, CGFloat)? in
    guard let frame = try? elementFrame(menu),
          frame.maxX <= categoryFrame.minX + 8 || frame.minX >= categoryFrame.maxX - 8 else { return nil }
    return (menu, hypot(frame.midX - categoryFrame.midX, frame.midY - categoryFrame.midY))
  }
  guard let submenu = submenus.min(by: { $0.1 < $1.1 })?.0 else {
    throw HelperFailure.message("ChatGPT did not open the requested compact selector submenu.")
  }
  let options = children(of: submenu).filter {
    attributeString($0, kAXRoleAttribute as CFString) == "AXMenuItem" && !menuItemLabel($0).isEmpty
  }
  let labels = options.map(menuItemLabel)
  let selectedIndex = try targetIndex(labels)
  guard options.indices.contains(selectedIndex) else {
    throw HelperFailure.message("The requested compact selector option is unavailable.")
  }
  try postKey(124)
  usleep(120_000)
  try postKey(115)
  usleep(80_000)
  for _ in 0..<selectedIndex {
    try postKey(125)
    usleep(60_000)
  }
  try postKey(36)
  // Let the selector commit before a queued dial step opens it again. The
  // bridge's follow-up status scan verifies the visible result asynchronously.
  usleep(260_000)
  shouldCancelMenu = false
  return labels[selectedIndex]
}

private func reasoningLevelKey(for label: String) -> String? {
  let normalized = label.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
  if normalized == "最小" || normalized == "minimal" { return "minimal" }
  if normalized == "轻度" || normalized == "低" || normalized == "low" { return "low" }
  if normalized == "中" || normalized == "medium" { return "medium" }
  if normalized == "高" || normalized == "high" { return "high" }
  if normalized == "极高" || normalized.hasPrefix("极高 ")
    || normalized == "xhigh" || normalized == "extra high" || normalized.hasPrefix("extra high ") {
    return "xhigh"
  }
  return nil
}

private struct ReasoningSelection {
  let model: String
  let level: String
}

private func reasoningSelection(from title: String) -> ReasoningSelection? {
  let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
  let suffixes = ["extra high", "minimal", "medium", "xhigh", "high", "low", "轻度", "最小", "极高", "中", "高", "低"]
  let lowered = trimmed.lowercased()
  for suffix in suffixes.sorted(by: { $0.count > $1.count }) {
    guard lowered == suffix || lowered.hasSuffix(" \(suffix)") else { continue }
    let cutoff = trimmed.index(trimmed.endIndex, offsetBy: -suffix.count)
    let model = trimmed[..<cutoff].trimmingCharacters(in: .whitespacesAndNewlines)
    guard !model.isEmpty, let level = reasoningLevelKey(for: suffix) else { return nil }
    return ReasoningSelection(model: model, level: level)
  }
  return nil
}

private func selectReasoningLevel(
  from popup: AXUIElement,
  in application: NSRunningApplication,
  currentLevel: String,
  delta: Int
) throws {
  _ = try selectCompactMenuOption(
    from: popup,
    in: application,
    categoryPrefixes: ["推理强度", "reasoning"],
    targetIndex: { labels in
      var unique = [(index: Int, key: String)]()
      for (index, label) in labels.enumerated() {
        guard let key = reasoningLevelKey(for: label), !unique.contains(where: { $0.key == key }) else { continue }
        unique.append((index, key))
      }
      guard let currentIndex = unique.firstIndex(where: { $0.key == currentLevel }) else {
        throw HelperFailure.message("ChatGPT did not expose the current reasoning level in its submenu.")
      }
      let nextIndex = currentIndex + delta
      guard unique.indices.contains(nextIndex) else {
        throw HelperFailure.message(delta > 0
          ? "Codex reasoning is already at its highest level."
          : "Codex reasoning is already at its lowest level.")
      }
      return unique[nextIndex].index
    }
  )
}

private func selectModel(
  _ model: String,
  from popup: AXUIElement,
  in application: NSRunningApplication
) throws {
  _ = try selectCompactMenuOption(
    from: popup,
    in: application,
    categoryPrefixes: ["模型", "model"],
    targetIndex: { labels in
      guard let index = labels.firstIndex(where: {
        $0.caseInsensitiveCompare(model) == .orderedSame
      }) else {
        throw HelperFailure.message("ChatGPT did not expose the requested model in its submenu.")
      }
      return index
    }
  )
}

private func focusPrompt(in area: AXUIElement) throws -> AXUIElement {
  guard let input = prompt(in: area) else {
    throw HelperFailure.message("The current Codex task has no accessible message input.")
  }
  let result = AXUIElementSetAttributeValue(input, kAXFocusedAttribute as CFString, kCFBooleanTrue)
  if result != .success || !attributeBool(input, kAXFocusedAttribute as CFString) {
    try clickElement(input)
    guard attributeBool(input, kAXFocusedAttribute as CFString) else {
      throw HelperFailure.message("ChatGPT did not focus the current Codex input line.")
    }
  }
  return input
}

private func togglePlanMode(in area: AXUIElement) throws {
  _ = try focusPrompt(in: area)
  try postChord(35, flags: [.maskControl, .maskAlternate, .maskShift])
  usleep(180_000)
}

private func clearInput(in area: AXUIElement) throws {
  let input = try focusPrompt(in: area)
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
    let (_, area) = try desktop(activateDesktop: true)
    try press(control, in: area)
    usleep(120_000)
    return ["ok": true, "message": "Pressed the ChatGPT Codex \(control.rawValue) control."]
  case "voice-start", "voice-stop":
    let desiredActive = action == "voice-start"
    let (application, area) = try desktop(activateDesktop: true)
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
    _ = try desktop(activateDesktop: true)
    try postKey(try keyCode(for: direction))
    return ["ok": true, "message": "Navigated \(direction) in ChatGPT Codex."]
  case "access-cycle":
    let (application, area) = try desktop(activateDesktop: true)
    guard controlButton(.stop, in: area) == nil,
          let button = accessModeButton(in: area) else {
      throw HelperFailure.message("The ChatGPT Codex access mode is not currently adjustable.")
    }
    let before = accessModeLabel(in: area) ?? ""
    try selectNextAccessMode(from: button, in: application, currentLabel: before)
    return ["ok": true, "message": "Requested the next ChatGPT Codex access mode."]
  case "plan-mode":
    let (_, area) = try desktop(activateDesktop: true)
    guard controlButton(.stop, in: area) == nil else {
      throw HelperFailure.message("Plan mode cannot be changed while the visible Codex task is running.")
    }
    try togglePlanMode(in: area)
    return ["ok": true, "message": "Requested the next Codex collaboration mode."]
  case "reasoning":
    guard let rawDelta = arguments.dropFirst().first,
          let delta = Int(rawDelta), delta == -1 || delta == 1 else {
      throw HelperFailure.message("Reasoning depth must move by one step.")
    }
    let (application, area) = try desktop(activateDesktop: true)
    guard let popup = reasoningPopup(in: area) else {
      throw HelperFailure.message("The ChatGPT Codex reasoning level is not currently adjustable.")
    }
    let beforeTitle = attributeString(popup, kAXTitleAttribute as CFString)
    guard let before = reasoningSelection(from: beforeTitle) else {
      throw HelperFailure.message("ChatGPT exposed an unrecognized model and reasoning selection.")
    }
    try selectReasoningLevel(from: popup, in: application, currentLevel: before.level, delta: delta)
    return ["ok": true, "message": "Requested the next ChatGPT Codex reasoning level."]
  case "clear-input":
    let (_, area) = try desktop(activateDesktop: true)
    try clearInput(in: area)
    return ["ok": true, "message": "Cleared the ChatGPT Codex input line."]
  case "focus-agent":
    guard let agentID = arguments.dropFirst().first,
          agentID.hasPrefix("agent-"), agentID.count <= 80 else {
      throw HelperFailure.message("A valid Codex agent ID is required.")
    }
    let (application, area) = try desktop(activateDesktop: true)
    let root = AXUIElementCreateApplication(application.processIdentifier)
    let agents = agentTargets(in: root, currentTaskState: taskState(in: AreaIndex(area)))
    guard let agent = agents.first(where: { $0.id == agentID }) else {
      throw HelperFailure.message("That Codex agent is no longer visible.")
    }
    if !agent.focused {
      func taskBecameFocused(attempts: Int) throws -> Bool {
        for _ in 0..<attempts {
          usleep(150_000)
          let updatedArea = try codexArea(for: application)
          let updatedRoot = AXUIElementCreateApplication(application.processIdentifier)
          let updatedAgents = agentTargets(
            in: updatedRoot,
            currentTaskState: taskState(in: AreaIndex(updatedArea))
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
      if !semanticPressWorked {
        try clickElement(agent.element)
      }
      let taskIsNowFocused = semanticPressWorked ? true : try taskBecameFocused(attempts: 8)
      guard taskIsNowFocused else {
        throw HelperFailure.message("ChatGPT did not switch to the selected Codex task.")
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

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
  guard application.activate(options: [.activateIgnoringOtherApps]) else {
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
    let title = attributeString($0, kAXTitleAttribute as CFString).trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    let description = attributeString($0, kAXDescriptionAttribute as CFString).trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    return control.labels.contains(title) || control.labels.contains(description)
  })
}

private func controls(in area: AXUIElement) -> [String: Bool] {
  Dictionary(uniqueKeysWithValues: DesktopControl.allCases.map { control in
    (control.rawValue, controlButton(control, in: area) != nil)
  })
}

private func interactiveControls(in area: AXUIElement) -> [[String: Any]] {
  let roles: Set<String> = [
    "AXButton",
    "AXCheckBox",
    "AXIncrementor",
    "AXMenuButton",
    "AXPopUpButton",
    "AXRadioButton",
    "AXSlider",
    "AXTextArea",
  ]
  return descendants(of: area, where: {
    roles.contains(attributeString($0, kAXRoleAttribute as CFString))
  }).compactMap { element in
    let role = attributeString(element, kAXRoleAttribute as CFString)
    let title = attributeString(element, kAXTitleAttribute as CFString)
      .trimmingCharacters(in: .whitespacesAndNewlines)
    let description = attributeString(element, kAXDescriptionAttribute as CFString)
      .trimmingCharacters(in: .whitespacesAndNewlines)
    guard role == "AXTextArea" || !title.isEmpty || !description.isEmpty else { return nil }
    return [
      "role": role,
      "title": title,
      "description": description,
      "enabled": attributeBool(element, kAXEnabledAttribute as CFString),
      "focused": attributeBool(element, kAXFocusedAttribute as CFString),
    ]
  }
}

private func press(_ control: DesktopControl, in area: AXUIElement) throws {
  guard let button = controlButton(control, in: area) else {
    throw HelperFailure.message(control.unavailableMessage)
  }
  guard AXUIElementPerformAction(button, kAXPressAction as CFString) == .success else {
    throw HelperFailure.message("ChatGPT did not accept the requested Codex control.")
  }
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
    reply([
      "ok": true,
      "available": true,
      "message": "Ready to control the visible ChatGPT Codex task.",
      "controls": controls(in: area),
    ])
  case "attach":
    _ = try desktop(activateDesktop: true)
    reply(["ok": true, "message": "Attached to the visible ChatGPT Codex task."])
  case "inspect":
    let (_, area) = try desktop(activateDesktop: false)
    reply(["ok": true, "controls": interactiveControls(in: area)])
  case "control":
    guard let rawControl = CommandLine.arguments.dropFirst(2).first,
          let control = DesktopControl(rawValue: rawControl) else {
      throw HelperFailure.message("Unsupported Vibe Pocket desktop control.")
    }
    let (_, area) = try desktop(activateDesktop: true)
    try press(control, in: area)
    usleep(120_000)
    reply(["ok": true, "message": "Pressed the ChatGPT Codex \(control.rawValue) control."])
  default:
    throw HelperFailure.message("Unsupported Vibe Pocket desktop action.")
  }
} catch {
  let message = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
  FileHandle.standardError.write(Data(message.utf8))
  FileHandle.standardError.write(Data("\n".utf8))
  exit(1)
}

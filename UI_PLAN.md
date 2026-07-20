# Vibe Pocket UI Plan

Status: implementation baseline

Target device: Xiaomi 13, portrait, approximately `393 x 873dp`

Primary scenario: the user looks at Codex on the Mac while operating the phone with one thumb

## Decision

Vibe Pocket should be a fixed, low-glance Codex control surface. It should not be a small remote desktop, a terminal mirror, or a generic macro grid.

The selected direction combines three independently explored approaches:

- Fixed instrument board for stable geometry and muscle memory.
- State-aware console for truthful remote state and command feedback.
- Eyes-free thumb surface for reach, haptics, and error prevention.

The resulting rule is strict:

> State may change text, availability, and feedback inside reserved slots. It must not move a daily control, change its meaning, or make it disappear.

This preserves all daily controls on the main Control surface. Settings and option pickers may use dedicated surfaces because they are configuration or value selection, not hidden daily commands.

## First Principles

### Object

The controlled object is the visible Codex task on the Mac. The phone is an intent input device and a compact status display.

### User attention

The user's gaze normally remains on the Mac. A good interaction must therefore succeed with little or no visual search on the phone.

### Action

Every visible control represents one semantic action such as `navigate(up)`, `delete_backward`, `select_model(id)`, or `approve`. A profile input such as `key_focus` or `touch` is only a transport binding. It is not a second visible action.

### Evidence

The phone can immediately prove that it recognized a touch. It cannot claim that the Mac changed until a fresh, task-bound observation confirms the effect.

### Safety

Actions with opposite or irreversible outcomes cannot run concurrently. A timeout means the outcome is unknown unless the remote side explicitly reports failure.

## Evidence Summary

### Current implementation

- [`Screen.kt`](android/app/src/main/java/au/edu/uts/vibepocket/ui/control/Screen.kt) derives visible controls directly from profile inputs and sends unclassified inputs to `additionalInputs`. This exposes transport topology and creates duplicate semantic buttons.
- [`Stage.kt`](android/app/src/main/java/au/edu/uts/vibepocket/ui/control/stage/Stage.kt) changes height by state. Every change moves the D-pad and the action buttons.
- [`State.kt`](android/app/src/main/java/au/edu/uts/vibepocket/ui/control/state/State.kt) does not include `transportFresh` in its main status projection, so stale data may still appear as Ready.
- [`Agents.kt`](android/app/src/main/java/au/edu/uts/vibepocket/ui/control/Agents.kt) may expose up to 24 agents in one long rail without a skip mechanism for accessibility services.
- [`Reasoning.kt`](android/app/src/main/java/au/edu/uts/vibepocket/ui/control/Reasoning.kt) uses `42dp` step controls, below Android's `48dp` accessibility baseline.
- [`Dispatch.kt`](android/app/src/main/java/au/edu/uts/vibepocket/input/Dispatch.kt) preserves important HID and Bridge safety boundaries, but the UI receives only compressed pending information.
- [`Prediction.kt`](android/app/src/main/java/au/edu/uts/vibepocket/session/Prediction.kt) optimistically changes Reasoning for up to three seconds. This is the source of visible values that may lead the desktop and later snap back.
- Model and Reasoning already have native Codex thread-setting paths through [`settings.mjs`](bridge/src/task/settings.mjs). Mode should use the same authoritative settings contract instead of opening or cycling a desktop menu.
- The current test tree has strong transport and state unit coverage, but no Compose screenshot suite, accessibility checks, or fixed-coordinate tests.

### Product and interaction research

- [Codex Micro](https://openai.com/supply/co-lab/work-louder/) validates fixed agent keys, dedicated accept/reject/voice/new-task controls, workflows, and a continuously visible reasoning control.
- [OpenMicro](https://github.com/stephenleo/OpenMicro) validates semantic Codex actions, capability-aware mappings, and explicit gaps when a control cannot be implemented reliably.
- [Stream Deck Mobile](https://www.elgato.com/us/en/s/stream-deck-mobile), [Touch Portal](https://touchportal.com/), and [Deckboard](https://deckboard.app/) show the value of stable locations, but also the cost of dense generic grids, nested pages, and hidden profiles.
- Android recommends at least [`48dp` touch targets](https://developer.android.com/develop/ui/compose/accessibility/api-defaults). One-handed thumb research reports approximately [`9.2mm` for discrete targets and `9.6mm` for serial targets](https://www.microsoft.com/en-us/research/publication/target-size-study-for-one-handed-thumb-use-on-small-touchscreen-devices/). Daily blind controls should therefore target `56-88dp`, not merely satisfy `48dp`.
- Android recommends restrained, predefined [action-oriented haptics](https://developer.android.com/develop/ui/views/haptics/haptics-principles).
- WCAG requires [4.5:1 normal-text contrast](https://www.w3.org/TR/WCAG22/) and [3:1 non-text UI contrast](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html). Color cannot be the only state signal.
- Codex app-server exposes `thread/list` with `recency_at`, `model/list`, `collaborationMode/list`, thread settings updates, and settings notifications in its [official app-server documentation](https://github.com/openai/codex/blob/main/codex-rs/app-server/README.md).

## Rejected Directions

| Direction | Why it is rejected |
|---|---|
| Generic Stream Deck grid | It optimizes for the number of commands, not blind operation or Codex state. |
| State-dependent button replacement | It saves space by destroying muscle memory and makes hazardous actions unpredictable. |
| Floating joystick | Codex navigation is discrete. A floating origin adds acquisition and drift without adding useful control. |
| Full-screen touchpad or invisible gestures | It lacks discoverability, collides with MIUI gestures, and has poor TalkBack semantics. |
| More workflow or Layer pages | It repeats the exact hierarchy cost the user asked to remove. |
| Desktop menu automation for Model, Reasoning, or Mode | It steals focus, can leave menus open, and confuses requested state with confirmed state. |
| Snackbar for every phase | Repeated messages compete with the Mac, appear as flashing, and may visually disturb the board. |
| Automatic control reordering | Recency is useful at hydration time, but live reordering breaks both touch memory and accessibility focus. |

## Main Board

Use `WindowInsets.safeDrawing`. On the Xiaomi 13, the expected safe portrait surface is approximately `393 x 824dp`. Horizontal page padding is `16dp`, leaving `361dp` for controls.

### Vertical geometry

| Y | Height | Region |
|---:|---:|---|
| `0-56` | `56dp` | App bar |
| `64-124` | `60dp` | Agents |
| `128-180` | `52dp` | Status and Focus |
| `188-252` | `64dp` | Layers |
| `260-336` | `76dp` | Workflows |
| `344-588` | `244dp` | D-pad and adjacent actions |
| `596-656` | `60dp` | Clear, Reject, Stop |
| `664-732` | `68dp` | Model and Reasoning |
| `740-824` | `84dp` | Voice |

Every row has a stable height. Ready, Running, Question, Decision, Error, Stale, and pending states use exactly the same geometry.

```text
+-----------------------------------------------------+
| Vibe Pocket                              [Settings] |
+-----------------------------------------------------+
| [Focused agent] [Recent agents ...]          [Next] |
+-----------------------------------------------------+
| [state] Working - current task              [Focus] |
+-----------------------------------------------------+
| [1 Code] [2] [3] [4] [5] [6]                       |
+-----------------------------------------------------+
| [Review PR] [Debug] [Refactor] [Tests]              |
+--------------------------------+--------------------+
|              [ Up ]            | [Mode - Plan]      |
|       [Left] [    ] [Right]     | [Delete]           |
|             [Down]             | [New task]         |
|                                | [Accept]            |
+--------------------------------+--------------------+
| [Clear]            [Reject]              [Stop]      |
+-----------------------------------------------------+
| [Model - GPT-5]     [-] [Reasoning - Medium] [+]    |
+-----------------------------------------------------+
|                  [ Hold to talk ]                    |
+-----------------------------------------------------+
```

### Horizontal geometry

- Agents: `297dp` lazy horizontal rail, `8dp` gap, `56dp` Next button.
- Status: `289dp` status rail, `8dp` gap, `64dp` Focus button.
- Layers: six `56.8 x 64dp` slots with `4dp` gaps.
- Workflows: four `84.25 x 76dp` buttons with `8dp` gaps.
- Main controls: `244 x 244dp` D-pad, `12dp` gap, `105 x 244dp` action column.
- Action column: four `105 x 58dp` controls with `4dp` gaps: Mode, Delete, New task, Accept.
- Safety row: three approximately `110 x 60dp` controls with larger separation: Clear, Reject, Stop.
- Selectors: `145 x 68dp` Model, `8dp` gap, `208 x 68dp` Reasoning.
- Voice: `361 x 84dp`.

### Why this fits the task

- Voice remains the largest and lowest target because it is expected to be the most frequent action.
- Model and Reasoning are directly above Voice, always visible.
- Mode stays beside the D-pad, followed by Delete, New task, and Accept.
- Workflows and Layers remain on the main surface without consuming the thumb's primary zone.
- Clear is separate from Delete. Delete removes one character and supports held repeat. Clear is a deliberate full-draft action.
- Stop is separated from Accept and requires a short hold. Reject and Clear use longer holds.

## Region Behavior

### App bar

- Show the product name, compact connection state, and Settings.
- Remove the permanent Refresh button. Pull-to-refresh is a recovery gesture, not a primary command.
- Never place Reset or Disconnect on the main board.

### Agents

- Query by `recency_at desc` at hydration.
- Pin the focused task first, then attention-required tasks, then recent tasks.
- Freeze slot order while the board remains open. Update activity in place.
- Keep every loaded agent in the main horizontal rail. Lazy loading is not a separate Agents page.
- Next selects the next task requiring attention, then the next recent task.
- TalkBack announces `Focused, 1 of 24` and provides a `Skip agents` custom action.
- Duplicate labels never determine identity. Use stable thread IDs.

### Status

- Replace the large Stage card with one fixed `52dp` rail.
- Show icon, state text, task title, and age of the last confirmed observation.
- Reserve the right slot for Focus Codex. It is always visible and enabled only when a safe focus route exists.
- Tap opens complete question or error details as an overlay. It does not push the board.
- Use a polite live region only for Working, Needs input, Completed, Failed, Stale, and Offline transitions.

### Layers

- Six stable single-selection slots remain on the board.
- Each slot uses number, shape, and color. Color is never the only identifier.
- Show each label once. Do not repeat `Layer` in every item.
- The active full name is available through semantics and the selected slot. Long names ellipsize without resizing the row.

### Workflows

- Keep Review PR, Debug, Refactor, and Tests in one `76dp` row.
- Use Material icons with text. Do not reuse one icon for different workflows.
- Use neutral surfaces with a semantic icon accent rather than four unrelated colored cards.
- A workflow remains pending until the new task or command effect is observed.

### D-pad

- Use a fixed `244dp` square, approximately `41mm` on the Xiaomi 13.
- Use a `3 x 3` hit grid. Four corners and the center are neutral.
- Each direction is approximately `81dp`; diagonals are impossible.
- Capture the active pointer. Allow a small outer continuation zone while keeping neighboring controls outside it.
- Start repeat after `350ms`, repeat approximately every `90ms`, and do not vibrate on every repeat.
- Direction semantics do not mirror in RTL.

### Mode

- Render Mode as a real selector button with the confirmed value and chevron.
- The phone picker selects an exact collaboration mode. It must not cycle an unknown desktop menu.
- Populate choices from `collaborationMode/list` and mutate the bound thread through the authoritative thread-settings contract.
- Keep the confirmed value visible while a target is pending: `Default -> Plan`.
- If the installed Codex version cannot update collaboration mode on the bound thread, disable the control with a specific capability reason. Do not fall back to mouse-driven Accessibility.

### Delete and Clear

- Delete is `delete_backward`, uses the standard Backspace icon, and repeats while held.
- Clear is `clear_input`, uses a distinct clear-all icon, and requires a `600ms` hold.
- Neither action changes label or meaning according to draft state.

### Model

- The main button shows the complete confirmed model label where space permits.
- Tap opens a phone-native bottom sheet with current selection, stable model IDs, and at least `48dp` rows.
- Selection calls the native `select_model` path. It never opens the desktop model menu.
- Close the sheet immediately after selection. Keep the old value until `thread/settings/updated` or a fresh bound snapshot confirms the new model.
- Close the sheet on context change, Voice start, Stale, Offline, or Question.

### Reasoning

- Keep `-`, current value, and `+` in the fixed `208dp` region. Every target is at least `48dp`.
- `-` and `+` resolve the exact adjacent supported effort before delivery.
- Tapping the value opens the phone picker for exact effort selection.
- Add an Android `SelectReasoning(level)` command instead of exposing only `reasoning_depth(delta)`.
- Display `Medium -> High` while pending. Replace the confirmed value only after observation.
- When unavailable, preserve the geometry and show the reason in semantics. Do not turn the control into a blank gap.

### Voice

- Voice always occupies the bottom `84dp` row and consumes navigation insets correctly.
- Normal mode is press and hold. TalkBack mode is tap to start and tap to stop.
- It controls ChatGPT desktop dictation. Phone-microphone routing is an optional audio source, not the definition of Voice.
- Voice release remains available even when the connection becomes stale or another context change is blocked.

## State Model

One enum cannot truthfully represent the system. Project four independent dimensions:

| Dimension | Values |
|---|---|
| Transport | `Fresh`, `Stale`, `Offline` |
| Binding | `Confirmed`, `Reconciling`, `Conflict`, `Unbound` |
| Work | `Ready`, `Running`, `Question`, `Decision`, `Error` |
| Operation | `Idle`, `Queued`, `Sent`, `Acknowledged`, `Observing`, `Observed`, `Unknown`, `Failed` |

### Display priority

1. No usable snapshot or Bridge: Offline or Error.
2. Old transport, unresolved binding, or Agent/Desktop conflict: Stale.
3. Structured question: Question.
4. Explicit desktop error: Error.
5. Approval or decision: Decision.
6. Thinking or executing: Running.
7. Otherwise: Ready.

`transportFresh=false` can never project to Ready.

### Source conflict

- Agent chips display their own activity.
- The status rail displays the confirmed visible desktop task.
- If the focused Agent and visible desktop disagree within the same observation generation, show Conflict and reconcile. Do not choose one silently.
- Bridge snapshots need `observedAt`, stable `contextId`, and binding state.

## Command Feedback

Use the rule `optimistic intent, pessimistic effect`.

| Time or event | UI response |
|---|---|
| Touch, `0-100ms` | Press state and light haptic. This means locally recognized. |
| Before `700ms` | Keep geometry and label stable. Use the reserved indicator slot. |
| After `700ms` | Show an in-place spinner in the reserved `18-20dp` slot. |
| Bridge accepted | Show Sent or Waiting for Mac. Do not play success. |
| Fresh bound observation matches | Update the confirmed value and use confirm feedback. |
| Explicit failure | Show Failed with reject feedback and a bounded retry path. |
| More than `10s` or indeterminate result | Show Outcome unknown. Keep the last confirmed value and do not replay automatically. |

Toast or Snackbar is reserved for errors that need explanation or action. It overlays above Voice and never changes layout height.

### Conflict groups

The UI and dispatch layer must share conflict groups:

- `decision`: Accept, Reject.
- `context`: Agent, Layer, Model, Mode, New task.
- `draft`: Delete, Clear, workflow insertion.
- `voice`: Voice start and release.
- `run`: Stop and operations that start a new run.
- `navigation`: D-pad repeat ownership.

Two different buttons in one exclusive group cannot execute concurrently. Unrelated groups remain responsive.

## Visual System

The visual direction is a quiet, light-first instrument panel. Dark mode remains supported but is not the permanent default.

```text
Light canvas       #F4F7F8
Light surface      #FFFFFF
Light container    #E9EFF2
Light text         #172126
Light secondary    #45545B
Light outline      #5C6B72

Dark canvas        #0F1417
Dark surface       #151B1E
Dark container     #1D2529
Dark text          #E6EEF2
Dark secondary     #BAC7CD
Dark outline       #8D9BA2

Primary            #006A6A / light
Primary dark       #80D8D8 / dark
Info               #005FAE
Waiting            #755600
Error              #B3261E
```

- Use semantic Material color roles and paired on-colors.
- Use one primary accent for selection and Voice. Reserve blue, amber, green, and red for state meaning.
- Use `8dp` radius or less, `1dp` outline, no decorative shadows, gradients, or color blobs.
- Do not represent enabled, selected, pending, or failed using color alone. Combine icon, text, outline, and state description.
- Use Material Symbols consistently. Standard actions keep standard icons.
- Reserve a fixed icon/status slot inside every command button so pending feedback cannot move text.
- Motion is limited to an `80-120ms` press transition and an in-place progress indicator. No layout animation or animated reflow.

## Pairing and Settings

### Pairing

- Primary flow: scan a short-lived QR invitation generated by the Mac host.
- Secondary flow: open an invitation deep link on the phone.
- Manual Bridge URL and token entry moves under Advanced recovery.
- The connect screen explains only the current step and status. It does not expose the entire Bridge configuration by default.
- Show explicit `Discovering`, `Claiming`, `Authorizing`, and `Ready` phases inside one stable region.
- On the local network, invitation acceptance to a usable board should be `p95 < 2s` after authorization.
- Accepting a pairing request may update connection content, but it must not resize or reorder board controls.

### Settings

Settings is a full-screen low-frequency surface rather than one extremely long bottom sheet.

Sections:

1. Connection: host, device identity, status, repair pairing.
2. Hardware: HID state and left/right-handed board preset.
3. Controls: Layer labels and semantic bindings.
4. Workflows: prompt definitions.
5. Appearance and accessibility: theme, haptics, reduced motion.
6. Danger: reset profile and disconnect.

The app bar contains Back, Reset, and Save. Save is sticky and enabled only for valid changed data. Reset requires confirmation. Disconnect is never a main-board button.

## Adaptive Layouts

### Portrait target

The `393 x 873dp` Xiaomi 13 portrait layout is the blind-operation reference and must not scroll vertically at default font scale.

### Short portrait and 200% text

It is physically impossible to preserve all controls, a `41mm` D-pad, and all labels inside a `360 x 640dp` window. Do not solve this by shrinking touch targets.

- Keep the bottom blind board fixed.
- Allow only the information region above it to scroll.
- Keep every daily command on the same Control surface.
- Use a dedicated accessibility geometry class selected by window height and font scale. Geometry remains stable across runtime states within that class.
- Labels may wrap to two lines. Full semantics remain available when visual text ellipsizes.

### Landscape

- Use a two-column layout: Agents, status, Layers, workflows, Model, and Reasoning on one side; D-pad, adjacent actions, safety row, and Voice on the other.
- Mark landscape as operational but not eyes-free certified. The portrait geometry is the muscle-memory contract.

### RTL and handedness

- Use start/end for text and rails.
- Do not mirror the physical meaning of D-pad directions.
- Handedness is an explicit user setting that mirrors the D-pad and adjacent action column as one unit. It never changes automatically.

## Code Structure

Directory context should carry domain meaning. Keep local names short and avoid product prefixes or mechanical suffixes.

```text
ui/control/board/
  Board.kt       composition only
  Layout.kt      geometry selected from window and font scale
  Catalog.kt     semantic projection and de-duplication
  State.kt       display projection from transport, binding, work, operation
  Agents.kt
  Status.kt
  Layers.kt
  Workflows.kt
  Pad.kt
  Actions.kt
  Selectors.kt
  Voice.kt
```

Functional flow:

```text
Snapshot + Profile
        |
        v
Catalog.project()  -- semantic controls, one visible instance per intent
        |
        v
State.project()    -- truth, availability, pending phase, disabled reason
        |
        v
Layout.of()        -- stable geometry for the current device class
        |
        v
Board()            -- rendering only
        |
        v
Dispatch           -- existing HID/Bridge plan and delivery barriers
```

`Catalog` de-duplicates by `(action type, normalized arguments)`. It chooses one canonical executable binding but never exposes binding IDs to the layout.

Keep the existing `Dispatch`, `Delivery`, context-transition barrier, HID recovery, and Voice ownership rules. Extend their outputs instead of recreating transport behavior in Compose.

After the new board is authoritative, confirm that `Deck.kt` and `Context.kt` have no callers and remove the dead competing layout rather than maintaining two UI architectures.

## Implementation Batches

### Batch 0: Truth before layout

- Add snapshot observation time, context identity, and binding confidence.
- Add the four-dimensional status projection and ensure stale data cannot display Ready.
- Expose operation phases beyond `inFlightIds`.
- Add conflict groups for multi-touch and non-idempotent actions.
- Make Voice gating consistent across Model, Mode, Reasoning, Agent, and Layer.
- Add exact `SelectReasoning(level)` and `SelectMode(mode)` commands through native thread settings.

Exit gate: state and command tests prove Confirmed, Unknown, Failed, Stale, and Conflict behavior.

### Batch 1: Semantic board

- Introduce `Catalog` and fixed board slots.
- Remove `additionalInputs` from visible composition.
- Deduplicate `focus_next` and any other profile aliases.
- Stabilize Agent order and add Next attention.
- Replace variable Stage with fixed Status.

Exit gate: all runtime states keep core target centers within `1dp`.

### Batch 2: Main interaction surface

- Implement the measured rows, D-pad, action column, safety row, selectors, and bottom Voice.
- Add direct phone pickers for Mode, Model, and Reasoning.
- Add correct Delete, Clear, hold, repeat, cancel-on-slide-out, and haptic behavior.
- Apply light-first visual tokens and standard icons.

Exit gate: Xiaomi 13 default portrait shows every daily control without vertical scrolling or overlay.

### Batch 3: Pairing and settings

- Make QR/deep-link invitation the primary pairing route.
- Move manual URL/token entry to Advanced.
- Replace the long Settings sheet with the structured full-screen settings surface.
- Add sticky Save, explicit Reset, and separate Disconnect.

Exit gate: a fresh phone pairs without typing an address or token; invalid configuration cannot be saved.

### Batch 4: Accessibility and performance

- Add short-height, landscape, handedness, font-scale, and RTL geometry classes.
- Add TalkBack collection and status semantics.
- Add reduced-motion behavior and performance instrumentation.
- Remove dead layout code after parity verification.

Exit gate: the complete verification matrix passes.

## Verification Matrix

### Pure and protocol tests

- Semantic catalog de-duplicates physical mappings without losing executable bindings.
- State priority covers Fresh, Stale, Offline, Conflict, Question, Decision, Running, and Error.
- Exact Mode, Model, and Reasoning requests update only the intended bound thread.
- Out-of-order responses cannot replace a newer context.
- Accept plus Reject, Delete plus Clear, and Stop plus New task cannot execute concurrently.
- Indeterminate HID delivery never triggers an unsafe Bridge replay.

### Compose tests

- Add official [Compose Preview Screenshot Testing](https://developer.android.com/training/testing/ui-tests/screenshot), which Android recommends for visual Compose verification.
- Golden states: Ready, Running, Question, Decision, Error, Stale, Offline, pending, Unknown, Voice active.
- Devices: `360 x 640dp`, `393 x 873dp`, `600 x 960dp`, and `873 x 393dp`.
- Font scales: `1.0`, `1.3`, and `2.0`.
- Themes and languages: light, dark, Chinese, English, `en-XA`, and `ar-XB`.
- Assert target size, no overlap, no clipping, no hidden command, and stable coordinates.
- Enable Compose [automated accessibility checks](https://developer.android.com/develop/ui/compose/accessibility/testing) for labels, contrast, target size, and traversal order.

### Device tests

- Test on the Xiaomi 13 with gesture navigation and three-button navigation.
- Test with TalkBack, left and right hand presets, screen rotation, Voice held during connection loss, and 24 agents.
- Measure QR claim and authorization separately. Local invitation acceptance to Ready must be `p95 < 2s`, with no board flash or coordinate change.
- Use JankStats and a release-profile Macrobenchmark. Android recommends measuring performance on a [physical device](https://developer.android.com/codelabs/jetpack-compose-performance).
- Local visual feedback: `p95 < 100ms`.
- Fresh snapshot to visible update: `p95 < 150ms`.
- Janky frame rate during repeated controls: less than `1%`.
- Blind press hit rate: at least `98%` over 30 presses per primary control.
- Adjacent-target error rate: at most `0.5%`.
- D-pad wrong-direction rate: at most `1%`.
- Destructive accidental activation: `0 / 1000` trials.

## Completion Definition

The UI redesign is complete only when all of the following are proven on the current implementation:

- Every daily control is visible on the Xiaomi 13 Control surface without vertical scrolling at default scale.
- Core controls do not move between runtime states.
- No visible control is created from a duplicate physical mapping.
- Stale and conflicting observations cannot appear as Ready.
- Mode, Model, and Reasoning complete on the phone without leaving a desktop menu open.
- Confirmed values never advance solely because a command was sent.
- Clear and Delete are distinct and behave correctly.
- Agent ordering is recency-aware, stable during use, and can load the full running set.
- Voice remains fixed, responsive, and releasable.
- Light and dark themes meet contrast requirements and no screen is dominated by the old dark brown palette.
- Screenshot, accessibility, state, transport, performance, and Xiaomi 13 device gates all pass.

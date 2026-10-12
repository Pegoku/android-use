# Android Use

"Computer use" for your Android phone. Type a task like

> Open Revolut, get my EUR balance and email it to me@example.com

and an agent drives the phone through the UI (screenshots + accessibility tree, taps, typing, swipes)
until the task is done. When it finishes, it messages **"Task done: …"** or **"Task failed: …"** to a
WhatsApp chat (default: the `Notes` group).

- **Device control: [Shizuku](https://shizuku.rikka.app/).** A Shizuku user service runs with the shell
  (adb) uid. It holds a UiAutomation connection for fast UI-tree reads, taps, swipes and text entry,
  and uses `screencap` and `am start` for screenshots and launches. No root and no accessibility
  service needed.
- **Model: your ChatGPT/Codex subscription.** The app signs in with the same OAuth flow as `codex login`
  and calls the Codex Responses endpoint (`chatgpt.com/backend-api/codex/responses`), so usage counts
  against your plan, not an API key.

## Setup

1. Install and start Shizuku (wireless debugging, `adb`, or root).
2. Build and install: `./gradlew installDebug`.
3. Open **Android Use**:
   - **Grant Shizuku access.**
   - **Sign in with ChatGPT.** This opens the browser on `auth.openai.com`, and the app catches the
     `http://localhost:1455/auth/callback` redirect itself.
     Alternatively, **Import auth.json** from a desktop `~/.codex/auth.json`. Because refresh tokens rotate,
     the phone and the desktop may then sign each other out, so signing in on the phone is preferred.
4. Optional: in **Settings**, pick the models and reasoning effort, the step limit, and the WhatsApp
   report chat.

## Default assistant

Android Use can replace Gemini or Google Assistant as your **default digital assistant**. In the
app, tap **Make default assistant**. With Shizuku running this is a single tap; otherwise it opens
the system setting. After that, long-press Home (or the power button, if set up) to open the
assistant over whatever app you're in:

- **Chat:** answers stream in. It uses web search for current information such as weather, news
  and opening hours, and replies in the language you write in.
- **Do things:** ask it to act ("text Anna I'm late", "turn on dark mode", "start this timer").
  The overlay steps aside while the agent works, then comes back with the outcome.
- **Ask about the screen:** the screen is captured before the overlay draws. Tap **Ask about
  screen** (or **+**) to attach it to your next message, so you can say things like "what's this?"
  or "reply to her".
- **Other controls:** a mic for voice input, read-aloud, copy, new chat and minimize.

Chat replies use the fast model (about 1 s until the reply starts streaming). Phone tasks use the
agent described below.

## Models

Routine steps run on a **fast model** (default `gpt-6-luna`, about 2 s per turn). The **main model**
(default `gpt-6.1-sol`, about 5 s per turn) takes over for a few turns when an action fails, the agent
repeats itself, or a turn changes nothing. Only the main model can end a task, so a "done" from the
fast model is always double-checked. Clear the fast model field to use the main model throughout.

You can also share text from any app to Android Use to prefill a task.

The phone must be unlocked while a task runs. The agent presses Home, then works in the foreground.
Tap **Stop** in the app or in the notification to abort.

## Shell access and approvals

- **`shell_read`** is always available. It runs a fixed allowlist of read-only queries, such as
  `getprop`, `dumpsys battery`, `settings get`, `pm list` and `wm size`. Commands run directly, not
  through `sh -c`, so `;`, `|` and `$(…)` can't chain anything.
- **`shell`** is off by default; turn it on in **Settings**. It runs any other command, with a
  reason the agent must give. Destructive commands (`rm`, `pm uninstall`/`clear`, `reboot`,
  `content delete`, factory reset, anything touching Shizuku or Android Use) are always refused.

Shell commands and messages to recipients that your task doesn't name are **sensitive actions**:

- **Auto-review** (the default): a reviewer model (`codex-auto-review`) sees your original task, the
  exact action, the agent's stated reason, the recent steps and the on-screen text. On-screen text
  is marked as untrusted. The reviewer approves actions that clearly serve the task, denies
  unrelated ones or ones prompted by on-screen content (prompt injection), and asks you when unsure.
- **Always ask me**: every sensitive action waits for you to tap **Approve** or **Deny**, either in
  the notification or in the app. Unanswered requests are denied after 3 minutes.

## CI/CD

`.github/workflows/android.yml` runs on every push and pull request:

1. Lint and run unit tests.
2. Build debug and release APKs.
3. Upload the APKs as workflow artifacts.

Each push to `main` also updates a rolling **nightly** pre-release with the latest APKs. Pushing a
`v*` tag (for example `git tag v0.2.0 && git push origin v0.2.0`) publishes a versioned GitHub
release.

To give releases a stable signature, so that updates install over the previous version, add these
repository secrets:

| Secret | Value |
| --- | --- |
| `KEYSTORE_B64` | `base64 -w0 release.jks` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

Without these secrets, the release APK is signed with a throwaway debug key, and it won't install
over a previous build. For local release builds, put `storeFile`, `storePassword`, `keyAlias` and
`keyPassword` in a git-ignored `keystore.properties` file.

## How it works

| Piece | File |
| --- | --- |
| Shell-uid service hosted by Shizuku | `shell/ShellService.kt`, `IShellService.aidl` |
| Screenshot / UI-tree capture, input actions | `device/Device.kt`, `device/UiTree.kt` |
| Codex OAuth (PKCE, refresh, auth.json import) | `auth/CodexAuth.kt` |
| Streaming Responses client | `llm/CodexClient.kt` |
| Observe → act loop, tools, prompt | `agent/Agent.kt`, `agent/Tools.kt` |
| Task + WhatsApp report orchestration | `agent/TaskRunner.kt`, `agent/AgentService.kt` |

On each turn, the model gets a downscaled screenshot plus a numbered list of on-screen elements
(including popups and dialogs), then calls one or more tools:

`batch`, `tap`, `type_text`, `fill_form`, `scroll`, `scroll_to`, `swipe`, `press_key`, `launch_app`,
`list_apps`, `open_uri`, `open_settings`, `compose_sms`, `compose_email`, `shell_read`, `shell`,
`wait`, `save_note`, `finish`

The tools are designed to save model round trips:

- `batch` runs several actions in one turn. Element ids are re-mapped if the screen shifts, and the
  batch stops at the first failure.
- `fill_form` fills several fields in one call.
- `scroll_to` keeps swiping until the target text is on screen.

After each turn, the agent waits for the UI to settle rather than sleeping for a fixed time.

Only the latest screenshot is sent in full, which keeps long tasks cheap. Text is set directly on
the focused field through accessibility. If that fails, the agent falls back to `input text` or a
clipboard paste.

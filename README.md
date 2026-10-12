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

## Models

Routine steps run on a **fast model** (default `gpt-6-luna`, about 2 s per turn). The **main model**
(default `gpt-6.1-sol`, about 5 s per turn) takes over for a few turns when an action fails, the agent
repeats itself, or a turn changes nothing. Only the main model can end a task, so a "done" from the
fast model is always double-checked. Clear the fast model field to use the main model throughout.

You can also share text from any app to Android Use to prefill a task.

The phone must be unlocked while a task runs. The agent presses Home, then works in the foreground.
Tap **Stop** in the app or in the notification to abort.

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
`list_apps`, `open_uri`, `compose_email`, `wait`, `save_note`, `finish`

The tools are designed to save model round trips:

- `batch` runs several actions in one turn. Element ids are re-mapped if the screen shifts, and the
  batch stops at the first failure.
- `fill_form` fills several fields in one call.
- `scroll_to` keeps swiping until the target text is on screen.

After each turn, the agent waits for the UI to settle rather than sleeping for a fixed time.

Only the latest screenshot is sent in full, which keeps long tasks cheap. Text is set directly on
the focused field through accessibility. If that fails, the agent falls back to `input text` or a
clipboard paste.

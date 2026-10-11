# Android Use

"Computer use" for your Android phone. Type a task like

> Open Revolut, get my EUR balance and email it to me@example.com

and an agent drives the phone through the UI (screenshots + accessibility tree, taps, typing, swipes)
until the task is done. When it finishes, it messages **"Task done: …"** or **"Task failed: …"** to a
WhatsApp chat (default: the `Notes` group).

- **Device control: [Shizuku](https://shizuku.rikka.app/).** A Shizuku user service runs with the shell
  (adb) uid and executes `screencap`, `uiautomator dump`, `input`, and `am start`. No root and no
  accessibility service needed.
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
4. Optional: in **Settings**, pick the model and reasoning effort, the step limit, and the WhatsApp
   report chat.

You can also share text from any app to Android Use to prefill a task.

The phone must be unlocked while a task runs. The agent presses Home, then works in the foreground.
Tap **Stop** in the app or in the notification to abort.

## How it works

| Piece | File |
| --- | --- |
| Shell-uid service hosted by Shizuku | `shell/ShellService.kt`, `IShellService.aidl` |
| Screenshot / UI-tree capture, input actions | `device/Device.kt`, `device/UiTree.kt` |
| Codex OAuth (PKCE, refresh, auth.json import) | `auth/CodexAuth.kt` |
| Streaming Responses client | `llm/CodexClient.kt` |
| Observe → act loop, tools, prompt | `agent/Agent.kt`, `agent/Tools.kt` |
| Task + WhatsApp report orchestration | `agent/TaskRunner.kt`, `agent/AgentService.kt` |

On each step, the model gets a downscaled screenshot plus a numbered list of on-screen elements,
then calls one tool:

`tap`, `type_text`, `scroll`, `swipe`, `press_key`, `launch_app`, `list_apps`, `open_uri`,
`compose_email`, `wait`, `save_note`, `finish`

Only the latest screenshot is sent in full, which keeps long tasks cheap. Text with non-ASCII
characters is typed by pasting from the clipboard.

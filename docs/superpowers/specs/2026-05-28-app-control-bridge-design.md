# App Control Bridge + Background Notifications — Design

**Date:** 2026-05-28
**App:** `moe.momokko.sillytavernapp` (SillyTavern Android)

## Goal

Let SillyTavern (running in the WebView) control native app behavior and surface background activity:

1. **Configurable idle auto-exit.** The user can set, from inside SillyTavern, how many minutes of background idle
   before the app auto-exits (kills the process to free resources). **Default is 0 = never auto-exit**, so the app does
   not surprise users by closing itself.
2. **Background reply notifications.** When the AI finishes responding while the app is in the background, post a
   heads-up notification showing the character name and a short message preview. Tapping it reopens the app.

## Background / Motivation

- Node cannot restart in-process (nodejs-mobile), so "auto-exit" means killing the app process; reopening is a fresh
  cold start (assets are not re-extracted — see `AssetExtractor`).
- The previous build hard-coded a 5-minute idle kill, which closed the app unexpectedly. We are making auto-exit opt-in
  and adding a control surface.
- SillyTavern exposes `globalThis.SillyTavern.getContext()` (see `public/script.js:292`,
  `public/scripts/st-context.js:114`), returning `eventSource`, `eventTypes` (incl. `GENERATION_ENDED`), `name2` (active
  character), and `chat`. This lets injected JS observe generation completion without a forked ST.

## Key Design Decision: split "pause" from "kill"

Backgrounding now triggers two independent behaviors:

- **Pause WebView** (`onPause` + `pauseTimers`) once any in-flight generation finishes — always, for battery. Node stays
  alive, so the app resumes instantly.
- **Kill process** — only when the user-configured idle timeout is > 0, armed for that duration. Default 0 means never.

This makes the default experience "go quiet in the background, resume instantly," with auto-exit as an opt-in for power
users.

## Components

### 1. Native bridge — `WebAppBridge`

An inner class of `MainActivity`, registered via `webView.addJavascriptInterface(bridge, "STAndroid")` in `onCreate`, *
*before** the URL is loaded. Exposed as `window.STAndroid` in the WebView.

`@JavascriptInterface` methods (each marshals to the main thread via the activity's `Handler`):

| Method                                           | Behavior                                                                           |
|--------------------------------------------------|------------------------------------------------------------------------------------|
| `getIdleTimeoutMinutes(): Int`                   | Reads `SharedPreferences("ST_PREFS")` key `idle_timeout_min`, default `0`.         |
| `setIdleTimeoutMinutes(min: Int)`                | Clamps `min >= 0`, persists it, and live-reconfigures the current idle scheduling. |
| `notifyAiResponded(title: String, body: String)` | Posts a notification on the `ai_reply` channel.                                    |

**Security:** `addJavascriptInterface` exposes these to all JS in the WebView. Only trusted local `127.0.0.1` content is
ever loaded, and the surface is minimal (one clamped int + two display strings, no file/system access), so the exposure
is acceptable. `@JavascriptInterface` annotation is required on each method (API 17+ rule).

### 2. Notifications

- New channel `ai_reply` with `IMPORTANCE_HIGH` (heads-up), created lazily.
- Notification content: `title` = `"<character> replied"`, `body` = preview (~100 chars). Built by the injected script;
  native just renders the strings it is handed.
- Content intent: `PendingIntent` launching `MainActivity` (`FLAG_ACTIVITY_SINGLE_TOP`), auto-cancel on tap.
- Runtime `POST_NOTIFICATIONS` permission (Android 13+/`TIRAMISU`) requested in `onCreate` alongside the existing
  all-files-access flow. The permission is already declared in the manifest. If denied, `notifyAiResponded` is a silent
  no-op (the OS drops the post).

### 3. Idle logic rework (`MainActivity`)

Replaces the fixed `IDLE_TIMEOUT_MS` constant with a value read from `SharedPreferences` (default 0).

- `onStop` → `checkBusyThenIdle()`:
    - busy > 0: keep rendering, re-poll after `BUSY_POLL_MS` (unchanged — this is also what allows a backgrounded
      generation to finish and fire the notification).
    - idle: `webView.onPause()` + `pauseTimers()` (battery). Then, **only if** `idleTimeoutMinutes > 0`, arm the
      `ACTION_STOP` kill after `idleTimeoutMinutes * 60_000` ms.
- `onStart` → cancel any pending kill/poll, `webView.onResume()` + `resumeTimers()`.
- `setIdleTimeoutMinutes` from the bridge updates the stored value; if the app is currently backgrounded and idle, it
  re-arms/cancels accordingly.

### 4. Injected script — `assets/st-app-extension.js`

Injected immediately after `webview-hooks.js` in `MainActivity.injectBusyHooks()` (renamed conceptually to "inject app
scripts"), on every `onPageFinished`.

- Idempotency guard (`window.__stAppExtInit`).
- Polls (bounded retries) for `window.SillyTavern?.getContext`. Once available:
    - Registers `eventSource.on(eventTypes.GENERATION_ENDED, handler)`.
    - `handler`: if `document.hidden` and `window.STAndroid`, call `getContext()` fresh, derive `name = ctx.name2`, find
      the last message in `ctx.chat` (last non-user entry), strip markdown/HTML, trim to ~100 chars, then
      `STAndroid.notifyAiResponded(name, preview)`.
    - Injects a settings block into ST's Extensions panel (`#extensions_settings`): a labeled number input "Auto-exit
      after idle (minutes, 0 = never)", initialized from `STAndroid.getIdleTimeoutMinutes()`, writing back via
      `STAndroid.setIdleTimeoutMinutes()` on change. Native prefs are the single source of truth; nothing is stored in
      ST settings.
- Every `window.STAndroid` access is guarded so the script is inert in a normal browser (no crash if the bridge is
  absent).

## Data Flow

```
App start
  └─ MainActivity.onCreate
       ├─ addJavascriptInterface(WebAppBridge, "STAndroid")
       ├─ request POST_NOTIFICATIONS (Android 13+)
       └─ idleTimeoutMinutes = prefs.get (default 0)
  └─ page load → inject webview-hooks.js + st-app-extension.js

Generation ends while backgrounded
  └─ st-app-extension GENERATION_ENDED handler (document.hidden)
       └─ STAndroid.notifyAiResponded(char, preview)
            └─ heads-up notification → tap → MainActivity (cold or warm)

User edits "Auto-exit" in ST Extensions panel
  └─ STAndroid.setIdleTimeoutMinutes(n)
       └─ persist + re-arm/cancel idle kill

Background idle
  └─ pause WebView (battery)
  └─ if timeout > 0: kill process after timeout (START_NOT_STICKY → no zombie restart)
```

## Error Handling

- Bridge methods: wrapped, marshalled to main thread; bad input clamped (`min < 0 → 0`).
- JS: all bridge calls guarded by `window.STAndroid` presence; `getContext` polling gives up gracefully after a bounded
  number of attempts.
- Notification permission denied: post is a no-op; no crash, no retry loop.
- `GENERATION_ENDED` with empty/blank message: skip the notification (nothing useful to show).

## Out of Scope (YAGNI)

- Persisting the idle setting in ST's `extension_settings` (native prefs are authoritative).
- Notification actions beyond tap-to-open (no reply/dismiss actions).
- Per-character or per-chat notification rules.
- Foreground notifications (only background completion notifies).

## Testing (manual, on-device — consistent with prior milestones)

1. Start a generation, background the app immediately → on completion a heads-up notification shows `<char> replied` +
   preview; tapping reopens the app.
2. Foreground during generation → no notification (only fires when `document.hidden`).
3. Set "Auto-exit" = 1 in the ST Extensions panel, background while idle → process exits after ~1 min (
   `adb shell ps | grep momokko` empty); reopen → clean cold start.
4. Default (0) → background while idle for >5 min → app does NOT exit; reopening is instant (WebView resumes).
5. Deny notification permission → no notifications, no crash.

## Files

- **Modify:** `app/src/main/java/moe/momokko/sillytavernapp/MainActivity.kt` (bridge, notifications, permission, idle
  rework, inject second script)
- **Modify:** `app/src/main/AndroidManifest.xml` (only if launch flags need adjusting; permission already present)
- **Create:** `app/src/main/assets/st-app-extension.js`
- **Possibly create:** a small `Notifications` helper if `MainActivity` grows unwieldy.

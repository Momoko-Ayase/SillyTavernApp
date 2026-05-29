# Design: bundled git, file picker/export, .nomedia, notifications

Date: 2026-05-29
Status: Approved

Four changes to the SillyTavern Android embed:

1. Bundle a static `git` so extensions can be updated/version-checked/branch-switched.
2. Make file import (file picker) and file export (save dialog) work in the WebView.
3. Drop a `.nomedia` in the SillyTavern data dir so exports don't pollute the gallery.
4. Add a "test notification" button and notify on AI waiting / abnormal-response states.

---

## 1. Bundle a static `git` (arm64-v8a)

### Problem

SillyTavern 1.18.0's `/api/extensions/install` uses a pluggable git client
(`src/git/client.js`) that falls back to **isomorphic-git** (pure JS, already bundled)
when no system `git` is on `PATH`, so *installing* extensions already works.

But `src/endpoints/extensions.js` `/update`, `/version`, `/branches`, and `/switch`
call `simpleGit(...)` directly, which spawns the literal binary `git` over `PATH`.
With no `git` present those endpoints fail — extensions can be installed but never
updated or switched.

### Decision

Bundle a real static `git` binary rather than rewrite the four endpoints onto
isomorphic-git. `git.backend` stays `auto`, so isomorphic-git remains the automatic
fallback for `/install` if the binary is ever missing.

### Android exec constraint

Apps targeting API 29+ cannot `execve` a file from a writable app dir (`filesDir`,
`cacheDir`) — SELinux denies it. Only `nativeLibraryDir` is executable, and it is
populated from APK `lib/<abi>/*.so` entries. Therefore the git executables must be
shipped as `lib*.so` in `jniLibs` (exactly how `libnode.so` already ships), and we
reach them by name through symlinks placed in a writable dir on `PATH`. Executing a
symlink resolves to the target inode in `nativeLibraryDir`, which is exec-allowed
(the technique Termux uses).

### Binaries (built locally, gitignored like libnode)

Built fully static + PIE for arm64-v8a, placed in `app/libgit/bin/arm64-v8a/`:

- `libgit.so` — the `git` executable.
- `libgitremotehttps.so` — the `git-remote-https` helper, with libcurl + a TLS
  backend (OpenSSL) + zlib statically linked in, so there are **no** shared-lib
  dependencies at runtime.

A CA bundle ships as a normal asset: `app/src/main/assets/git/cacert.pem`
(from <https://curl.se/ca/cacert.pem>).

These two `.so` files and `cacert.pem` are gitignored build inputs the user supplies,
documented alongside the existing `libnode`/`nodejs-project` build steps.

### Gradle

```kotlin
sourceSets["main"].jniLibs.srcDir("libgit/bin")   // in addition to libnode/bin
```

`useLegacyPackaging = true` already keeps them uncompressed and unstripped in
`nativeLibraryDir`.

### Runtime wiring — new `GitSetup.kt`, invoked from `NodeService.startNodeOnce()` before `startNodeWithArguments`

1. **Extract CA bundle** once per APK update (reuse the `ST_PREFS` / `apk_last_update`
   gate pattern, or piggyback on `AssetExtractor`): copy `assets/git/cacert.pem` to
   `filesDir/git/cacert.pem`.
2. **Build the bin dir** `filesDir/gitbin/` with symlinks (recreated each start; cheap
   and self-healing):
    - `git` → `<nativeLibraryDir>/libgit.so`
    - `git-remote-https` → `<nativeLibraryDir>/libgitremotehttps.so`
    - `git-remote-http` → `<nativeLibraryDir>/libgitremotehttps.so`

   Use `Os.symlink` (android.system) and delete-then-create so stale links after an
   update are replaced.
3. **Set env** via `NodeBridge.setEnv` (before Node starts, so children inherit it):
    - `PATH` = `<gitbin>:<existing PATH>` (read current via `System.getenv("PATH")`)
    - `GIT_EXEC_PATH` = `<gitbin>` (so `git` finds `git-remote-https`)
    - `GIT_SSL_CAINFO` = `<filesDir>/git/cacert.pem`
    - `GIT_CONFIG_NOSYSTEM` = `1` (no `/etc/gitconfig`)
    - `GIT_TERMINAL_PROMPT` = `0` (never block on credential prompts)
    - `GIT_AUTHOR_NAME` / `GIT_AUTHOR_EMAIL` / `GIT_COMMITTER_NAME` /
      `GIT_COMMITTER_EMAIL` = a fixed local identity (e.g. `SillyTavern` /
      `noreply@localhost`) so `git pull` can create a merge/ff commit without a
      configured user.

`HOME` is already set to `filesDir`; a per-user `.gitconfig` is unnecessary because the
identity comes from env.

### Notes / risks

- `command-exists('git')` (used only by `/install`'s `auto` backend selection) shells
  out with `exec('command -v git')` via `/bin/sh`, which may not resolve on Android.
  This does **not** matter: install falls back to isomorphic-git, and the other four
  endpoints call `simpleGit` directly (libuv `PATH` lookup, no shell), which works.
- Smart-HTTP clone/fetch/pull need only `git` + `git-remote-https`; no shell, no
  `/bin/sh`, no extra libexec helpers.

---

## 2. File import (picker) + export (save dialog)

### Import — `WebChromeClient.onShowFileChooser`

Add a `WebChromeClient` to the WebView in `MainActivity.onCreate` overriding
`onShowFileChooser`. On invocation:

- Read `fileChooserParams` for `acceptTypes` (map to intent MIME types; default `*/*`)
  and `mode` (`MODE_OPEN_MULTIPLE` → allow multiple).
- Launch `ACTION_OPEN_DOCUMENT` (`CATEGORY_OPENABLE`) through an
  `ActivityResultLauncher<Intent>` registered in `onCreate`.
- Keep the pending `ValueCallback<Array<Uri>>` in a field; on result call it with the
  selected `Uri[]`, or `null` on cancel (so the `<input type=file>` doesn't hang). If a
  new chooser opens while one is pending, resolve the old callback with `null` first.

### Export — patched blob handling + `STAndroid.saveFile`

ST's `download()` (public/scripts/utils.js) does
`URL.createObjectURL(blob)` → `<a download>` → `a.click()` → `revokeObjectURL`. WebView
ignores `blob:` anchor downloads.

In `webview-hooks.js` (runs on every `onPageFinished`, before any export click):

- Patch `URL.createObjectURL` to record `blobUrl → Blob` in a Map, and patch
  `URL.revokeObjectURL` to defer/no-op the revoke for tracked URLs (so the blob stays
  readable until we've consumed it).
- Add a capture-phase `click` listener on `document`: if the target (or an ancestor) is
  an `a[download]` whose `href` is a tracked `blob:`/`data:` URL, `preventDefault()`,
  read the blob via `FileReader.readAsDataURL`, strip the data-URI prefix to get
  base64, and call `window.STAndroid.saveFile(fileName, base64, mimeType)`. Then clean
  up the Map entry. Guard everything so a plain browser is unaffected and `STAndroid`
  absence is a no-op (falls through to default behavior).

New bridge method `WebAppBridge.saveFile(name, base64, mime)`:

- Decode base64 to bytes, store as a pending payload (bytes + name + mime) in a field.
- Launch `ACTION_CREATE_DOCUMENT` (system "Save to…" picker) via an
  `ActivityResultLauncher`, pre-filling `EXTRA_TITLE = name` and `type = mime`.
- On result, write the pending bytes to the chosen `Uri` via
  `contentResolver.openOutputStream`. Toast on success/failure. Clear the pending
  payload.

Also register a `WebView.setDownloadListener` for any direct `http(s)`/`data:`
downloads (defensive completeness), launching the same save flow or `DownloadManager`
as appropriate. Primary path remains the blob interception above.

> Payloads cross the bridge as base64 strings. Typical exports (chat JSONL, character
> PNG, settings JSON) are KB–low-MB; acceptable. No chunking (YAGNI).

---

## 3. `.nomedia`

In `NodeService.startNodeOnce()`, right after ensuring `DATA_ROOT` exists, create
`DATA_ROOT/.nomedia` (empty file) if absent. MediaStore treats a `.nomedia` at a
directory root as recursive, hiding the whole SillyTavern tree from the gallery. Wrap
in try/catch; failure is non-fatal.

---

## 4. Notifications (only when backgrounded)

### Bridge change

Rename `WebAppBridge.notifyAiResponded(title, body)` → `notifyAi(title, body)`
(generic). Native `postAiNotification` is unchanged (same `ai_reply` channel,
`REPLY_NOTIF_ID = 2`, heads-up). The reply path, the new states, and the test button
all call `notifyAi`. Reusing one id means each state supersedes the previous.

Final bridge surface (4 methods): `getIdleTimeoutMinutes`, `setIdleTimeoutMinutes`,
`notifyAi`, `saveFile`.

### `st-app-extension.js` event wiring

Track `generating` (boolean). Only act when `document.hidden`.

- `GENERATION_STARTED` → set `generating = true`; if `document.hidden`, notify
  "Waiting for AI response…".
- `document.visibilitychange` → if becoming hidden and `generating`, notify
  "Waiting for AI response…" (covers the common case: start in foreground, then
  background while waiting).
- `GENERATION_ENDED` → set `generating = false`; if hidden: post the reply preview
  (existing behavior) if non-empty, else "AI didn't respond normally".
- `GENERATION_STOPPED` → set `generating = false`; if hidden, notify
  "AI did not respond normally".

### Test button

In the existing "Android App" settings block (`injectSettings`), add a
"Send test notification" button wired to
`window.STAndroid.notifyAi('SillyTavern', 'Test notification ✅')`. Guarded so it is
inert without `STAndroid`.

---

## Files touched

| File                                      | Change                                                                                                   |
|-------------------------------------------|----------------------------------------------------------------------------------------------------------|
| `app/build.gradle.kts`                    | add `jniLibs.srcDir("libgit/bin")`                                                                       |
| `app/libgit/bin/arm64-v8a/lib*.so`        | **new build inputs** (gitignored)                                                                        |
| `app/src/main/assets/git/cacert.pem`      | **new build input** (gitignored)                                                                         |
| `.gitignore`                              | ignore `app/libgit/` and `assets/git/cacert.pem`                                                         |
| `app/src/main/java/.../GitSetup.kt`       | **new** — symlinks + env                                                                                 |
| `app/src/main/java/.../NodeService.kt`    | call `GitSetup`; create `.nomedia`                                                                       |
| `app/src/main/java/.../MainActivity.kt`   | `WebChromeClient` file chooser; `saveFile` bridge + SAF launchers; rename `notifyAiResponded`→`notifyAi` |
| `app/src/main/assets/webview-hooks.js`    | blob `createObjectURL`/click interception                                                                |
| `app/src/main/assets/st-app-extension.js` | generation-state notifications; test button; `notifyAi` rename                                           |
| `CLAUDE.md`                               | document git bundling, file picker/export, `.nomedia`, notification states                               |

## Out of scope (YAGNI)

- Rewriting the four git endpoints onto isomorphic-git (binary covers them).
- Chunked/streamed export transfer.
- Configurable export destination beyond the system save dialog.
- Credential/auth for private extension repos.

# SillyTavern Android App — Design Spec

**Date:** 2026-05-28
**Package:** `moe.momokko.sillytavernapp`
**Status:** Approved design, pending implementation plan

## 1. Goal

A standalone Android app that runs SillyTavern with **no Termux required**. A Node.js
runtime is bundled inside the APK (via nodejs-mobile), runs the SillyTavern server on
localhost, and a full-screen WebView displays the normal SillyTavern web UI. Chat/character
data persists at `/sdcard/Documents/SillyTavern`. Mobile-oriented optimizations are applied
out of the box.

## 2. Constraints & Decisions

| Decision            | Choice                                                         | Rationale                                                               |
|---------------------|----------------------------------------------------------------|-------------------------------------------------------------------------|
| SillyTavern version | **1.18.0** (latest)                                            | Requires Node ≥ 20 (see §11)                                            |
| Node runtime        | **Self-built Node 24** `libnode.so` (Acurast `update-v24.5.0`) | No prebuilt Node ≥ 20 for Android exists; ST 1.18 needs Node ≥ 20       |
| Dependencies        | Pre-bundled in APK assets                                      | Works fully offline; no fragile first-run npm on device                 |
| CPU arch            | `arm64-v8a` only                                               | Covers ~all modern phones; smallest APK                                 |
| Data location       | `/sdcard/Documents/SillyTavern`                                | Persistent, browsable, survives uninstall                               |
| Storage permission  | `MANAGE_EXTERNAL_STORAGE` (All Files Access)                   | Node uses raw POSIX file I/O; shared storage on Android 11+ requires it |
| Server port         | `8616` (configurable)                                          | Avoid common-port collisions (8000/3000/5000/8080)                      |
| Server binding      | `127.0.0.1`, `listen: false`                                   | Localhost-only, not exposed on network                                  |
| Idle behavior       | Shut down service after ~5 min idle                            | Minimize background battery; deferred while a generation streams        |
| minSdk / targetSdk  | 26 / 34                                                        | nodejs-mobile supports 24+; modern target                               |

## 3. Architecture

```
MainActivity ──► WebView ──HTTP──► http://127.0.0.1:8616
     │ (All-Files perm, poll until ready)        ▲
     ▼                                            │
NodeService (foreground service, lifecycle owner) │
     │                                            │
     ▼                                            │
NodeBridge (JNI) ──► libnode.so ──► server.js (SillyTavern)
                                              │
                                              ▼
                              /sdcard/Documents/SillyTavern
```

## 4. Components

1. **`MainActivity` (Kotlin)** — Full-screen WebView host. On launch: verify/request
   All-Files-Access, start `NodeService`, poll `http://127.0.0.1:8616` until it responds,
   then load it. Back button maps to WebView history. Pauses/resumes WebView with lifecycle.

2. **`NodeService` (foreground service)** — Owns the Node thread and a persistent
   notification. Keeps the server alive when backgrounded. Manages the idle-shutdown timer.

3. **`NodeBridge` (JNI: `node.cpp` + `CMakeLists.txt`)** — `external fun startNodeWithArguments(args)`
   → `node::Start(argc, argv)` on a dedicated background thread. Compiled into
   `libnode-bridge.so`, linked against the prebuilt `libnode.so`.

4. **`AssetExtractor` (Kotlin)** — Copies `assets/nodejs-project/` to internal
   `files/nodejs-project/` (APK assets are not a real filesystem Node can run from).
   Writes a `version.txt` marker; re-extracts only when the bundled version changes.

5. **SillyTavern launcher + config** — A small bootstrap (`app-launcher.js`) that:
    - sets `dataRoot` → `/sdcard/Documents/SillyTavern`
    - sets `port: 8616`, `listen: false`
    - disables auto-update / version checks
    - applies mobile config defaults
    - then requires/starts SillyTavern's `server.js`.

## 5. Data Flow (cold start)

```
App opens → grant/verify All-Files perm → start NodeService
  → extract assets if version changed → start libnode → server.js boots
  → server listens on 127.0.0.1:8616, reads/writes /sdcard/Documents/SillyTavern
  → MainActivity polls 8616 → ready → WebView loads UI
```

## 6. Optimizations

- **Startup perf:** skip re-extraction via `version.txt` marker; keep service warm so
  re-opening (within idle window) avoids a Node restart; WebView cache enabled.
- **Battery/background:** foreground service prevents premature kills; WebView
  `onPause()` + `pauseTimers()` on background to stop JS timers/animations; **idle-shutdown**
  stops the service after ~5 min backgrounded with no active generation.
- **Generation tracking:** a lightweight middleware injected by `app-launcher.js` tracks
  in-flight streaming requests and exposes a tiny status endpoint
  (e.g. `/__app/active`); the idle timer defers shutdown while a generation is active.
- **WebView tuning:** hardware acceleration, DOM/local storage enabled, file access,
  mixed-content allowed (localhost http), viewport/zoom tuned for the ST UI.

## 7. Project Layout

```
app/
  src/main/
    java/moe/momokko/sillytavernapp/   MainActivity, NodeService, NodeBridge, AssetExtractor
    cpp/                                node.cpp + CMakeLists.txt
    jniLibs/arm64-v8a/libnode.so        ← self-built Node 24 (see §8a)
    assets/nodejs-project/              ← SillyTavern + node_modules + app-launcher.js + config.yaml
    AndroidManifest.xml
  build.gradle                          NDK/CMake, arm64-only abiFilters, packaging opts
```

## 8. Externally Downloaded / Built Artifacts (slow-network + heavy-build items)

Exact URLs/versions are provided in the implementation plan. The user downloads/builds these;
Claude writes all source files and build scripts.

1. **Node 24 `libnode.so` (self-built)** — clone `Acurast/nodejs-mobile` @ `update-v24.5.0`
   and cross-compile for `arm64-v8a` (see §8a). Produces `out_android/arm64-v8a/libnode.so`
    + headers in `out_android/libnode/include/node/`.
2. **SillyTavern 1.18.0 source** — release tag `1.18.0`.
3. **SillyTavern `node_modules`** — `npm install --omit=dev` on the host. SillyTavern is
   deliberately pure-JS (uses `jimp`, not native `sharp`), so host-installed modules run on
   arm64 without a native rebuild.
4. **Android NDK r24 (`24.0.8215888`) + CMake** — via Android Studio SDK Manager. NDK r24 is
   required to build libnode; CMake is for our JNI bridge.

### 8a. Building Node 24 `libnode.so` for Android (one-time)

Host: Linux (Debian/Ubuntu). Needs ~8GB+ RAM, several GB disk, ~30–90 min.

```bash
# prereqs
sudo apt-get install -y build-essential git python3 gcc-multilib g++-multilib
# NDK r24 via sdkmanager: sdkmanager "ndk;24.0.8215888"

git clone --branch update-v24.5.0 --depth 1 https://github.com/Acurast/nodejs-mobile.git
cd nodejs-mobile
./tools/android_build.sh /path/to/Android/Sdk/ndk/24.0.8215888 26 arm64
# → out_android/arm64-v8a/libnode.so   (copy to app/src/main/jniLibs/arm64-v8a/)
# → out_android/libnode/include/node/  (headers for the JNI bridge CMake include path)
```

(`26` = minSdk API level passed to `android-configure`; `arm64` = target arch.)

## 9. Division of Work

- **User:** creates the Android Studio project (empty Views Activity, package
  `moe.momokko.sillytavernapp`); installs NDK r24 + CMake; builds `libnode.so` per §8a;
  downloads SillyTavern 1.18.0 + runs `npm install --omit=dev`; builds/runs in Android Studio.
- **Claude:** writes all Kotlin sources, `node.cpp`, `CMakeLists.txt`, `AndroidManifest.xml`,
  gradle config, `app-launcher.js`, and `config.yaml`; provides exact placement instructions
  and the libnode build commands.

## 10. Risks & Mitigations

- **libnode build from an unmerged branch** → `update-v24.5.0` is not merged upstream
  (conflicts are vs. nodejs-mobile main; the Acurast branch itself should build standalone).
  Mitigation: build early as the first milestone; if it fails, fall back to Option A
  (ST 1.16.0 + official prebuilt Node 18). This is the highest-risk item — validate first.
- **NDK r24 vs. Node 24** → branch doc specifies r24; if the compile errors on toolchain
  grounds, try a newer NDK (r25/r26).
- **Build host resources** → needs ~8GB+ RAM; on a constrained machine the V8 compile may OOM.
- **Native dependency in node_modules** → would be a host (x86) binary, failing on arm64.
  Mitigation: SillyTavern avoids native deps; verify `npm ls` for native bindings and rebuild
  for arm64 if found.
- **APK size** (~100–250MB from node_modules + libnode) → acceptable; arm64-only keeps it down.
- **All-Files-Access UX** → first-launch flow must clearly route the user to the system
  settings toggle and handle denial gracefully.

## 11. Node Version Analysis (why Option B)

- SillyTavern **1.16.0** is the last release with `engines.node >= 18`;
  **1.17.0 / 1.18.0** require `>= 20`.
- No prebuilt nodejs-mobile binary exists above Node 18 (official latest `v18.20.4`;
  Acurast `main`/`acurast/main` also Node 18). Node 24 lives only on the unmerged Acurast
  `update-v24.5.0` branch (PR nodejs-mobile/nodejs-mobile#151), with **no published `.so`**.
- Decision: build Node 24 from that branch to run ST 1.18.0 (Option B). Fallback documented:
  Option A = ST 1.16.0 + official prebuilt Node 18.

```

# Building

## Prerequisites

- Android Studio with: **NDK 29.0.14206865**, **CMake 3.22.1**, **platforms;android-36**, build-tools 36.
- A Linux/macOS host for building `libnode.so` (`build-essential`, `git`, `python3`, `rsync`).
- Node + npm (host) for installing SillyTavern's dependencies.
- An **arm64-v8a** device/emulator (the app is arm64-only).

## Step 1 — Build `libnode.so` (Node 24, with ICU)

```bash
git clone --branch update-v24.5.0 --depth 1 https://github.com/Acurast/nodejs-mobile.git
cd nodejs-mobile
```

> **REQUIRED:** nodejs-mobile configures Node with `--with-intl=none` by default. SillyTavern (and its
> dependencies) use Unicode regex property escapes like `/\p{Cc}/u`, which V8 **rejects at module-compile
> time** without ICU — SillyTavern dies at boot with `SyntaxError: Invalid regular expression … Invalid
> property name`. You must build with at least small-ICU.
>
> Edit the last line of `android_configure.py` and change `--with-intl=none` to `--with-intl=small-icu`
> (small-ICU is vendored at `deps/icu-small`, so no download is needed; it satisfies the `\p{…}` escapes).
> Use `--with-intl=full-icu --download=all` instead if you need full Intl locale support (~28 MB larger).

```bash
# build (arm64; ~30–90 min, needs ~8 GB+ RAM)
./tools/android_build.sh "$HOME/Android/Sdk/ndk/29.0.14206865" 26 arm64
```

Copy the outputs into the app module:

```bash
APP=/path/to/SillyTavernApp/app
mkdir -p "$APP/libnode/bin/arm64-v8a"
cp out_android/arm64-v8a/libnode.so "$APP/libnode/bin/arm64-v8a/libnode.so"
cp -R out_android/libnode/include "$APP/libnode/include"
```

Sanity check: `app/libnode/bin/arm64-v8a/libnode.so` and `app/libnode/include/node/node.h` exist.

## Step 2 — Bundle SillyTavern 1.18.0

```bash
git clone --branch 1.18.0 --depth 1 https://github.com/SillyTavern/SillyTavern.git
cd SillyTavern
npm install          # runtime deps are pure-JS; no native .node binaries should appear
find node_modules -name "*.node"   # expected: empty
```

Copy the install into assets **without clobbering the tracked `config.yaml`** (SillyTavern has no
`config.yaml` at its root, so a plain copy is safe):

```bash
APP=/path/to/SillyTavernApp/app
cp -R ./* "$APP/src/main/assets/nodejs-project/"
```

`config.yaml` (tracked in this repo) is mobile-tuned:

- `dataRoot: /storage/emulated/0/Documents/SillyTavern`
- `port: 8616`, `listen: false` (binds 127.0.0.1 only)
- `browserLaunch.enabled: false` (no desktop browser on Android)
- `whitelistMode: true`, `securityOverride: false`, `enableServerPlugins: false`

## Step 3 — Build & run the app

Open the project in Android Studio and run on an arm64 device/emulator, or:

```bash
./gradlew :app:assembleDebug
```

On first launch the app requests **All-Files access** (needed to write `dataRoot` on shared storage).
Grant it, and SillyTavern's UI appears after the server boots.

Watch the embedded Node logs:

```bash
adb logcat -s SILLYTAVERN-NODE:*
```

Expected: `Using config path: …/files/nodejs-project/config.yaml` followed by SillyTavern's listening
banner on `:8616`. If the server stops or crashes, the loading overlay shows the exit code instead of
spinning forever.
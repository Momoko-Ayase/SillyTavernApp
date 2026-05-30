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

# libnode.so ships unstripped from nodejs-mobile and is large. Strip it (keeps the
# exported dynamic symbols native-lib links against; only debug/symtab is removed).
NDK="$HOME/Android/Sdk/ndk/29.0.14206865"
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" \
  "$APP/libnode/bin/arm64-v8a/libnode.so"
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

## Step 3 — Build static `git` (optional; needed to *update* extensions)

SillyTavern installs extensions via the bundled **isomorphic-git** (pure JS), so installing works without
this step. But *updating* / version-checking / switching branches of extensions shells out to a real `git`
binary (`simple-git`), which Android doesn't provide. This step builds a fully-static, PIE `git` for arm64.

> **Android exec constraint:** apps can only execute binaries from `nativeLibraryDir`, never from writable
> app storage. So the binaries ship as `lib*.so` in `jniLibs` (like `libnode.so`), and `GitSetup.kt`
> symlinks them onto `PATH` at runtime. They must be **fully static** (no shared-lib deps) and **PIE**.

Host tools needed: the NDK (already installed), plus `make`, `perl`, and `autoconf`-built `./configure`
scripts (shipped in the release tarballs below).

**Download list:**

- zlib — <https://zlib.net/zlib-1.3.1.tar.gz>
- OpenSSL 3.x — <https://github.com/openssl/openssl/releases/download/openssl-3.5.0/openssl-3.5.0.tar.gz>
- curl — <https://curl.se/download/curl-8.11.1.tar.gz>
- git — <https://mirrors.edge.kernel.org/pub/software/scm/git/git-2.47.1.tar.gz>
- CA bundle — <https://curl.se/ca/cacert.pem>

(Versions are examples; current releases are fine.)

```bash
# 0) toolchain env (adjust the NDK path)
export ANDROID_NDK=$HOME/Android/Sdk/ndk/29.0.14206865
export TOOLCHAIN=$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64
export API=26 TARGET=aarch64-linux-android
export PATH=$TOOLCHAIN/bin:$PATH
export PREFIX=$HOME/st-git-build/prefix
export CC=$TOOLCHAIN/bin/${TARGET}${API}-clang
export AR=$TOOLCHAIN/bin/llvm-ar RANLIB=$TOOLCHAIN/bin/llvm-ranlib
mkdir -p "$PREFIX"

# 1) zlib (static)
cd zlib-1.3.1
CC=$CC ./configure --static --prefix=$PREFIX && make -j"$(nproc)" && make install && make distclean

# 2) OpenSSL (static). no-dso/no-engine drops dso_dlfcn.o, which references
#    dlopen/dlsym and won't link into a fully-static (-static) Bionic binary.
cd ../openssl-3.5.0
./Configure android-arm64 no-shared no-dso no-engine no-tests -D__ANDROID_API__=$API --prefix=$PREFIX
make -j"$(nproc)" && make install_sw

# 3) curl (static, OpenSSL + zlib). Build AFTER OpenSSL — curl bakes in whatever
#    OpenSSL features it detects, so rebuild curl if you change OpenSSL's options
#    (e.g. no-engine, or curl will keep referencing ENGINE_* and fail to link git).
cd ../curl-8.11.1
./configure --host=$TARGET --prefix=$PREFIX \
  --with-openssl=$PREFIX --with-zlib=$PREFIX \
  --disable-shared --enable-static \
  --disable-ldap --disable-ldaps --without-libpsl --without-libidn2 \
  --disable-manual --disable-ntlm --disable-docs \
  CC=$CC AR=$AR RANLIB=$RANLIB
make -j"$(nproc)" && make install
```

git cross-compiles via plain `make` (not `./configure`, which would try to run target binaries). Create
`config.mak` in the git source dir:

> NOTE: git is compiled with the android28 clang (not android26): `getrandom()` is
> only declared at API >= 28. The binary is static and run as its own process, so
> this is independent of the app's minSdk 26 and still runs on API 26 devices
> (`getrandom` is a thin wrapper over the `__NR_getrandom` syscall present on all
> Android-26 kernels). The deps (zlib/openssl/curl) can stay at API 26.

```make
CC = aarch64-linux-android28-clang
AR = llvm-ar
uname_S = Linux
NO_GETTEXT = YesPlease
NO_PERL = YesPlease
NO_PYTHON = YesPlease
NO_TCLTK = YesPlease
NO_GITWEB = YesPlease
NO_INSTALL_HARDLINKS = YesPlease
NO_REGEX = YesPlease
NO_MMAP = YesPlease
NO_ICONV = YesPlease
# Bionic has no POSIX thread cancellation (pthread_setcancelstate / pthread_cancel),
# so git's threaded async won't compile. fork()-based async works fine.
NO_PTHREADS = YesPlease
# libexpat is only needed for dumb-HTTP/WebDAV push; smart HTTP (clone/fetch/pull)
# does not use it. Drop it so we don't have to build a static expat.
NO_EXPAT = YesPlease
NEEDS_SSL_WITH_CURL = YesPlease
NEEDS_CRYPTO_WITH_SSL = YesPlease
CURL_CONFIG = PREFIX_PLACEHOLDER/bin/curl-config
CFLAGS = -O2 -fPIE -I PREFIX_PLACEHOLDER/include
LDFLAGS = -static -pie -L PREFIX_PLACEHOLDER/lib
```

```bash
# 4) git — build just the two binaries we need
cd ../git-2.47.1
sed -i "s#PREFIX_PLACEHOLDER#$PREFIX#g" config.mak
make -j"$(nproc)" git git-remote-https
file git git-remote-https   # expect: ELF 64-bit ... ARM aarch64 ... pie executable, statically linked
```

> `NO_PTHREADS=YesPlease` above is required on Android: Bionic lacks POSIX thread cancellation, so without
> it `run-command.c` fails with `undeclared 'pthread_setcancelstate' / 'PTHREAD_CANCEL_DISABLE'`.
> If linking fails on missing curl symbols, append `-lcurl -lssl -lcrypto -lz` to `LDFLAGS` in
> `config.mak`. If another compat probe fails (e.g. `getdelim`, `regcomp`), add the matching `NO_*`/`HAVE_*`
> line and re-run. Cross-compiling git is iterative — expect a couple of passes.

Strip debug symbols (the binaries link with `debug_info` and are tens of MB
unstripped), then stage them (renamed to `lib*.so`) and the CA bundle into the app:

```bash
$TOOLCHAIN/bin/llvm-strip git git-remote-https
file git git-remote-https   # expect: statically linked, NOT stripped → now stripped
APP=/path/to/SillyTavernApp/app
mkdir -p "$APP/libgit/bin/arm64-v8a" "$APP/src/main/assets/git"
cp git              "$APP/libgit/bin/arm64-v8a/libgit.so"
cp git-remote-https "$APP/libgit/bin/arm64-v8a/libgitremotehttps.so"
cp /path/to/cacert.pem "$APP/src/main/assets/git/cacert.pem"
```

Sanity check after building the APK:

```bash
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -E 'libgit|libgitremotehttps'
# expect both lib/arm64-v8a/libgit.so and lib/arm64-v8a/libgitremotehttps.so
```

## Step 4 — Build & run the app

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

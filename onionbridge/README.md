# Routeveil Onionmasq Java bridge

Small, auditable fork of the Java facade only. The Tor/Arti native engine and IPtProxy implementation are not modified or rebuilt here.

## Provenance and licenses

- Upstream: [Tor Project Onionmasq, pinned commit `086d05145f5aa711ac300d04edc19c26360bac29`](https://gitlab.torproject.org/tpo/core/onionmasq/-/tree/086d05145f5aa711ac300d04edc19c26360bac29).
- Source subtree: `android/OnionmasqAndroid/onionmasq/src/main/java`. The pinned tree contains **34 Java files** (not 35); all were downloaded. Before applying the local patch, every file was checked against its Git blob SHA-1 from the pinned tree. [upstream-sources.json](<upstream-sources.json>) records each original blob ID and SHA-256.
- Original dual-license texts are preserved: [LICENSE-MIT](<LICENSE-MIT>) and [LICENSE-APACHE](<LICENSE-APACHE>).
- [fetch-upstream.mjs](<tools/fetch-upstream.mjs>) downloads only pinned source data and licenses, checks blob hashes, and refuses to overwrite existing files, including the patched facade. It does not execute downloaded code. It is a maintenance tool, not a build step.

The only locally modified upstream Java file is [OnionMasq.java](<src/main/java/org/torproject/onionmasq/OnionMasq.java>). Other upstream Java sources remain byte-for-byte unchanged.

## Native artifact boundary

[build.gradle](<build.gradle>) resolves this artifact into an isolated, non-transitive `nativeArtifact` configuration:

```text
org.torproject:onionmasq:1.9.1Beta-local-release@aar
size:   51388641 bytes
SHA256: 89c580cc6f462fc2a346263b414d80a9c779e6966a6e4702dddd576d48444087
```

Size and SHA-256 were independently measured from the previously downloaded upstream archive with PowerShell `Get-FileHash`. The extraction task verifies both again, and fails closed if they differ. Only `jni/**` is extracted into `build/generated/native`; **the upstream `classes.jar`, resources, manifest and other AAR payload are not dependencies or copied**. No binaries are vendored in this module's source. The generated JNI source root is `build/generated/native/jni`.

## Patch: explicit file-descriptor ownership

New overloads:

```java
OnionMasq.start(ParcelFileDescriptor tun, String bridgeLines);
OnionMasq.start(ParcelFileDescriptor tun, String bridgeLines,
               java.util.function.BooleanSupplier shouldRun);
OnionMasq.requestNativeStop();
```

The caller transfers ownership of the Java PFD to `start`. It must not use or close it concurrently. Keep a separately duplicated guard PFD in the VPN service when fail-closed handover is required.

1. Check cancellation before preparation. Validate every nonempty bridge line and reject mixed/invalid/empty supplied bridge configs instead of allowing the legacy parser to silently fall back to direct Tor.
2. Parse/select the bridge config, start IPtProxy, prepare the state paths and every JNI argument, validate the PT port, and check cancellation again **while Java still owns the PFD**.
3. Detach immediately before calling `OnionMasqJni.runProxy`. All JNI arguments have already been evaluated. Rust's pinned `run_proxy` starts with `OwnedFd::from_raw_fd(fd)` and owns the descriptor on **all** subsequent returns/errors. [Native ownership source](https://gitlab.torproject.org/api/v4/projects/tpo%2Fcore%2Fonionmasq/repository/files/crates%2Fonionmasq-mobile%2Fsrc%2Flib.rs/raw?ref=086d05145f5aa711ac300d04edc19c26360bac29).
4. Try-with-resources closes only the Java-owned PFD on early failure/cancellation; after detach it cannot close the consumed/recycled raw fd. Never add a `close(rawFd)` in an exception handler.

The old `start(int, ...)` overloads remain unchanged for compatibility; Routeveil must use the PFD overload. `init()` must have succeeded before use. The pinned native library must match the Java JNI signatures; the archive verification protects this pairing. As with upstream, catastrophic VM/process termination is outside Java resource guarantees.

`requestNativeStop()` calls only `OnionMasqJni.closeProxy()`, without the PT lock, counter resets or upstream one-second sleep. It does not join the engine. Cancellation can race the final Java check and native command-sender creation: the service must set its per-run cancellation flag, repeatedly request native stop while bounded-joining the engine, and perform ordinary `OnionMasq.stop()` PT cleanup **only after the engine thread actually exits**. Do not claim disconnected, close the retained guard, unbind protect, or start another engine if that join times out. The caller also owns cleanup of any PT created before pre-JNI cancellation.

Existing native methods, event callbacks, original `stop()` and legacy raw-fd entry points are otherwise unchanged.

## Logging delta and limitation

The controller's log level is reduced from `DEBUG` to `WARN`, and `enableLogging` is now `false` so the controller does not maintain its own `pt_state/ipt.log`. [IPtProxy 5.4.2 explicitly supports ERROR/WARN/INFO/DEBUG](https://raw.githubusercontent.com/tladesignz/IPtProxy/5.4.2/IPtProxy.go/controller.go). This is **not** a claim of no logs: Java callbacks retain upstream logging and the prebuilt Rust engine writes its own Android logcat messages. The pinned Rust source logs bridge lines when it receives them, so device logcat must be treated as sensitive during bridge use. Routeveil never exports native logcat or raw system logs; only its allowlisted diagnostic codes leave the device. No native binary patch is applied here.

## Build integration

- Android Library plugin from root AGP 9.1.0, namespace `org.torproject.onionmasq`, compile SDK 36, min SDK 26, Java 17. The upstream Java 21 build target is not needed by these sources; switch arrows compile under Java 17.
- APIs: `androidx.appcompat:appcompat:1.7.1`, `com.google.code.gson:gson:2.13.1`, `com.netzarchitekten:IPtProxy:5.4.2`.
- Core library desugaring: `com.android.tools:desugar_jdk_libs:2.1.5`, enabled for min-26 APIs such as upstream `String.strip()`. The consuming application must also enable core-library desugaring and use the same dependency; library-only configuration does not desugar the final APK by itself.
- Parent must register `:onionbridge`, add root `com.android.library` plugin, and replace the app's original Onionmasq implementation dependency with `implementation project(':onionbridge')`. Do not retain both Java facade definitions.
- [consumer-rules.pro](<consumer-rules.pro>) preserves JNI-visible class/method/error names if a future consuming release enables R8.

No Gradle build, native execution, VPN/device test or APK installation was run by this module implementer. Parent coordinates the combined build and lifecycle verification.

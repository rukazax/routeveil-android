# Routeveil Probe protocol (v1)

## APK and integration

- Gradle module: `:probe`; application ID / separate Linux UID: `io.github.rukazax.routeveil.probe`.
- Explicit component: `new ComponentName("io.github.rukazax.routeveil.probe", "io.github.rukazax.routeveil.probe.ProbeService")`.
- Android min SDK 26, target/compile SDK 36, Java 17, root AGP 9.1.0. Platform Java/Android APIs only; no dependencies.
- The parent owns `settings.gradle` and must include `:probe`. The main APK must declare `io.github.rukazax.routeveil.permission.PROBE` with `android:protectionLevel="signature"` and request that permission itself. Both APKs must have matching signing certificates. Install the permission-declaring main APK first.
- Probe requests `INTERNET`, `ACCESS_NETWORK_STATE`, and that signature permission. Its exported service is protected by the signature permission. Probe does not declare/redefine the permission.
- Main should declare package visibility for `io.github.rukazax.routeveil.probe` if it queries package availability. Probe declares visibility for the main package.
- Include the probe package in the VPN application's allowed-app routing. Its UID must **not** be excluded alongside the VPN owner. Probe never establishes a VPN and never bypasses one.
- Bind with the exact component and `Context.BIND_AUTO_CREATE`, retain the binding until reply, and always unbind on completion/timeout. There is no started-service API or automatic test on launch.

The exported launcher is informational only, labelled **Routeveil Probe**. Opening it performs no network requests.

## Authentication

Android checks the signature permission before binding. `Service.onBind()` is a lifecycle callback dispatched by the system, so `Binder.getCallingUid()` there is **not** used to identify the host.

For **every Messenger message**, the service uses the framework-stamped `Message.sendingUid`, checks it against the installed UID of the exact main package `io.github.rukazax.routeveil`, verifies `PackageManager.checkSignatures(sendingUid, Process.myUid()) == SIGNATURE_MATCH`, and checks the signature permission for that UID. A shared UID containing multiple packages is rejected, as is the probe's own UID. Other same-signed packages are not authorised. Caller-supplied package/UID fields are never trusted. Unauthorised messages and messages without `replyTo` are silently dropped with no network work.

## Request

Send a `Message` with:

| Field | Type | Value |
| --- | --- | --- |
| `what` | int | `1` |
| `data.request_id` | String | Nonempty opaque ID, at most 128 UTF-16 code units |
| `replyTo` | Messenger | Mandatory reply destination |

The host should use a fresh random ID, not sensitive user data. Extra Bundle fields are ignored. There is no caller-controlled URL, payload, proxy, timeout, size, or destination. Unknown message types or invalid IDs receive `error / bad_request` only if the sender is authorised and supplies a reply messenger; an invalid ID is returned as `""`.

At most **one worker job** is accepted. A concurrent request receives `unavailable / busy` immediately. A timed-out worker retains its slot until it actually exits: an uninterruptible platform DNS operation cannot cause unbounded queued tests. A host must not blindly retry while the service is busy.

## Reply

A single terminal reply attempt per accepted job uses `Message.what = 2` and these Bundle keys:

| Key | Type | Meaning |
| --- | --- | --- |
| `request_id` | String | Echo of the valid request ID |
| `status` | String | `ok`, `not_tor`, `unavailable`, `challenge`, or `error` |
| `ip` | String | Validated IPv4/IPv6 literal from the check endpoint, or `""` if unavailable |
| `elapsed_ms` | long | Monotonic elapsed time for Tor-check HTTP transaction, not whole job time |
| `download_ms` | long | Monotonic elapsed time for speed HTTP transaction |
| `bytes` | long | Actual speed response bytes consumed; exactly 65536 on `ok` |
| `challenge_url` | nullable String | Exactly `https://check.torproject.org/` only for `challenge`, otherwise null |
| `error_code` | String | Fixed, short, non-secret code; `none` on success |

Measurements use `SystemClock.elapsedRealtime()` and include connection/DNS/TLS/headers/body transfer for each respective transaction. They exclude JSON parsing and the other transaction. Unstarted phases have zero values. Failed phases can contain partial measurements; deadline replies may have zero phase time while a blocked phase is unwinding. **Only `ok` is a usable speed measurement.** Do not reinterpret partial bytes/times, `not_tor`, or challenge as success. There is no fabricated speed/latency fallback. IP and results are held only in memory for this job.

Expected status/code pairs:

- `ok`: `none` — Tor confirmed and the full speed body received over an active VPN.
- `not_tor`: `not_tor` — well-formed endpoint JSON explicitly returned boolean false; no download.
- `challenge`: `challenge` — bounded HTML from the Tor-check endpoint contained explicit CAPTCHA/browser-challenge markers; no download.
- `unavailable`: `busy`, `vpn_required`, `vpn_lost`, `deadline`, `timeout`, `io`, `stopped`, `check_http`, `download_http`.
- `error`: `bad_request`, `tls`, `invalid_json`, `check_large`, `check_short`, `encoding`, `download_size`, `download_short`, `network_denied`, `internal`.

Errors never contain exception messages, endpoint response bodies, arbitrary URLs, query strings, or raw HTML. Remote reply failures are discarded without logging or storage. Android process termination may prevent any reply; the host must maintain its own timeout (70 seconds is recommended), validate the ID and `what`, and treat disconnect/timeout as unavailable.

## Network sequence and bounds

1. Require this UID's `ConnectivityManager.getActiveNetwork()` capabilities to include `TRANSPORT_VPN`. Merely having an Internet-capable network is insufficient.
2. GET **only** `https://check.torproject.org/api/ip` through an ordinary default-routed `URL.openConnection()`. Default platform HTTPS certificate and hostname verification remain enabled. Redirects are disabled. Read at most **16 KiB** into memory. Declared oversized bodies are rejected before reading; unknown-length responses that completely fill the cap are conservatively rejected rather than reading a look-ahead byte.
3. Check VPN again. A successful check requires HTTP 200 and `JSONObject` fields `IsTor` with a real Boolean value and `IP` with a validated numeric IP literal. Boolean false produces `not_tor`; invalid/missing/coerced fields produce `error`. No hostname resolution is used to validate IPs.
4. A challenge requires actual HTML with explicit markers such as reCAPTCHA/hCaptcha, `cf-chl-`, Turnstile, CAPTCHA, or human/browser verification. HTTP 403 alone is **not** evidence; redirects are not followed or treated as challenge destinations. Challenge recognition is limited to the check endpoint's bounded body. The helper does not solve challenges or open a browser. The only reply link is the fixed Tor Project root URL.
5. **Only after `IsTor == true`**, GET **only** `https://speed.cloudflare.com/__down?bytes=65536`. Require HTTP 200, identity content encoding and `Content-Length: 65536`, with no `Transfer-Encoding`. Consume exactly **65536 bytes**, rejecting short reads. Unknown/chunked framing is deliberately rejected (`download_size`): requiring an exact HTTP framing length establishes completion without consuming byte 65537. Any speed-endpoint challenge/error is just a download failure, not a Tor-check challenge.
6. Check VPN before, between and after tests, before reads, and immediately before successful completion. A 200 ms monitor also detects VPN loss during blocking I/O and aborts the job. Connections use 15-second connect/read timeouts; a separate monotonic **60-second total job deadline** delivers a failure, interrupts the worker and disconnects the active connection. No result may become successful after that deadline. Platform scheduling/process suspension can delay delivery; the host's timeout remains mandatory.

Both requests send `Accept-Encoding: identity`, `Connection: close`, `Cache-Control: no-store`, and a fixed non-identifying `User-Agent: Routeveil-Probe` (no device/version/user identifiers). Non-identity responses are rejected. No response caches, cookie jars, telemetry, analytics, persistent storage, WebView, custom trust, socket protection, physical-network `Network.openConnection()`, or process/network binding are used. Downloads are discarded as they are read. External diagnostic endpoints necessarily see the connection's exit IP.

Transport checks are observations, **not an OS kill switch**: an instantaneous network transition can race a default-routed socket operation. Enable Android VPN lockdown / block-connections-without-VPN where strict leak prevention is required. A VPN transport alone also does not prove Tor; the check response is mandatory. Platform DNS/socket teardown is best-effort and can outlive a timeout, but its worker slot is not reused until it exits.

## Lifecycle and verification

`onDestroy()` interrupts work, disconnects any active `HttpURLConnection`, and calls `shutdownNow()` on the worker and timer executors. No receivers, jobs, alarms, foreground service, boot startup, or work after launcher opening are registered.

The dependency-free `ProbePolicySelfTest` covers request ID limits, challenge vs bare 403/JSON/plain text, IPv4/IPv6 literal validation, and numerical limits. It is a plain Java `main`, not a JUnit test. After the parent authorises building/testing, compile `ProbePolicy.java` plus `ProbePolicySelfTest.java` into a temporary output directory with Java 17 and run `io.github.rukazax.routeveil.probe.ProbePolicySelfTest`. It does not perform network calls.

Device/instrumentation acceptance should additionally cover: missing/main-mismatched signatures; binder forwarding to an unauthorised UID; missing `replyTo`; busy rejection; no active VPN and probe excluded from VPN; real Tor success; boolean false and malformed JSON; plain 403 versus marker-bearing HTML; redirects; certificate failure; oversized and truncated responses; unknown/chunked or short speed body; VPN drop mid-read; stalled endpoint deadline; unbind/service destruction; and destroyed host reply messenger. Policy tests alone do not prove these integration properties.

No Gradle build, APK installation, external endpoint request, or device test was started by the module implementer; full-build coordination belongs to the parent agent.

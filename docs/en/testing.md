# Routeveil verification plan

**Except for the rows marked below, all checks are PENDING.** The matrix is an acceptance plan; a PASS requires dated evidence.

## First local run, 2026-11-07

Environment: Windows 11, JDK 21.0.12, Gradle 9.3.1, Android SDK 36 / build-tools 36.0.0; POCO X6 5G (garnet, Android 16, SDK 36), Wi-Fi `MY-NET-5g`, Amnezia installed but inactive.

| Step | Result |
|---|---|
| `:app:assembleDebug :probe:assembleDebug :app:testDebugUnitTest :probe:testDebugUnitTest` | PASS, exit 0; app APK 189,861,064 bytes, probe APK 855,645 bytes |
| Native provenance: AAR 51,388,641 bytes, SHA-256 `89c580cc…44087`, `jni/` arm64-v8a, armeabi-v7a, x86, x86_64 | PASS, enforced by the build task |
| Install on POCO | PASS after manually confirming the USB-install prompt; debug signers match, UIDs differ |
| UI, notification and VPN consent | PASS; TUN established, VPN icon shown |
| AUTO: snowflake 1/3, snowflake 2/3, obfs4 3/3 | FAIL: each profile failed to reach `isReadyForTraffic` within 90 s; logcat showed `snowflake … timeout waiting for DataChannel.OnOpen` and `Unable to select a guard relay` |
| Direct Tor (no bridge) | FAIL: `Network is unreachable (os error 101)` |
| Behaviour after failure | PASS: TUN retained, UI reported selected apps blocked; no silent direct egress |
| Probe, Optimize, rotation, DIRECT picker on a live tunnel, YouTube/Instagram/Telegram, CAPTCHA, log export, cellular networks | NOT RUN |

Conclusion: build, install and VPN lifecycle work; end-to-end Tor connectivity was not achieved in this network. Fresh user-supplied obfs4/WebTunnel bridges and a mobile-network test are required. This neither proves nor disproves operation under carrier allowlists.

## Evidence requirements

Record source commit, APK SHA-256 and signer certificate, dependency lock/resolution, ABI, build command and exit status, device/API/OEM, network/operator, selected mode/profile and direct exceptions. Redact bridge secrets, account data, cookies and full browsing history. Explain observations that are unavailable: a bootstrap success is not a leak test or a successful video session.

Build both APKs with the same signing identity. Verify their UIDs differ and the helper is included in Tor routing. Do not assume an installed helper or a displayed VPN icon proves its route. The intended development commands in the [README](<../../README.md>) are not build results.

## Pending acceptance matrix

| ID | Test | Expected acceptance evidence | Status |
|---|---|---|---|
| B01 | Resolve pinned Onionmasq and its transitives | Exact artifact/version/provenance; AAR contains native libraries for intended ABIs; no hidden snapshot substitution | PASS 2026-11-07: AAR SHA-256 verified, four ABIs, upstream `classes.jar` unused |
| B02 | Build main and companion APKs, unit tests | Commands, toolchain versions, exit codes and APK hashes; no fabricated PASS from download success | PASS 2026-11-07: Gradle 9.3.1/JDK 21, exit 0; record APK hashes at release |
| B03 | Install/update main+helper on API 26, 28, 29 and target API 36 | Signature compatibility, distinct UIDs, ABI/native load, foreground-service operation | PARTIAL: API 36 passed; 26/28/29 not tested |
| R01 | Default routing and DIRECT presets | All apps except visible DIRECT exceptions use Tor; YouTube/Instagram are not exceptions; changes persist; an empty set remains empty | PENDING |
| R02 | Bridge-mode self-exclusion | Main UID bypasses TUN; companion does not; main HTTP requests are never reported as Tor measurements | PENDING |
| R03 | Direct Tor explicit mode | Explicit choice only; protected Arti sockets do not loop; separate measured results | PENDING |
| R04 | Android 26–28 UID limitation | UI/docs do not claim per-app circuit isolation or reliable per-app counters; OS app routing independently tested | PENDING |
| R05 | API 29+ attribution | Companion/app flow UID matches events; per-app rotation scope verified | PENDING |
| A01 | AUTO finite selection | Maximum four profiles; shown ordering and truncation; first-ready selection; bounded cancellation/timeouts | PENDING |
| A02 | Invalid, empty and mixed bridge profiles | Rejected before native start; no silent direct fallback; redacted actionable error | PENDING |
| A03 | Built-in and imported transports | obfs4, WebTunnel and Snowflake exercised independently; endpoint failures recorded, not called universal circumvention | PENDING |
| P01 | Missing/mismatched helper | Connection remains possible; active Tor measurement unavailable; no made-up throughput/score | PENDING |
| P02 | Signature boundary | Wrong-signer app cannot command helper/read privileged results; requests/replies validated and correlated | PENDING |
| P03 | Actual helper egress | Tor-check observation matches Tor routing; no helper fallback to direct or DNS leakage; fresh requests and TLS verification | PENDING |
| P04 | Optimize measurement | Separate bootstrap/HTTPS timings and exactly bounded 64 KiB sample; bytes, endpoint, time, status and limits shown; invalid samples excluded | PENDING |
| P05 | Untrusted/stale results | Forged, duplicated, expired and cancelled responses rejected; no arbitrary network-fetch API exposed | PENDING |
| C01 | Real challenge detection | Observe actual challenge; manual-action notification only; 403/429/timeout alone not mislabelled CAPTCHA | PENDING |
| C02 | Manual challenge workflow | User opens same affected site/app; no automated solving/clicking; no assumption of cross-app clearance cookies | PENDING |
| F01 | Profile switch/backend failure | While service lives, selected apps remain behind blocking TUN; external capture shows no transient direct egress | PENDING |
| F02 | Native start failure/fd ownership | No orphan interface or fd leak; blocking state preserved; cancellation safely serialized | PENDING |
| F03 | Strict Private DNS enabled before/during session | BLOCKED with clear reason; system setting untouched; no plaintext DNS fallback | PENDING |
| F04 | IPv4, IPv6, DNS and unsupported UDP/QUIC | No unselected direct fallback; TCP fallback where application supports it; unsupported flows fail explicitly | PENDING |
| F05 | Crash/force-stop/reboot/permission revoke | Actual OS behavior documented; no claim of protection beyond surviving TUN; test with and without OS lockdown | PENDING |
| F06 | Lockdown plus DIRECT exceptions | Excluded-app loss of connectivity explained; no claim that global lockdown preserves direct bypass | PENDING |
| F07 | Wi-Fi/mobile transition, offline/captive portal, battery restrictions | State recovery bounded; selected apps do not leak during active-service transition; failures visible | PENDING |
| N01 | Rotation off and configured intervals | Off by default; enabled interval >=60s; no overlapping/infinite rotations | PENDING |
| N02 | Rotation during long TCP session | Old session behavior recorded; no forced new-IP/identity claim; cookies and logins remain | PENDING |
| S01 | YouTube launch and navigation | Actual app launch, search/feed and thumbnails load over verified Tor path; exact app/network recorded | PENDING |
| S02 | Instagram launch and feed | Actual app launch, feed/thumbnails/media load over verified Tor; no direct exception | PENDING |
| S03 | Authorized account login | Only with explicit user permission and user-controlled credentials/2FA; no unsolicited account actions | PENDING |
| S04 | YouTube video 720p for 5 minutes | Verify requested/actual resolution and full playback interval, startup delay, stalls and route; Tor bootstrap alone is insufficient | PENDING |
| S05 | Instagram video for 5 minutes, 720p where selectable/verifiable | Record actual quality if controlled by app; inability to verify 720p is NOT a 720p PASS; no unsolicited interactions | PENDING |
| S06 | QUIC failure / TCP fallback | Demonstrate app fallback to TCP where possible; if unavailable or video fails, mark failed/unsupported, not passed | PENDING |
| S07 | Service rejects Tor exit | Record refusal distinctly from transport failure; user objective remains unmet; no endless rotation or CAPTCHA automation | PENDING |
| S08 | Other user-requested blocked services | Repeat launch/feed/media/authorized-login criteria per named service and network; no extrapolation to all RF networks | PENDING |
| T01 | Telegram connection | Actual app connects through verified Tor route, not a default DIRECT exception; record reconnect behavior | PENDING |
| T02 | Telegram text/media/video | Send/receive tests only with explicit permission and agreed recipient/content; confirm delivery/download, timing and route; no unsolicited messages | PENDING |
| T03 | Telegram voice/video calls | Separately authorized call test; record UDP/Tor limitation; working messages do not imply working calls | PENDING |
| D01 | Local log size/retention | Two rotating JSONL files, each <=128 KiB; bounded retention and clear action verified under oversized/repeated events | PENDING |
| D02 | Log minimization | Only allowlisted events/codes; no IPs, URLs, bridges, secrets, personal data, raw native logcat or unsanitized exception/user text | PENDING |
| D03 | User-confirmed export | Android chooser only after user action/confirmation; no automatic uploads or logging server | PENDING |
| D04 | Provider boundary | Non-public read-only URI, temporary grant to chosen recipient; reject writes/path traversal/arbitrary file access; cleanup/retention checked | PENDING |
| L01 | Release compliance | Actual APK SBOM, transitive/native notices, licenses, signer and independent branding verified | PENDING |

## Service acceptance and safety

YouTube and Instagram are target use cases, not guaranteed Tor-compatible applications. Test public content first. Do not log into, post, like, follow, message, change settings or otherwise operate a user's account without authorization. Let the user enter credentials and complete 2FA/CAPTCHA. Do not retain account cookies/tokens in evidence.

A refusal by an exit-sensitive service or inadequate video performance is a failed acceptance goal even if Tor connects successfully. A transport switch does not necessarily change the exit, and a circuit refresh does not guarantee a different exit. If Tor cannot meet these targets, a separately designed backend may be needed; it is not implemented or promised by this plan.

Use bounded tests rather than large downloads or repeated circuit churn. Record tests per network/operator/date. A successful test on one Wi-Fi/mobile connection does not prove resistance to strict carrier allowlists or universal RF availability.

## Release gate

Before distributing beyond a clearly labelled experimental audience, resolve failures affecting routing/protection, verify the native supply chain, complete license/SBOM work, inspect diagnostics for secrets, test relevant Android/OEM lifecycle behavior, and publish honest limitations. Documentation-only review is not this gate.

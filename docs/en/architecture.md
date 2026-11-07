# Routeveil experimental architecture

**Status: design/integration contract; APK build, device operation and leak resistance are not verified by this document.**

![Architecture](<../../assets/architecture.svg>)

## 1. Trust boundaries and data flow

```text
Selected application or companion :probe (distinct UID)
  -> Android per-app VpnService routing
  -> retained TUN -> Onionmasq userspace stack -> Arti
  -> optional IPtProxy transport -> reachable bridge -> Tor relays -> HTTPS destination

Direct-app exceptions -> underlying network (outside Tor)
UI/control APK <-> explicit, signature-permission-protected companion protocol
```

The main APK is a Java Android application, minSdk 26, target/compile SDK 36. The backend is pinned to `org.torproject:onionmasq:1.9.1Beta-local-release`; this is beta, not production-stable. No custom relay/VPN server is required, but external infrastructure is essential.

The application supplies policy/UI, lifecycle coordination, bridge validation, routing configuration and status. Onionmasq supplies the userspace network stack and Arti integration; its Android layer starts IPtProxy for supported bridge transports. Do not start a second independent VPN service or redundant IPtProxy controller.

## 2. Transport policy

AUTO builds a finite candidate list from supplied obfs4/WebTunnel/Snowflake profiles and built-in Snowflake/obfs4. At most four profiles are tried per attempt. Candidate ordering/truncation must be visible rather than described as exhaustive: all five categories cannot necessarily fit in four slots. Validate every line, require a single transport type per profile, remove duplicates and distinguish unsupported profiles from network timeouts.

A bridge-required profile must never become direct Tor because parsing yielded no usable bridge. `Direct Tor` is a separate explicit user choice, not AUTO fallback. Built-in bridge data is versioned configuration, not guaranteed reachable infrastructure; manually supplied bridges may be necessary.

The initial Connect action stops at the first ready profile. Optimize is an explicit bounded experiment: record bootstrap separately from companion HTTPS latency and a 64 KiB transfer. Show endpoint, timestamp, failures and sample size. A tiny transfer is an approximate sample, affected by TLS setup, caching, load and exit policies, not a broadband speed benchmark. Never continuously probe/rotate the Tor network searching for a globally fastest path.

## 3. Companion and measurement integrity

`:probe` is a separate installed APK with a distinct UID, signed with the same certificate as the main APK. A signature-permission boundary and explicit components protect commands and results. The helper is always included in Tor routing during tests and must not be offered as a direct exception. Both endpoints must validate requests/results, use request identifiers and bounded time/byte budgets, and reject stale or unexpected responses. Do not expose an arbitrary URL-fetching service to other apps.

In bridge mode the main APK is excluded from the VPN, matching upstream Tor VPN. Arti has socket protection, but the selected stock IPtProxy integration does not provide the necessary per-socket protection for its complete transport path. A normal main-process HTTP request would therefore measure direct connectivity, not Tor. A second process under the same application UID does not fix this.

No companion installed, signature mismatch, helper timeout or an unverified route means **active Tor measurement unavailable**, not a synthetic score. Routing can still operate using bootstrap events. Passive native counters may show transferred traffic, but do not prove measured link capacity. Confirm the helper's actual Tor egress in device tests; installing a helper alone is not evidence.

The helper's observed challenge may trigger a notification asking the user to open the affected site in their own application and solve it manually. A generic HTTP 403/429 or network failure is not automatically a CAPTCHA. Clearance cookies/session state do not transfer automatically between the helper and another application. No solvers, automatic challenge clicks or credential interception.

## 4. Per-app routing and isolation

The user-editable DIRECT set has initial convenience presets:

| Application | Initial package identifier |
|---|---|
| Gosuslugi | `ru.gosuslugi.gosuslugi` |
| Yandex Navigator | `ru.yandex.yandexnavi` |
| Yandex Go / taxi | `ru.yandex.taxi` |
| VK | `com.vkontakte.android` |
| MAX | `ru.oneme.app` |

Installed packages must be checked; identifiers are presets, not verified identities of every vendor edition. Preserve an intentionally empty set. These settings expose normal connectivity for excluded applications. They are neither a carrier whitelist nor a domain/country bypass engine.

Android's allowed and disallowed application lists are mutually exclusive. Configure them before establishing the TUN and rebuild carefully when policy changes. Never interpret an unset allowed list as “allow no apps”: Android's default is all apps. Main-APK exclusion is required for bridges, not automatically for direct Tor. The companion must not inherit that exclusion.

Android 26–28 can use OS per-app routing, but the chosen backend returns an unknown UID where Android's connection-owner API is unavailable. Do not promise per-app circuit isolation or per-app counters on those versions. On API 29+, verify actual UID attribution rather than assuming every flow is attributable. Authentication, device identifiers and application behavior can still link activity.

## 5. Lifecycle and fail-closed scope

Use a serialized state machine, conceptually:

```text
STOPPED -> PREPARING -> BLOCKED_TUN -> BOOTSTRAPPING -> READY
READY -> SWITCHING/BLOCKED_TUN -> BOOTSTRAPPING
any active state -> BLOCKED_ERROR or explicit STOPPED
```

Obtain `VpnService.prepare()` consent, start a foreground service with visible notification, initialize Onionmasq once, and establish protected backend connectivity. Native start is blocking and owns its transferred file descriptor; use worker threads and explicit ownership. Stop cannot wait on the same single-thread worker that is blocked in start. Handle revoke, lifecycle cleanup, cancellation and stale completion events.

While the service lives, policy requires a blocking TUN across profile switches and failures. The native library owns its active TUN descriptor; merely calling stop/restart is not sufficient evidence of continuity. The implementation must coordinate a blocking interface/handover and prove that selected apps do not fall back to the underlying network.

Crash, force-stop, service death or reboot can remove the TUN. Android Always-on/“Block connections without VPN” is an additional user-controlled protection with different semantics: direct exceptions may lose connectivity too. Do not claim a universal/global kill switch together with unrestricted direct bypass.

Strict Private DNS with a configured hostname is incompatible with this backend path. Detect it before connecting and during operation; remain blocked with actionable status. Do not silently change system DNS or fall back to ISP DNS. Capture IPv4/IPv6 and handle DNS internally; do not leak unsupported UDP. General UDP/QUIC/VoIP support is outside the contract.

## 6. Rotation

Automatic rotation is disabled by default. If enabled, the interval is at least 60 seconds, with no overlapping requests. `OnionMasq.refreshCircuits()` and `refreshCircuitsForApp(uid)` retire circuits for **new connections**. They are not C Tor control-port `SIGNAL NEWNYM` and must not be described as guaranteed identity/IP changes. Old TCP sessions continue; cookies/logins remain. Changing bridges needs a controlled backend/TUN transition, not just a circuit refresh.

## 7. Local diagnostics and user-controlled sharing

The diagnostic contract is two rotating JSONL files of at most 128 KiB each, with bounded retention and an explicit Clear action. Only allowlisted event names and codes may be recorded. Do not store IP addresses, URLs, bridge lines, secrets, personal data, HTTP bodies or raw native logcat. Do not add unsanitized exception text or user-provided strings to structured events. File rotation must respect the size limit even for malformed/oversized events.

Text export is user-initiated and confirmed through the Android share chooser, using a non-public read-only ContentProvider with a temporary grant to the selected recipient. No automatic uploads and no developer-operated logging server. The user may select an analysis chat; this is a deliberate external disclosure of the exported diagnostic data, not a background service. Validate access mode, URI scope, lifecycle and cleanup; do not expose arbitrary private files. These are implementation/acceptance requirements, not a security-test PASS.

Telegram connection, authorized message/media/video transfer, and separate voice/video-call tests are acceptance targets. Never send real test messages without permission. General UDP is unsupported by the Tor baseline, so voice/video calls are not promised even if text and media work. YouTube/Instagram/Telegram must not be default DIRECT exceptions.

## 8. Limits and provenance

Transport obfuscation can help against some filtering, but cannot grant access to a blocked IP/domain in a strict carrier allowlist. Access still requires reachable bridges and transport services. No Russia-operator reachability measurements or universal bypass claims are made. HTTPS remains necessary to protect application content beyond the exit relay.

Original Routeveil branding and diagram are independent of Tor Project. See [license notices](<../../THIRD_PARTY_NOTICES.md>) and [pending verification matrix](<testing.md>).

Primary references:

- [Pinned Onionmasq source](https://gitlab.torproject.org/tpo/core/onionmasq/-/tree/086d05145f5aa711ac300d04edc19c26360bac29)
- [Upstream Tor VPN service, including bridge-mode self-exclusion](https://gitlab.torproject.org/api/v4/projects/tpo%2Fapplications%2Fvpn/repository/files/app%2Fsrc%2Fmain%2Fjava%2Forg%2Ftorproject%2Fvpn%2Fvpn%2FTorVpnService.kt/raw?ref=1.9.1Beta)
- [IPtProxy 5.4.2 controller](https://raw.githubusercontent.com/tladesignz/IPtProxy/5.4.2/IPtProxy.go/controller.go)
- [Android VPN guide](https://developer.android.com/develop/connectivity/vpn?hl=en)
- [Tor VPN beta warning](https://support.torproject.org/tor-vpn/)
- [Android lockdown limitation](https://support.torproject.org/tor-vpn/features/kill-switch/)
- [Tor VPN threat model](https://support.torproject.org/tor-vpn/security/threat-model/)

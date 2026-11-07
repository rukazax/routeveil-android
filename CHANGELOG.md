# Changelog / История изменений

## Unreleased — 0.1.0-experimental

**Local debug builds and one partial device run are recorded below. No signed release, published APK or service acceptance (YouTube/Instagram/Telegram) is asserted. / Ниже зафиксированы локальные debug-сборки и один частичный прогон на телефоне. Подписанный выпуск, опубликованный APK и приёмка сервисов не подтверждаются.**

### First build and partial device run — 2026-11-07

- Both APKs built with Gradle 9.3.1/JDK 21 and installed on POCO X6 5G (Android 16).
- UI, notification and VPN consents work; the VPN service establishes a TUN and shows the system VPN icon.
- AUTO profiles (`snowflake` ×2, built-in `obfs4`) each failed to reach `isReadyForTraffic` within 90 s; direct Tor returned `Network is unreachable`.
- On total failure the app retained the TUN and reported selected apps blocked; no silent direct fallback.
- Probe/Optimize, services, CAPTCHA and log export were not exercised. See [RU testing](<docs/ru/testing.md>) and [EN testing](<docs/en/testing.md>).

### Documentation / Документация

- Added bilingual project overview, architecture and pending acceptance matrices.
- Documented the pinned Onionmasq beta dependency, license provenance and outstanding SBOM work.
- Added an original architecture diagram without Tor branding.
- Added YouTube/Instagram launch, authorized login, feed/thumbnails, video and QUIC-to-TCP acceptance criteria; all remain PENDING.
- Added Telegram connection/text/media/video and separate voice/video-call acceptance criteria, requiring permission before real messages/calls.
- Specified bounded local JSONL diagnostics and user-confirmed, read-only Android sharing; privacy/provider tests remain PENDING.
- Добавлены двуязычное описание, архитектура, матрицы ожидающих испытаний и собственная схема.
- Описаны beta-зависимость Onionmasq, происхождение лицензий и незавершённый SBOM audit.
- Добавлены критерии YouTube/Instagram: запуск, разрешённый login, лента/миниатюры, видео и TCP fallback; все PENDING.

### Experimental design contract / Проектный контракт

- Android API 26+ Java shell; pinned `org.torproject:onionmasq:1.9.1Beta-local-release`.
- AUTO tries at most four bridge profiles, selecting the first ready candidate; no silent direct-Tor fallback.
- Separate same-signer companion APK/UID for bounded Tor-check and 64 KiB HTTPS measurements; main-APK requests are not mislabelled as Tor measurements.
- Editable DIRECT presets, visible routing policy, manual-only CAPTCHA, rotation off by default or >=60 seconds.
- Retained blocking-TUN requirement during live-service transitions; strict Private DNS fail-blocked and crash/lockdown limitations.
- No universal RF allowlist circumvention, arbitrary UDP/QUIC support, guaranteed exit-IP change or production-safety claim.

These describe intended behavior under implementation and verification, not a tested feature list. / Это требования к реализации и проверке, а не список испытанных возможностей.

### Publication plan / План публикации

Separate repository `rukazax/routeveil-android`, branch `feature/adaptive-tor`; creation/publication not verified here. / Создание и публикация отдельно выбранного репозитория здесь не подтверждены.

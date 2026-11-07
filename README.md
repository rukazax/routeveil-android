# Routeveil

**Experimental Android Tor-routing prototype · Экспериментальный Android-клиент маршрутизации через Tor**

> **Not production-ready. The APKs build and start on one device, but no transport completed bootstrap in the first Wi-Fi test; YouTube, Instagram and Telegram are NOT verified. Do not rely on this prototype for sensitive activity.**
>
> **Не готов к промышленному использованию. APK собираются и запускаются на одном устройстве, но при первом тесте по Wi-Fi ни один транспорт не завершил bootstrap; работа YouTube, Instagram и Telegram НЕ подтверждена. Не используйте прототип для чувствительных задач.**

![Routeveil architecture / архитектура](<assets/architecture.svg>)

## Русский

Routeveil — независимая компактная Java-оболочка для Android 8.0+ (API 26+) вокруг закреплённого `org.torproject:onionmasq:1.9.1Beta-local-release`. Собственный VPN-сервер не нужен: используются публичная сеть Tor и сторонние мосты/транспортная инфраструктура. Это **не означает отсутствие серверов** или гарантированный обход блокировок.

Это описание проектного контракта экспериментальной версии и результатов первого частичного прогона. Остальные проверки отмечены как PENDING в [плане испытаний](<docs/ru/testing.md>).

### Предусмотренное поведение

- **AUTO:** до четырёх подготовленных профилей из пользовательских obfs4/WebTunnel/Snowflake и встроенных Snowflake/obfs4; подключение к первому готовому. Неправильный bridge-профиль — ошибка, а не скрытый переход на direct Tor.
- **Оптимизировать:** отдельное ограниченное сравнение bootstrap и HTTPS/передачи 64 КиБ через companion APK `:probe`. Это оценка конкретного измерения, не обещание самого быстрого маршрута. Бесконечного перебора цепочек нет.
- **Отдельный companion APK:** свой Android UID и подпись того же разработчика; взаимодействие защищено signature-permission. Без companion туннель работает, но честное активное измерение Tor-скорости недоступно. Второй процесс основного APK не заменяет отдельный UID.
- **Прямые приложения:** редактируемые начальные исключения — Госуслуги, Яндекс Навигатор, Яндекс Go/такси, VK, MAX. Это локальные предпочтения маршрутизации, **не белый список оператора РФ**. Исключённые приложения раскрывают обычный сетевой адрес.
- **YouTube, Instagram и другие заблокированные сервисы:** целевые сценарии приёмки включают запуск, вход только с разрешения пользователя, ленту, миниатюры и видео 720p в течение 5 минут. По умолчанию через Tor идут все приложения, кроме видимых DIRECT-исключений; YouTube/Instagram в исключения не входят. Это критерий, **не PASS**. При отказе сайта обслуживать Tor-exit или недостаточной скорости Tor задача не решена; может понадобиться дополнительный backend.
- **Telegram:** целевые проверки — подключение, текстовые сообщения и media/video. Тесты не отправляют реальные сообщения без разрешения. Voice/video calls проверяются отдельно и не гарантируются из-за ограничений UDP/Tor.
- **Локальные логи:** проектный формат — ограниченные `2 × 128 КиБ` JSONL-файлы, только разрешённые события/коды, без IP, URL, bridge-строк, секретов, персональных данных и сырого native logcat. Очистка вручную; экспорт текста только по действию пользователя через read-only ContentProvider с временным доступом и Android chooser. Автозагрузки/своего log-сервера нет; передачу в чат анализа выбирает пользователь.
- **Ротация:** выключена по умолчанию; включаемый интервал — не менее 60 секунд. Новые цепочки предназначены для новых соединений; существующие сессии, cookies и логины не очищаются, другой выходной IP не гарантирован.
- **CAPTCHA:** только вручную в приложении/на сайте пользователя. Уведомление допустимо при реально наблюдаемом helper-запросом challenge. Верификация challenge не переносится автоматически в другой браузер/приложение.

### Существенные ограничения

- Tor VPN/Onionmasq — beta-компоненты. Routeveil не является продуктом Tor Project, не использует его логотип и не подтверждён им.
- Обфускация не делает произвольный адрес разрешённым при операторском allowlist/default-deny. Нужен доступный вход Tor/bridge и необходимые транспортные сервисы. **Универсальный обход не обещается.**
- Обычный Tor переносит TCP. Произвольный UDP, QUIC, голосовые звонки и игры не гарантируются; неподдерживаемый трафик нельзя незаметно выпускать напрямую.
- Strict Private DNS с заданным hostname несовместим с выбранным backend: подключение должно блокироваться с объяснением, без изменения системной настройки.
- Блокирующий TUN должен сохраняться при переключениях/ошибках, пока VPN-service жив. Это не защита от всех последствий crash/force-stop/reboot. Android lockdown может блокировать и прямые исключения; обещания глобального kill switch нет.
- Надёжное определение UID для circuit isolation в выбранном Android API начинается с API 29. На Android 8–9 системная per-app маршрутизация и per-app Tor circuit isolation — разные гарантии.

[Архитектура RU](<docs/ru/architecture.md>) · [Проверки RU](<docs/ru/testing.md>) · [Первичное исследование](<docs/ru/research.md>)

## English

Routeveil is an independent, compact Java shell for Android 8.0+ (API 26+), using pinned `org.torproject:onionmasq:1.9.1Beta-local-release`. It needs no developer-operated VPN server, but still depends on Tor relays, bridges and transport infrastructure.

The following is the experimental design contract plus the actual first-run status; unlisted capabilities remain **unverified**:

- AUTO tries at most four prepared user-supplied/built-in bridge profiles and accepts the first ready one. No silent direct-Tor fallback.
- An explicit Optimize action compares bootstrap and a bounded HTTPS/64 KiB transfer through a separate `:probe` companion APK.
- The companion uses a distinct UID and same-signer signature permission. Without it, routing remains available but trustworthy active Tor throughput measurement does not.
- Editable direct-app presets cover Gosuslugi, Yandex Navigator, Yandex Go/taxi, VK and MAX. These are local bypass preferences, not a Russian carrier whitelist.
- YouTube, Instagram and other blocked services are acceptance targets: launch, authorized login, feed, thumbnails and five minutes of 720p video. All apps route through Tor by default except visible DIRECT exceptions; YouTube/Instagram are not exceptions. This is **not a PASS claim**. Exit refusal or inadequate video performance is a failed target and may require an additional backend.
- Telegram connection and text/media/video are acceptance targets; sending real test messages requires permission. Voice/video calls remain separate pending tests with UDP/Tor limitations.
- Local diagnostics: two bounded 128 KiB JSONL files, allowlisted events/codes only, no IPs/URLs/bridge lines/secrets/personal data/raw native logcat. Clear locally; export text only after user action/confirmation through a non-public read-only ContentProvider, temporary access and Android chooser. No automatic upload or logging server; sharing with an analysis chat is the user's choice.
- Rotation is off by default, with a minimum enabled interval of 60 seconds. It affects new connections, does not clear application identity, and does not guarantee a new exit IP.
- CAPTCHA is manual only, in the user's application/site. Notify only after an actually observed challenge; cookies and challenge clearance are not automatically shared with the helper.
- Preserve a blocking TUN across transitions/errors while the service remains alive. Crash/reboot protection is a separate OS concern. Strict Private DNS is unsupported and must fail blocked. OS lockdown may prevent bypass apps from connecting.
- General UDP/QUIC support, universal censorship circumvention and Android 26–28 per-app Tor circuit isolation are **not promised**.

[Architecture EN](<docs/en/architecture.md>) · [Testing EN](<docs/en/testing.md>)

## Development / Разработка

Current project target: Android SDK 36, minSdk 26, Java application, experimental version `0.1.0-experimental`. A separate `:probe` Android application module is part of the integration contract. Native backend, ABI contents and transitive dependencies require verification; a successful dependency download is not a successful build or leak test.

The pinned Onionmasq artifact is supplied through the [official Tor GitLab Maven registry](https://gitlab.torproject.org/api/v4/projects/1192/packages/maven), not assumed to exist on Maven Central. See [third-party notices](<THIRD_PARTY_NOTICES.md>) for artifact provenance and limitations.

After both modules and the toolchain are available, intended local checks are:

```text
gradle :app:assembleDebug :probe:assembleDebug
gradle :app:testDebugUnitTest :probe:testDebugUnitTest
```

The four commands above were executed successfully on 2026-11-07 with Gradle 9.3.1 and JDK 21; see the status section and the acceptance matrix for what did **not** work. Use the implementation's actual Gradle/JDK configuration; this document does not assert a Gradle wrapper is shipped. Device installation, publication, signing and release verification are separate steps. Both APKs must use the same signing identity for the signature-protected companion protocol.

## Project status / Статус

Remote: [`rukazax/routeveil-android`](https://github.com/rukazax/routeveil-android), branch `feature/adaptive-tor`. This is a source branch, not a signed release; there is no F-Droid/Play badge or download claim.

First local run, 2026-11-07 (POCO X6 5G, Android 16, Wi-Fi `MY-NET-5g`):

- PASS — `:app:assembleDebug`, `:probe:assembleDebug`, JVM unit tests; both APKs installed with the same debug certificate and distinct UIDs.
- PASS — the UI opens in Russian, VPN consent and notification consent work, the VPN service establishes a TUN and the system VPN icon appears.
- FAIL — none of the three AUTO profiles (`snowflake`, second `snowflake`, built-in `obfs4`) reached `isReadyForTraffic` within 90 seconds. Logs showed a usable directory but repeated `snowflake ... timeout waiting for DataChannel.OnOpen` and `Unable to select a guard relay`. Direct Tor additionally reported `Network is unreachable`.
- PASS (safety behaviour) — after all profiles failed, the app kept the TUN and reported that selected apps were blocked; it did not silently fall back to direct networking.
- NOT RUN — companion Tor check/64 KiB speed probe, rotation, DIRECT app picker on a live tunnel, YouTube/Instagram/Telegram, CAPTCHA challenge, log export, cellular networks, whitelist-restricted operators.

The immediate blocker is transport availability, not an unproven UI. Fresh user-supplied obfs4/WebTunnel bridges and a mobile-network test are required before any service claim. See the detailed matrix in [RU testing](<docs/ru/testing.md>) / [EN testing](<docs/en/testing.md>).

Original Routeveil code and documentation: [BSD-3-Clause](<LICENSE>), © 2026 rukazax and contributors. Dependencies keep their own licenses; a complete APK SBOM/license audit is pending. [Changelog](<CHANGELOG.md>) · [Third-party notices](<THIRD_PARTY_NOTICES.md>).

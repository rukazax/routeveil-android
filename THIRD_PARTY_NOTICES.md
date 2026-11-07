# Third-party notices / Уведомления о сторонних компонентах

## Scope / Область действия

Original Routeveil code, documentation and the original diagram use [BSD-3-Clause](<LICENSE>), copyright © 2026 rukazax and contributors. That license **does not relicense third-party code, bundled binaries, fonts, assets or notices**. Any copied/adapted upstream material must retain its original attribution and applicable terms.

Собственная лицензия Routeveil не заменяет лицензии зависимостей. **Полный SBOM и проверка лицензионного состава фактических APK пока не выполнены.** Эта таблица — исходный реестр происхождения, не заявление о полном compliance. Ссылки не заменяют включение обязательных текстов/уведомлений в распространяемые APK или сопроводительные материалы.

## Pinned backend / Закреплённый backend

| Component | Version / origin | License / action |
|---|---|---|
| Onionmasq Android | `org.torproject:onionmasq:1.9.1Beta-local-release`; source commit `086d05145f5aa711ac300d04edc19c26360bac29`, tag `vpn-1.9.1Beta` | MIT **OR** Apache-2.0 for Onionmasq itself; retain upstream notices and audit bundled native dependencies |
| IPtProxy | `com.netzarchitekten:IPtProxy:5.4.2`, declared by the pinned Android library | MIT for wrapper; independently audit bundled Go transport/runtime dependencies |
| AndroidX AppCompat | `androidx.appcompat:appcompat:1.7.1`, declared by the pinned library | Apache-2.0; resolve and inventory transitives |
| Gson | `com.google.code.gson:gson:2.13.1`, declared by the pinned library | Apache-2.0; retain applicable notices |
| Arti and Rust dependencies | Embedded in Onionmasq native libraries; exact binary dependency inventory pending | Arti principally MIT OR Apache-2.0; optional `equix`/`hashx` can carry LGPL-3.0. Determine actual enabled features and obligations before release |
| Go transports / Lyrebird / Snowflake / WebTunnel and dependencies | Embedded through IPtProxy; complete exact-version inventory pending | Separate upstream licenses apply. Do not infer them from the wrapper's MIT license |
| Other Android/Kotlin/native build/runtime components | Resolved APK contents not yet inventoried | SBOM, licenses and required NOTICE texts pending |

The GitLab registry lists the pinned AAR as 51,388,641 bytes and SHA-1 `4f365e1c0f88542dc3f41c64c612fe14a77a978e`. This is **registry metadata, not an independently verified download checksum**. Obtain/check a stronger checksum and archive the artifact's provenance during release verification. A prebuilt dependency does not remove native supply-chain or licensing obligations.

Official provenance:

- [Pinned package files and hashes](https://gitlab.torproject.org/api/v4/projects/1192/packages/1463/package_files?per_page=30)
- [Pinned AAR download](https://gitlab.torproject.org/api/v4/projects/1192/packages/maven/org/torproject/onionmasq/1.9.1Beta-local-release/onionmasq-1.9.1Beta-local-release.aar)
- [Pinned POM](https://gitlab.torproject.org/api/v4/projects/1192/packages/maven/org/torproject/onionmasq/1.9.1Beta-local-release/onionmasq-1.9.1Beta-local-release.pom)
- [Pinned Android library dependency declarations](https://gitlab.torproject.org/api/v4/projects/1192/repository/files/android%2FOnionmasqAndroid%2Fonionmasq%2Fbuild.gradle/raw?ref=086d05145f5aa711ac300d04edc19c26360bac29)
- [Onionmasq MIT license](https://gitlab.torproject.org/api/v4/projects/1192/repository/files/LICENSE-MIT/raw?ref=086d05145f5aa711ac300d04edc19c26360bac29)
- [Onionmasq Apache license](https://gitlab.torproject.org/api/v4/projects/1192/repository/files/LICENSE-APACHE/raw?ref=086d05145f5aa711ac300d04edc19c26360bac29)
- [IPtProxy 5.4.2 license](https://raw.githubusercontent.com/tladesignz/IPtProxy/5.4.2/LICENSE)
- [Arti license caveat](https://gitlab.torproject.org/api/v4/projects/tpo%2Fcore%2Farti/repository/files/README.md/raw?ref=main)
- [Gson project/license](https://github.com/google/gson)
- [AndroidX source/license](https://android.googlesource.com/platform/frameworks/support/)

A live Arti README describes general licensing, not proof of the exact bundled version/features. The full corresponding native dependency audit remains required.

## Referenced upstream applications / Использованные ориентиры

[Official Tor VPN](https://gitlab.torproject.org/tpo/applications/vpn) and [Orbot](https://github.com/guardianproject/orbot-android) are BSD-3-Clause projects referenced during research. Routeveil is an independent shell, not an assertion that either complete application is bundled. If their code/assets/default bridge configuration are reused, record the actual copied source, commit and retained notices. References alone do not create an endorsement.

Default bridge data may derive from official Tor VPN/Tor Expert Bundle configuration. Preserve source/version information, review its applicable terms, and never advertise a bundled bridge as private or guaranteed reachable. Do not publish user-imported bridge secrets in source control or diagnostics.

The aggregate Tor VPN notices and IPtProxy's own LICENSE have differed in their attribution of IPtProxy's license. Use the **actual pinned component license**, not an unverified copied aggregate table. This is a reason for an artifact-level audit, not a claim that all downstream terms have been resolved.

## Release checklist / Перед распространением

- [ ] Resolve the actual Gradle graph and inventory all APK/AAR/native/Go/Rust contents.
- [ ] Preserve all applicable copyright, license, NOTICE and attribution texts in distributed materials.
- [ ] Check Arti optional LGPL components and any static-linking/relinking/source obligations if present.
- [ ] Record pinned hashes, source correspondence and security-update policy.
- [ ] Review copied code/assets/default bridge configuration separately from dependencies.
- [ ] Generate and inspect a complete SBOM for both main and companion APKs.
- [ ] Verify the final packages contain the required notices; do not treat this checklist as completed.

## Trademark / Товарные знаки

Routeveil is not affiliated with, endorsed by or an official product of the Tor Project or Guardian Project. Tor and other product names identify external technologies or compatibility targets. No Tor logo is used in the original Routeveil diagram. Open-source licenses do not grant trademark rights. See the [Tor trademark policy](https://www.torproject.org/about/trademark/).

Routeveil — независимый эксперимент. Названия YouTube, Instagram, Госуслуги, Яндекс, VK и MAX обозначают сценарии совместимости/пользовательские исключения, не партнёрство или одобрение.

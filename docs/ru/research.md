# Источники и границы реализации

Дата: 2026-11-07. Статус: исследование и экспериментальная разработка, не проверенный обход сетей РФ.

## Видео

[Ролик Alex WysiWyg](https://www.youtube.com/watch?v=balSiD6Xd7Y), 611 секунд: «Автоматическая смена IP без VPN — Анонимно и Бесплатно | Новый Метод».
Получены название, полное описание и ссылки через ytInitialPlayerResponse. Субтитры: endpoint возвращает HTTP 200 с пустым телом; просмотр/полная расшифровка видео НЕ выполнены.
Прочитаны все три технических приложения:
- [Rotator](https://pastebin.com/raw/hHnYvcRL): Windows Python/Tk GUI, Tor ControlPort + NEWNYM, системный HTTP proxy Clash, таймер 3–600 сек, запрос внешнего IP к api.ipify.org.
- [torrc](https://pastebin.com/raw/zimidpL5): SocksPort 9050, ControlPort 9061, незаполненный HashedControlPassword.
- [Clash](https://pastebin.com/raw/fNHWyT8Y): глобальный маршрут через loopback SOCKS Tor.

Исходный код не исполнялся и не включается в приложение: лицензия фрагментов не указана. Переносится идея, а не Windows-реализация. В этих материалах нет автоматического решения CAPTCHA, подбора скорости, мостов и обхода белых списков операторов. Подмена системного прокси не эквивалентна Android VpnService.

## Выбранный экспериментальный backend

[Onionmasq](https://gitlab.torproject.org/tpo/core/onionmasq), Android TUN → userspace TCP/DNS → Arti/Tor, публичные реле и мосты. Собственный VPS не нужен.
[README](https://gitlab.torproject.org/api/v4/projects/tpo%2Fcore%2Fonionmasq/repository/files/README.md/raw?ref=main).
Планируемая закреплённая зависимость: `org.torproject:onionmasq:1.9.1Beta-local-release`, публичный registry проекта 1192; pipeline success, source commit `086d05145f5aa711ac300d04edc19c26360bac29`. Наличие native ABI и работоспособность APK требуют отдельной проверки. Central metadata 07.11.2026 возвращает 404, несмотря на README. Пакет 1.10.0Beta имеет failed pipeline и подозрительно малый AAR, поэтому автоматически не выбран.

[Официальный Tor VPN](https://support.torproject.org/tor-vpn/) имеет статус beta, возможны утечки. Собственная оболочка не делает backend прошедшим аудит. UDP/QUIC/звонки не обещаются: upstream UDP/TURN experimental и отключены в стандартных сборках.

## Обязательные ограничения UX

- [NEWNYM](https://spec.torproject.org/control-spec/commands.html#signal) / обновление цепочек касается новых потоков; не гарантирует другой IP, не очищает cookies и аккаунты. Не сбрасывать guards ради скорости.
- «Быстрее» означает результат ограниченного теста доступных транспортов в этой сети, а не глобально самый быстрый exit. Не делать массовый перебор выходных узлов.
- При сетевом default-deny нужен хотя бы один реально доступный bridge/transport endpoint; случайный SNI или название разрешённого сайта не создаёт такой endpoint.
- Локальный список DIRECT приложений — не официальный и не автоматически известный белый список оператора. Показывать каждое исключение пользователю.
- [Android lockdown](https://support.torproject.org/tor-vpn/features/kill-switch/) блокирует и исключённые приложения: для DIRECT системный «Блокировать без VPN» должен быть выключен. Один VpnService на профиль.
- CAPTCHA показывать только при реально обнаруженной проверке/ручном обращении пользователя. Внешние HTTPS-потоки не расшифровываются, поэтому CAPTCHA чужого приложения автоматически не видна. CAPTCHA не обязательна для смены IP; мосты или сайт могут её требовать отдельно.
- Приложение не связано с Tor Project и не использует официальный бренд/логотип.

## Дополнительные источники

- [Bridges](https://support.torproject.org/tor-vpn/circumvention/using-bridges/)
- [Per-app routing](https://support.torproject.org/tor-vpn/features/config-apps/)
- [Threat model](https://support.torproject.org/tor-vpn/security/threat-model/)
- [Android VpnService source](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/net/VpnService.java)
- [Orbot source](https://github.com/guardianproject/orbot-android)
- [Tor trademark](https://www.torproject.org/about/trademark/)

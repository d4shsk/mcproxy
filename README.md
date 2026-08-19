# Minecraft TCP Relay

TCP/UDP-релей для Minecraft: Java-игроки заходят через промежуточный хост, а на
основном Paper/Purpur-сервере плагины видят **реальный IP игрока**, а не общий IP
релея. Simple Voice Chat при этом работает у обеих групп игроков.

Типичный сценарий: основной сервер стоит за границей, российские игроки идут через
российский proxy-хост, иностранные — напрямую. Все оказываются на одном сервере, с
одинаковыми UUID и скинами, и слышат друг друга в одном голосовом чате.

```text
Игрок через proxy                          Игрок напрямую
   │ TCP  RELAY_PUBLIC_IP:28939          │ TCP  BACKEND_IP:26289
   │ UDP  RELAY_PUBLIC_IP:25794          │ UDP  BACKEND_IP:26289
   ▼                                     │
┌─────────────────────────────┐          │
│  minecraft-tcp-relay.jar    │          │
│  подписывает реальный IP    │          │
│  (HMAC-SHA256)              │          │
└──────────────┬──────────────┘          │
               │ TCP+UDP на backend      │
               ▼                         ▼
      ┌──────────────────────────────────────┐
      │  Paper / Purpur + Simple Voice Chat  │
      │  minecraft-relay-backend.jar         │
      │  подставляет реальный IP до login    │
      └──────────────────────────────────────┘
```

> [!IMPORTANT]
> В примерах `RELAY_PUBLIC_IP` — публичный IP proxy-хоста, `BACKEND_IP` — IP
> основного сервера, `proxy.example.com` / `mc.example.com` — домены. Номера портов
> приведены как пример. Реальные адреса и `forwarding-secret` в репозитории не
> хранятся, их нужно указать в своих локальных конфигах.

## Совместимость и границы интеграции

> [!WARNING]
> Проект интегрирован **только** с перечисленными ниже плагинами. Никакие другие
> плагины, прокси и системы авторизации пока не поддерживаются: релей передаёт им
> трафик, но реальный IP и подмену голосового хоста они не получат.

| Компонент | Требование | Статус интеграции |
|---|---|---|
| Ядро сервера | **Paper** или **Purpur** | Обязательно. Spigot не поддерживается: там нет раннего `PlayerHandshakeEvent`, поэтому подставить IP до авторизации нельзя |
| [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) | `voicechat-bukkit` 2.6.20 | Поддерживается: голосовой хост выдаётся каждому игроку отдельно через официальный API |
| [AuthMe Reloaded](https://github.com/AuthMe/AuthMeReloaded) | 6.0.0 (Paper) | Поддерживается: получает реальный IP, лимит регистраций считается по адресу игрока |
| [LibertyBans](https://github.com/A248/LibertyBans) | 1.1.3 | Поддерживается: IP-бан банит адрес игрока, а не всех игроков релея |
| Java | 17+ на релее, версия ядра на backend | — |
| Folia | `folia-supported: true` | Заявлено, но отдельно не тестировалось |

Что **не** поддерживается:

- BungeeCord, Velocity, HAProxy/PROXY protocol — их forwarding нужно выключить, релей использует свой механизм;
- Bedrock/Geyser, Minecraft-запросы поверх других протоколов;
- любые другие плагины авторизации, банов и голосового чата;
- защита от DDoS — релей её не даёт.

## Как это работает

| Маршрут | Minecraft TCP | Simple Voice Chat UDP |
|---|---|---|
| Через proxy | `RELAY_PUBLIC_IP:28939` | `RELAY_PUBLIC_IP:25794` |
| Напрямую | `BACKEND_IP:26289` | `BACKEND_IP:26289` |

1. Релей принимает TCP и **только в первый LOGIN handshake** дописывает реальный
   IP клиента, порт, timestamp и подпись HMAC-SHA256. Дальше соединение идёт
   побайтово в обе стороны, сессия и профиль игрока не затрагиваются.
2. STATUS/MOTD handshake не меняется. Если backend недоступен, релей сам отвечает
   на запрос списка серверов резервным MOTD и держит ping/pong.
3. UDP голосового чата проксируется с отдельной сессией на каждый клиентский
   `IP:port`.
4. Backend-плагин на `PlayerHandshakeEvent` (до login-логики Paper) проверяет
   подпись и подставляет реальный IP, поэтому AuthMe и LibertyBans работают с
   настоящим адресом.
5. Голосовой хост выбирается по адресу, который игрок ввёл в клиенте:
   вход через `RELAY_PUBLIC_IP:28939` → voice `RELAY_PUBLIC_IP:25794`,
   вход через `BACKEND_IP:26289` → voice `BACKEND_IP:26289`.

Формат внутренней метки и подписываемой строки описан в [`project.md`](project.md).

## Состав репозитория

| Путь | Назначение |
|---|---|
| `minecraft-tcp-relay.jar` | Релей, запускается на proxy-хосте (`java -jar`) |
| `server.jar` | Та же сборка релея под именем, которое требуют панели |
| `minecraft-relay-backend.jar` | Bukkit-плагин для основного сервера |
| `relay.properties.example` | Шаблон конфига релея |
| `source/Main.java` | Исходник релея, без сторонних зависимостей |
| `backend-plugin/` | Исходник и ресурсы backend-плагина |
| `deploy/proxy/`, `deploy/backend/` | Готовые наборы файлов для загрузки на хосты |
| `tests/RelayIntegrationTest.java` | Интеграционный тест handshake, UDP и MOTD |
| `project.md` | Техническая документация: протокол, архитектура, ограничения |

> [!CAUTION]
> `minecraft-relay-backend.jar`, Simple Voice Chat, AuthMe и LibertyBans — это
> Bukkit-плагины. У них нет `Main-Class`: их нельзя переименовывать в `server.jar`
> и нельзя запускать через `java -jar`.

## Быстрый старт

### Шаг 1. Общий секрет

Секрет генерируется один раз и должен побайтово совпадать на релее и на backend:

```bash
openssl rand -hex 32
```

### Шаг 2. Proxy-хост

```bash
cp relay.properties.example relay.properties
# указать backend-host, порты и forwarding-secret
java -jar minecraft-tcp-relay.jar
```

Если панель требует имя `server.jar` — переименовать **только JAR**. Ожидаемые
строки в консоли:

```text
Minecraft TCP: 0.0.0.0:28939 -> BACKEND_IP:26289
Simple Voice Chat UDP: 0.0.0.0:25794 -> BACKEND_IP:26289
Проброс реального IP для AuthMe/LibertyBans: включён (HMAC)
Резервный MOTD при недоступном backend: включён
```

### Шаг 3. Основной Paper/Purpur-сервер

Положить в `plugins`:

```text
voicechat-bukkit-2.6.20.jar
minecraft-relay-backend.jar
```

Полностью перезапустить сервер (**не** `/reload`). Плагин создаст
`plugins/MinecraftRelayBackend/router.properties` из шаблона со значениями-заглушками —
их нужно заменить своими и перезапустить сервер ещё раз.

В `plugins/voicechat/voicechat-server.properties` задать:

```properties
port=26289
voice_host=
```

`voice_host` оставить **пустым**: плагин выдаёт его каждому игроку индивидуально.
Глобально прописанный адрес proxy отправил бы иностранных игроков на недоступный
им хост.

## Конфигурация

### `relay.properties`

```properties
listen-port=28939
backend-host=BACKEND_IP
backend-port=26289

voice-enabled=true
voice-listen-port=25794
voice-backend-port=26289

forward-client-ip=true
forwarding-secret=CHANGE_ME_TO_A_RANDOM_SECRET_OF_AT_LEAST_32_BYTES

offline-motd-enabled=true
offline-motd-line1=&7Извините, главный сервер не отвечает
offline-motd-line2=&7Он выключен, или у хоста тех работы.

bind-address=0.0.0.0
connect-timeout-ms=10000
max-connections=200
backlog=128
socket-buffer-bytes=262144
tcp-no-delay=true
voice-session-timeout-ms=60000
voice-max-sessions=200
```

| Ключ | По умолчанию | Описание |
|---|---|---|
| `listen-port` | `25565` | Входящий TCP-порт релея |
| `backend-host`, `backend-port` | — | Адрес основного сервера, обязательны |
| `voice-enabled` | авто | Проксирование UDP Simple Voice Chat: без явного значения включается, если задан `voice-listen-port` |
| `voice-listen-port` | — | Входящий UDP-порт релея |
| `voice-backend-host` | `backend-host` | UDP-хост backend, если отличается |
| `voice-backend-port` | `24454` | UDP-порт Simple Voice Chat на backend |
| `forward-client-ip` | `false` | Подпись и передача реального IP |
| `forwarding-secret` | — | Общий секрет, минимум 32 байта |
| `offline-motd-enabled` | `true` | Резервный MOTD при недоступном backend |
| `offline-motd-line1/2` | серый текст | Строки резервного MOTD, поддерживают `&`-цвета |
| `bind-address`, `voice-bind-address` | `0.0.0.0` | Адреса прослушивания |
| `connect-timeout-ms` | `10000` | Таймаут подключения к backend |
| `max-connections` | `200` | Лимит одновременных TCP-соединений |
| `voice-session-timeout-ms` | `60000` | Время жизни неактивной UDP-сессии |
| `voice-max-sessions` | `200` | Лимит UDP-сессий |

Любой ключ можно переопределить переменной окружения (`LISTEN_PORT`,
`BACKEND_HOST`, `FORWARD_CLIENT_IP`, `FORWARDING_SECRET` и так далее) или
`-D`-свойством. `listen-port` при отсутствии значения дополнительно берётся из
`server.properties` (`server-port`) и переменных `SERVER_PORT` / `PORT`, что удобно
на панельных хостингах.

### `plugins/MinecraftRelayBackend/router.properties`

```properties
trusted-relay-ip=RELAY_PUBLIC_IP
forwarding-secret=CHANGE_ME_TO_A_RANDOM_SECRET_OF_AT_LEAST_32_BYTES
signature-max-age-seconds=300
proxy-game-hosts=RELAY_PUBLIC_IP,proxy.example.com
proxy-voice-host=RELAY_PUBLIC_IP:25794
direct-voice-host=BACKEND_IP:26289
```

| Ключ | Описание |
|---|---|
| `trusted-relay-ip` | Единственный источник, от которого принимается forwarding |
| `forwarding-secret` | Тот же секрет, что в `relay.properties` |
| `signature-max-age-seconds` | Срок действия подписи, допустимое расхождение часов |
| `proxy-game-hosts` | Адреса и домены входа через релей, через запятую |
| `proxy-voice-host` | Голосовой хост для игроков, вошедших через релей |
| `direct-voice-host` | Голосовой хост для прямых подключений |

Если игроки заходят по домену, его нужно добавить в `proxy-game-hosts` — иначе для
такого входа выберется direct-маршрут голосового чата.

### Настройки Paper

```properties
online-mode=true
```

Выключить (это не Velocity/Bungee): BungeeCord forwarding, Velocity forwarding,
HAProxy/PROXY protocol. Часы обоих хостов должны быть синхронизированы (обычно этим
занимается NTP хостинга).

### Порты

| Хост | Направление | Порт |
|---|---|---|
| Proxy | входящий | TCP `28939`, UDP `25794` |
| Proxy | исходящий | TCP и UDP к `BACKEND_IP:26289` |
| Backend | входящий | TCP `26289`, UDP `26289` |

Одинаковый номер для Minecraft TCP и голосового UDP допустим: это разные
транспортные протоколы, конфликта нет.

### DNS SRV (вход без указания порта)

SRV-запись действует только для того поддомена, который игрок вводит в клиенте:

| Поле | Значение |
|---|---|
| Имя | `_minecraft._tcp.proxy` |
| Тип | `SRV` |
| Значение | `5 28939 proxy.example.com.` |
| Приоритет | `10` |

Запись `_minecraft._tcp.mc` работает только для `mc.example.com` и не применяется
при вводе `proxy.example.com`.

## Проверка после установки

1. Войти через `RELAY_PUBLIC_IP:28939`.
2. В консоли backend: `Принят реальный IP через relay: ...`.
3. В консоли backend: `VoiceChat для NAME: RELAY_PUBLIC_IP:25794 (proxy)`.
4. Войти напрямую через `BACKEND_IP:26289`.
5. В консоли backend: `VoiceChat ... BACKEND_IP:26289 (direct)`.
6. Выполнить `/voicechat test <ник>`.
7. Проверить IP игрока средствами AuthMe/LibertyBans и тестовым IP-баном.

## Диагностика

| Симптом | Причина и что делать |
|---|---|
| `forwarding-метка пришла не от trusted relay` | Исходящий TCP proxy-хоста уходит с другого публичного IP (NAT). Записать в `trusted-relay-ip` адрес из лога backend и перезапустить сервер |
| `Отклонён IP forwarding` | Не совпал секрет или просрочен timestamp: сверить `forwarding-secret` и синхронизировать часы |
| `Simple Voice Chat API не найден` | В `plugins` нет `voicechat-bukkit` JAR |
| Голосовой чат молчит только у proxy-игроков | Панель proxy-хоста фильтрует UDP или не выдала UDP-порт |
| Игроки видят серый MOTD «главный сервер не отвечает» | Backend недоступен по TCP: войти в игру в этом состоянии нельзя |
| У всех proxy-игроков один IP | Плагин не установлен, не Paper/Purpur, либо `forward-client-ip=false` |

## Команды релея

| Команда | Действие |
|---|---|
| `status` | TCP-подключения, голосовые сессии, счётчики UDP |
| `stop` | Корректная остановка |
| `help` | Список команд |

## Безопасность

Forwarding принимается только при одновременном выполнении трёх условий: source IP
равен `trusted-relay-ip`, подпись HMAC-SHA256 верна, timestamp не старше
`signature-max-age-seconds`. Игрок с прямым доступом к backend не знает секрет и не
может подставить произвольный адрес; пакет с неверной подписью отклоняется.

- `forwarding-secret` держать приватным и одинаковым на обеих сторонах;
- при смене секрета перезапустить и релей, и backend;
- оставить `online-mode=true`;
- не включать Bungee/Velocity forwarding и PROXY protocol.

Локальные конфиги с реальными адресами (`relay.properties`,
`deploy/proxy/relay.properties`) в git не попадают — они перечислены в
`.gitignore`.

## Сборка и тест

Релею не нужны сторонние библиотеки:

```bash
mkdir -p build/relay
javac --release 17 -encoding UTF-8 -d build/relay source/Main.java
jar --create --file minecraft-tcp-relay.jar --main-class relay.Main -C build/relay .
```

Интеграционный тест проверяет подпись реального IP в LOGIN handshake, прозрачность
STATUS/MOTD, передачу TCP после handshake, UDP в обе стороны, резервный MOTD с
ping/pong и корректную остановку:

```bash
javac --release 17 -encoding UTF-8 -d build/tests tests/RelayIntegrationTest.java
java -cp build/tests RelayIntegrationTest minecraft-tcp-relay.jar
```

Ожидаемый вывод: `RelayIntegrationTest: OK`.

Backend-плагин компилируется с Paper API и API Simple Voice Chat, но эти
зависимости в итоговый JAR не встраиваются — их предоставляет сервер.

## Ограничения

- Релей не является DDoS-защитой.
- UDP не заработает, если хостинг выдаёт только TCP или фильтрует исходящий UDP.
- Source IP хостинга после NAT может отличаться от публичного: тогда
  `trusted-relay-ip` нужно взять из лога backend.
- При рассинхронизации часов больше допустимого подпись отклоняется.
- Активная игровая сессия не восстанавливается после обрыва backend.
- Смена `forwarding-secret` требует перезапуска релея и backend.

Подробности протокола и архитектуры — в [`project.md`](project.md).

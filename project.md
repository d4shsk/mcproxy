# Minecraft TCP Relay + Simple Voice Chat + Real IP Forwarding

## Назначение

Проект даёт два независимых маршрута к одному Paper/Purpur-серверу:

| Маршрут | Minecraft TCP | Simple Voice Chat UDP |
|---|---|---|
| Российский proxy | `RELAY_PUBLIC_IP:28939` | `RELAY_PUBLIC_IP:25794` |
| Прямой backend | `BACKEND_IP:26289` | `BACKEND_IP:26289` |

В документации и во всех конфигах репозитория используются заглушки:
`RELAY_PUBLIC_IP` — публичный IP proxy-хоста, `BACKEND_IP` — IP основного сервера,
`proxy.example.com`/`mc.example.com` — домены. Номера портов приведены как пример.
Перед запуском их нужно заменить своими значениями.

Российские игроки используют proxy, иностранные — прямой IP. Игроки обоих
маршрутов оказываются на одном основном сервере, имеют одинаковые UUID/скины и
могут разговаривать в одном Simple Voice Chat.

Дополнительная задача — сохранить исходный IP у proxy-игроков, чтобы:

- AuthMe не считал всех пользователей релея одним IP;
- лимиты регистраций AuthMe работали по реальному адресу;
- IP-баны LibertyBans работали по реальному адресу игрока;
- бан одного proxy-игрока не затрагивал остальных.

## Архитектура

Решение состоит из двух JAR.

### `minecraft-tcp-relay.jar`

Запускается на российском хостинге и выполняет три функции:

1. Передаёт Minecraft TCP на backend.
2. Передаёт Simple Voice Chat UDP на backend, создавая отдельную UDP-сессию для
   каждого клиентского `IP:port`.
3. Только в первом LOGIN handshake добавляет реальный IP и порт клиента,
   timestamp и HMAC-SHA256 подпись.
4. Если TCP backend недоступен, самостоятельно отвечает на STATUS-запрос
   настраиваемым резервным MOTD и поддерживает ping/pong.

При доступном backend STATUS/MOTD handshake не изменяется. После первого LOGIN
handshake соединение снова передаётся побайтово в обе стороны. Релей не завершает
Minecraft-сессию, не авторизует аккаунт и не меняет UUID или профиль.

Формат внутренней метки:

```text
original-host\0MCRELAY1\0client-ip\0client-port\0unix-time\0base64url-hmac
```

Подписывается строка:

```text
original-host\nclient-ip\nclient-port\nunix-time
```

### `minecraft-relay-backend.jar`

Устанавливается в `plugins` основного Paper/Purpur вместе с
`voicechat-bukkit-2.6.20.jar`.

Плагин слушает Paper `PlayerHandshakeEvent` раньше login/pre-login логики:

1. Игнорирует обычные прямые подключения без метки.
2. Проверяет, что соединение с меткой пришло с `trusted-relay-ip`.
3. Проверяет возраст timestamp и HMAC constant-time сравнением.
4. Удаляет внутреннюю метку из virtual hostname.
5. Подставляет реальный IP в адрес Minecraft-соединения до событий AuthMe и
   LibertyBans.

Плагин также регистрируется в API Simple Voice Chat и обрабатывает
`VoiceHostEvent`. По virtual host входа он выдаёт конкретному игроку:

- `RELAY_PUBLIC_IP:25794`, если Minecraft-вход был через proxy;
- `BACKEND_IP:26289`, если Minecraft-вход был прямым.

Это нужно потому, что глобальный `voice_host` Simple Voice Chat одинаков для всех
игроков. Российский IP недоступен части иностранных игроков, поэтому использовать
его глобально нельзя.

## Почему не PROXY protocol

Если включить HAProxy/PROXY protocol на основном Minecraft listener, сервер будет
ожидать PROXY-заголовок и от прямых игроков. Тогда одновременный обычный прямой
вход перестанет работать.

Здесь метка находится в Minecraft LOGIN handshake и распознаётся только backend-
плагином. Поэтому один порт продолжает принимать и подписанный proxy-вход, и
обычный прямой вход.

## Безопасность

IP forwarding доверяется только при одновременной проверке:

- source IP TCP-соединения равен `trusted-relay-ip`;
- HMAC-SHA256 корректен;
- timestamp не старше `signature-max-age-seconds`.

Игрок с прямым доступом к backend не знает секрет и не может самостоятельно
подставить произвольный IP. Пакет с меткой и неверной подписью отклоняется.

Требования:

- хранить `forwarding-secret` приватно;
- использовать одинаковый секрет на relay и backend;
- синхронизировать часы хостов;
- оставить `online-mode=true`;
- не включать Bungee/Velocity forwarding и PROXY protocol.

## Сетевой поток

### Proxy-игрок

```text
Minecraft client
    ├─ TCP -> RELAY_PUBLIC_IP:28939
    │          relay + signed IP
    │          -> BACKEND_IP:26289 TCP
    └─ UDP -> RELAY_PUBLIC_IP:25794
               per-client UDP session
               -> BACKEND_IP:26289 UDP
```

### Прямой игрок

```text
Minecraft client
    ├─ TCP -> BACKEND_IP:26289
    └─ UDP -> BACKEND_IP:26289
```

Одинаковый номер `26289` для TCP Minecraft и UDP VoiceChat допустим: TCP и UDP —
разные транспортные протоколы.

## Структура репозитория

```text
mcproxy/
├── source/Main.java
├── relay.properties.example
├── minecraft-tcp-relay.jar
├── backend-plugin/
│   ├── source/relay/backend/RelayBackendPlugin.java
│   └── resources/
│       ├── plugin.yml
│       └── router.properties
├── minecraft-relay-backend.jar
├── voicechat-bukkit-2.6.20.jar
├── tests/RelayIntegrationTest.java
├── README.md
└── project.md
```

## Конфигурация relay

Файл `relay.properties` (создаётся копированием `relay.properties.example`; сам
`relay.properties` в git не хранится, потому что содержит адреса и секрет):

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

Поддерживаются переменные окружения `FORWARD_CLIENT_IP` и `FORWARDING_SECRET`, а
также уже существующие переменные TCP/UDP настроек.

## Конфигурация backend-плагина

Файл `plugins/MinecraftRelayBackend/router.properties`:

```properties
trusted-relay-ip=RELAY_PUBLIC_IP
forwarding-secret=CHANGE_ME_TO_THE_SAME_SECRET
signature-max-age-seconds=300
proxy-game-hosts=RELAY_PUBLIC_IP,proxy.example.com
proxy-voice-host=RELAY_PUBLIC_IP:25794
direct-voice-host=BACKEND_IP:26289
```

`proxy-game-hosts` — список через запятую. Если игроки позже будут входить через
домен proxy, его нужно дописать сюда, иначе VoiceChat этого входа выберет direct-
маршрут. Для текущей схемы используется только цифровой IP.

## Конфигурация Simple Voice Chat

`plugins/voicechat/voicechat-server.properties`:

```properties
port=26289
voice_host=
```

Backend-плагин задаёт полный voice host динамически. UDP `26289` должен быть
открыт на основном сервере, UDP `25794` — на proxy.

## Требования

### Relay

- Java 17+;
- входящий TCP `28939`;
- входящий UDP `25794`;
- исходящий TCP+UDP к `BACKEND_IP:26289`.

### Backend

- Paper или Purpur;
- Java, соответствующая версии сервера;
- `voicechat-bukkit-2.6.20.jar`;
- `minecraft-relay-backend.jar`;
- входящий TCP+UDP `26289`.

Обычный Spigot не поддерживается backend-плагином, потому что требуемое изменение
IP должно произойти на Paper handshake-событии до AuthMe/LibertyBans.

## Ограничения

- Релей не является DDoS-защитой.
- UDP не заработает, если панель выдаёт только TCP или фильтрует исходящий UDP.
- Source IP российского хостинга после NAT может отличаться от его публичного IP;
  тогда `trusted-relay-ip` нужно заменить фактическим адресом из лога backend.
- При рассинхронизации часов более чем на разрешённый срок подпись отклоняется.
- Активная игровая сессия не восстанавливается после обрыва backend.
- Смена `forwarding-secret` требует перезапуска relay и backend.

## Сборка

Relay:

```bash
mkdir -p build/relay
javac --release 17 -encoding UTF-8 -d build/relay source/Main.java
jar --create --file minecraft-tcp-relay.jar \
  --main-class relay.Main -C build/relay .
```

Backend-плагин компилируется с Paper API и API приложенного Simple Voice Chat, но
эти зависимости не встраиваются в итоговый JAR: их предоставляет основной сервер.

## Проверка

Автоматический интеграционный тест проверяет:

- HMAC-метку в LOGIN handshake;
- исходный IP и timestamp;
- прозрачность STATUS/MOTD handshake;
- передачу TCP после handshake;
- передачу VoiceChat UDP в обе стороны;
- резервный MOTD и ping/pong при закрытом TCP-порту backend;
- корректную остановку relay.

```bash
javac --release 17 -encoding UTF-8 -d build/tests tests/RelayIntegrationTest.java
java -cp build/tests RelayIntegrationTest minecraft-tcp-relay.jar
```

Ожидается `RelayIntegrationTest: OK`.

На рабочем сервере дополнительно проверяются оба маршрута, `/voicechat test`, IP в
AuthMe и IP-бан LibertyBans.

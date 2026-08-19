
package relay;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class Main {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final AtomicInteger ACTIVE = new AtomicInteger();
    private static final AtomicInteger TOTAL = new AtomicInteger();
    private static final AtomicInteger VOICE_ACTIVE = new AtomicInteger();
    private static final AtomicInteger VOICE_TOTAL = new AtomicInteger();
    private static final AtomicLong VOICE_UPSTREAM_PACKETS = new AtomicLong();
    private static final AtomicLong VOICE_DOWNSTREAM_PACKETS = new AtomicLong();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(true);

    private static ServerSocket serverSocket;
    private static ExecutorService executor;
    private static Semaphore connectionLimit;
    private static VoiceRelay voiceRelay;

    private Main() {}

    public static void main(String[] args) {
        try {
            Config config = Config.load();

            connectionLimit = new Semaphore(config.maxConnections);
            executor = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "relay-worker");
                t.setDaemon(true);
                return t;
            });

            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(config.bindAddress, config.listenPort), config.backlog);

            Runtime.getRuntime().addShutdownHook(new Thread(Main::shutdown, "relay-shutdown"));

            if (config.voiceEnabled) {
                voiceRelay = new VoiceRelay(config);
                voiceRelay.start();
            }

            log("Minecraft TCP Relay запущен");
            log("Minecraft TCP: " + config.bindAddress + ":" + config.listenPort
                    + " -> " + config.backendHost + ":" + config.backendPort);
            if (config.voiceEnabled) {
                log("Simple Voice Chat UDP: " + config.voiceBindAddress + ":"
                        + config.voiceListenPort + " -> " + config.voiceBackendHost
                        + ":" + config.voiceBackendPort);
                log("Лимиты: TCP " + config.maxConnections
                        + ", голосовых UDP-сессий " + config.voiceMaxSessions);
            } else {
                log("Simple Voice Chat UDP: выключен");
                log("Лимит TCP-подключений: " + config.maxConnections);
            }
            log("Проброс реального IP для AuthMe/LibertyBans: "
                    + (config.forwardClientIp ? "включён (HMAC)" : "выключен"));
            log("Резервный MOTD при недоступном backend: "
                    + (config.offlineMotdEnabled ? "включён" : "выключен"));
            log("Команды консоли: status, stop, help");

            startConsoleThread();

            while (RUNNING.get()) {
                try {
                    Socket client = serverSocket.accept();

                    if (!connectionLimit.tryAcquire()) {
                        log("Отклонено подключение " + client.getRemoteSocketAddress()
                                + ": достигнут лимит " + config.maxConnections);
                        closeQuietly(client);
                        continue;
                    }

                    configureSocket(client, config);
                    executor.execute(() -> handle(client, config));
                } catch (SocketException e) {
                    if (RUNNING.get()) {
                        log("Ошибка сокета при accept: " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log("Критическая ошибка: " + e.getMessage());
            e.printStackTrace(System.out);
            System.exit(1);
        } finally {
            shutdown();
        }
    }

    private static void handle(Socket client, Config config) {
        int connectionId = TOTAL.incrementAndGet();
        ACTIVE.incrementAndGet();

        String clientAddress = String.valueOf(client.getRemoteSocketAddress());
        Socket backend = new Socket();
        AtomicBoolean closed = new AtomicBoolean(false);

        try {
            backend.connect(
                    new InetSocketAddress(config.backendHost, config.backendPort),
                    config.connectTimeoutMs
            );
            configureSocket(backend, config);

            if (config.forwardClientIp) {
                forwardMinecraftHandshake(client, backend, config);
            }

            log("#" + connectionId + " подключён: " + clientAddress
                    + " -> " + config.backendHost + ":" + config.backendPort);

            Future<?> upstream = executor.submit(() ->
                    pipe(client, backend, clientAddress + " -> backend", closed)
            );

            pipe(backend, client, "backend -> " + clientAddress, closed);

            try {
                upstream.get(2, TimeUnit.SECONDS);
            } catch (TimeoutException ignored) {
                upstream.cancel(true);
            } catch (ExecutionException ignored) {
                // Ошибка уже обработана внутри pipe().
            }
        } catch (IOException e) {
            boolean offlineMotdSent = false;
            if (!backend.isConnected() && config.offlineMotdEnabled) {
                try {
                    offlineMotdSent = respondWithOfflineMotd(client, config);
                } catch (IOException fallbackError) {
                    log("#" + connectionId + " ошибка резервного MOTD для "
                            + clientAddress + ": " + fallbackError.getMessage());
                }
            }
            log("#" + connectionId + " не удалось подключить " + clientAddress
                    + " к backend: " + e.getMessage()
                    + (offlineMotdSent ? " | показан резервный MOTD" : ""));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (closed.compareAndSet(false, true)) {
                closeQuietly(client);
                closeQuietly(backend);
            } else {
                closeQuietly(client);
                closeQuietly(backend);
            }

            ACTIVE.decrementAndGet();
            connectionLimit.release();
            log("#" + connectionId + " отключён: " + clientAddress
                    + " | активно: " + ACTIVE.get());
        }
    }

    private static void pipe(Socket from, Socket to, String direction, AtomicBoolean closed) {
        byte[] buffer = new byte[64 * 1024];

        try {
            InputStream input = new BufferedInputStream(from.getInputStream(), 64 * 1024);
            OutputStream output = new BufferedOutputStream(to.getOutputStream(), 64 * 1024);

            int read;
            while (!Thread.currentThread().isInterrupted()
                    && (read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                output.flush();
            }
        } catch (SocketException e) {
            // Обычное закрытие соединения не считаем ошибкой.
        } catch (IOException e) {
            if (!closed.get()) {
                log("Ошибка передачи [" + direction + "]: " + e.getMessage());
            }
        } finally {
            if (closed.compareAndSet(false, true)) {
                closeQuietly(from);
                closeQuietly(to);
            }
        }
    }

    private static void configureSocket(Socket socket, Config config) throws SocketException {
        socket.setTcpNoDelay(config.tcpNoDelay);
        socket.setKeepAlive(true);
        socket.setReceiveBufferSize(config.socketBufferBytes);
        socket.setSendBufferSize(config.socketBufferBytes);
    }

    /**
     * Отвечает только на современный Minecraft STATUS-запрос, если backend не
     * принял TCP-соединение. LOGIN-запросы не маскируются под работающий сервер.
     */
    private static boolean respondWithOfflineMotd(Socket client, Config config)
            throws IOException {
        client.setSoTimeout(3_000);
        InputStream input = client.getInputStream();
        OutputStream output = client.getOutputStream();

        VarIntPrefix handshakeLength = readVarIntPrefix(input);
        if (handshakeLength == null || handshakeLength.legacyPing
                || handshakeLength.value < 0 || handshakeLength.value > 8_192) {
            return false;
        }

        byte[] handshake = input.readNBytes(handshakeLength.value);
        if (handshake.length != handshakeLength.value) return false;

        int protocolVersion;
        try {
            PacketReader reader = new PacketReader(handshake);
            int packetId = reader.readVarInt();
            protocolVersion = reader.readVarInt();
            reader.readString();
            reader.readUnsignedShort();
            int nextState = reader.readVarInt();
            if (packetId != 0 || nextState != 1 || reader.hasRemaining()) return false;
        } catch (IllegalArgumentException e) {
            return false;
        }

        byte[] statusRequest = readFramedPacket(input, 64);
        try {
            PacketReader requestReader = new PacketReader(statusRequest);
            if (requestReader.readVarInt() != 0 || requestReader.hasRemaining()) return false;
        } catch (IllegalArgumentException e) {
            return false;
        }

        String line1 = stripLeadingGrayCode(config.offlineMotdLine1);
        String line2 = stripLeadingGrayCode(config.offlineMotdLine2);
        String description = line1 + "\n" + line2;
        String json = "{\"version\":{\"name\":\"Backend offline\",\"protocol\":"
                + protocolVersion
                + "},\"players\":{\"max\":0,\"online\":0},"
                + "\"description\":{\"text\":\"" + jsonEscape(description)
                + "\",\"color\":\"gray\"}}";

        ByteArrayOutputStream response = new ByteArrayOutputStream();
        writeVarInt(response, 0);
        writeString(response, json);
        writeFramedPacket(output, response.toByteArray());
        output.flush();

        // Vanilla после STATUS обычно сразу отправляет ping. Эхо даёт корректное
        // измерение задержки и не оставляет запись списка серверов в ожидании.
        client.setSoTimeout(1_000);
        try {
            byte[] ping = readFramedPacket(input, 32);
            if (ping.length == 9 && (ping[0] & 0xFF) == 1) {
                writeFramedPacket(output, ping);
                output.flush();
            }
        } catch (SocketTimeoutException ignored) {
            // Некоторые клиенты закрывают соединение сразу после STATUS response.
        }
        return true;
    }

    private static byte[] readFramedPacket(InputStream input, int maxLength)
            throws IOException {
        VarIntPrefix prefix = readVarIntPrefix(input);
        if (prefix == null || prefix.legacyPing || prefix.value < 0
                || prefix.value > maxLength) {
            throw new IOException("Некорректная длина Minecraft-пакета");
        }
        byte[] packet = input.readNBytes(prefix.value);
        if (packet.length != prefix.value) {
            throw new EOFException("Неполный Minecraft-пакет");
        }
        return packet;
    }

    private static void writeFramedPacket(OutputStream output, byte[] packet)
            throws IOException {
        writeVarInt(output, packet.length);
        output.write(packet);
    }

    private static String stripLeadingGrayCode(String value) {
        return value.startsWith("&7") ? value.substring(2) : value;
    }

    private static String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) ch));
                    } else {
                        escaped.append(ch);
                    }
                }
            }
        }
        return escaped.toString();
    }

    /**
     * Добавляет в первый Minecraft handshake подписанные данные о реальном адресе
     * клиента. Companion-плагин на Paper проверяет подпись и меняет адрес соединения
     * до событий pre-login, поэтому AuthMe и LibertyBans видят исходный IP.
     */
    private static void forwardMinecraftHandshake(
            Socket client,
            Socket backend,
            Config config
    ) throws IOException {
        InputStream input = client.getInputStream();
        OutputStream output = backend.getOutputStream();

        VarIntPrefix lengthPrefix = readVarIntPrefix(input);
        if (lengthPrefix == null) return;

        // Legacy server-list ping не является пакетным протоколом с VarInt.
        if (lengthPrefix.legacyPing) {
            output.write(lengthPrefix.bytes);
            output.flush();
            return;
        }

        int packetLength = lengthPrefix.value;
        if (packetLength < 0) {
            throw new IOException("Отрицательная длина первого Minecraft-пакета");
        }

        // Обычный handshake занимает меньше килобайта. Не буферизуем произвольный
        // большой пакет: возвращаем уже прочитанный префикс и продолжаем как relay.
        if (packetLength > 8_192) {
            output.write(lengthPrefix.bytes);
            output.flush();
            return;
        }

        byte[] packet = input.readNBytes(packetLength);
        if (packet.length != packetLength) {
            throw new EOFException("Клиент закрыл соединение во время Minecraft handshake");
        }

        byte[] forwardedPacket = rewriteHandshake(packet, client, config);
        writeVarInt(output, forwardedPacket.length);
        output.write(forwardedPacket);
        output.flush();
    }

    private static byte[] rewriteHandshake(
            byte[] packet,
            Socket client,
            Config config
    ) throws IOException {
        try {
            PacketReader reader = new PacketReader(packet);
            int packetId = reader.readVarInt();
            int protocolVersion = reader.readVarInt();
            String serverAddress = reader.readString();
            int serverPort = reader.readUnsignedShort();
            int nextState = reader.readVarInt();

            // STATUS/MOTD оставляем полностью прозрачным. Реальный IP нужен только
            // для login-соединения (nextState=2).
            if (packetId != 0 || nextState != 2 || reader.hasRemaining()) {
                return packet;
            }

            String clientIp = client.getInetAddress().getHostAddress();
            int clientPort = client.getPort();
            long timestamp = System.currentTimeMillis() / 1_000L;
            String forwardedAddress = createForwardedAddress(
                    serverAddress,
                    clientIp,
                    clientPort,
                    timestamp,
                    config.forwardingSecret
            );

            if (forwardedAddress.length() > 255) {
                throw new IOException("Подписанный Minecraft hostname длиннее 255 символов");
            }

            ByteArrayOutputStream rewritten = new ByteArrayOutputStream(packet.length + 160);
            writeVarInt(rewritten, packetId);
            writeVarInt(rewritten, protocolVersion);
            writeString(rewritten, forwardedAddress);
            rewritten.write((serverPort >>> 8) & 0xFF);
            rewritten.write(serverPort & 0xFF);
            writeVarInt(rewritten, nextState);
            return rewritten.toByteArray();
        } catch (IllegalArgumentException e) {
            // Неизвестный/нестандартный первый пакет передаём без изменений.
            return packet;
        }
    }

    private static String createForwardedAddress(
            String originalAddress,
            String clientIp,
            int clientPort,
            long timestamp,
            String secret
    ) throws IOException {
        String signedData = originalAddress + "\n" + clientIp + "\n"
                + clientPort + "\n" + timestamp;
        String signature = hmacSha256(secret, signedData);
        return originalAddress + "\0MCRELAY1\0" + clientIp + "\0"
                + clientPort + "\0" + timestamp + "\0" + signature;
    }

    private static String hmacSha256(String secret, String data) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IOException("Не удалось вычислить подпись IP forwarding", e);
        }
    }

    private static VarIntPrefix readVarIntPrefix(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(5);
        int value = 0;
        int position = 0;

        while (position < 5) {
            int current = input.read();
            if (current == -1) {
                if (position == 0) return null;
                throw new EOFException("Неполный VarInt первого Minecraft-пакета");
            }
            bytes.write(current);

            if (position == 0 && current == 0xFE) {
                return new VarIntPrefix(0, bytes.toByteArray(), true);
            }

            value |= (current & 0x7F) << (position * 7);
            position++;
            if ((current & 0x80) == 0) {
                return new VarIntPrefix(value, bytes.toByteArray(), false);
            }
        }
        throw new IOException("VarInt первого Minecraft-пакета длиннее 5 байт");
    }

    private static void writeVarInt(OutputStream output, int value) throws IOException {
        int remaining = value;
        do {
            int current = remaining & 0x7F;
            remaining >>>= 7;
            if (remaining != 0) current |= 0x80;
            output.write(current);
        } while (remaining != 0);
    }

    private static void writeString(OutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, bytes.length);
        output.write(bytes);
    }

    private record VarIntPrefix(int value, byte[] bytes, boolean legacyPing) {}

    private static final class PacketReader {
        private final byte[] data;
        private int position;

        private PacketReader(byte[] data) {
            this.data = data;
        }

        private int readVarInt() {
            int value = 0;
            int count = 0;
            int current;
            do {
                if (count == 5 || position >= data.length) {
                    throw new IllegalArgumentException("Некорректный VarInt");
                }
                current = data[position++] & 0xFF;
                value |= (current & 0x7F) << (count * 7);
                count++;
            } while ((current & 0x80) != 0);
            return value;
        }

        private String readString() {
            int length = readVarInt();
            if (length < 0 || length > data.length - position) {
                throw new IllegalArgumentException("Некорректная длина строки");
            }
            String value = new String(data, position, length, StandardCharsets.UTF_8);
            position += length;
            return value;
        }

        private int readUnsignedShort() {
            if (position + 2 > data.length) {
                throw new IllegalArgumentException("Нет порта в handshake");
            }
            return ((data[position++] & 0xFF) << 8) | (data[position++] & 0xFF);
        }

        private boolean hasRemaining() {
            return position != data.length;
        }
    }

    private static void startConsoleThread() {
        Thread console = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                while (RUNNING.get() && (line = reader.readLine()) != null) {
                    switch (line.trim().toLowerCase()) {
                        case "status" ->
                                log("Статус: TCP активно " + ACTIVE.get()
                                        + ", всего " + TOTAL.get()
                                        + "; voice-сессий активно " + VOICE_ACTIVE.get()
                                        + ", всего " + VOICE_TOTAL.get()
                                        + ", UDP-пакетов клиент->сервер "
                                        + VOICE_UPSTREAM_PACKETS.get()
                                        + ", сервер->клиент "
                                        + VOICE_DOWNSTREAM_PACKETS.get());
                        case "stop", "end", "shutdown" -> {
                            log("Получена команда остановки.");
                            shutdown();
                            return;
                        }
                        case "help" ->
                                log("Команды: status — статистика, stop — остановка, help — помощь");
                        case "" -> { }
                        default ->
                                log("Неизвестная команда. Доступно: status, stop, help");
                    }
                }
            } catch (IOException ignored) {
            }
        }, "relay-console");

        console.setDaemon(true);
        console.start();
    }

    private static synchronized void shutdown() {
        if (!RUNNING.compareAndSet(true, false)) {
            return;
        }

        log("Остановка TCP Relay...");

        closeQuietly(serverSocket);

        if (voiceRelay != null) {
            voiceRelay.close();
        }

        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        log("TCP Relay остановлен.");
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    private static void log(String message) {
        System.out.println("[" + LocalDateTime.now().format(TIME) + "] " + message);
    }

    /**
     * Прозрачный UDP-релей для Simple Voice Chat.
     *
     * Для каждого адреса клиента создаётся отдельный UDP-сокет к backend. Это важно:
     * Simple Voice Chat запоминает адрес отправителя при аутентификации и отправляет
     * голосовые пакеты обратно именно на него. Один общий backend-сокет не позволил бы
     * определить, какому клиенту предназначен ответ.
     */
    private static final class VoiceRelay implements Closeable {
        private static final int MAX_UDP_PAYLOAD = 65_507;
        private static final long LIMIT_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5);

        private final Config config;
        private final InetSocketAddress backendAddress;
        private final Map<InetSocketAddress, VoiceSession> sessions = new ConcurrentHashMap<>();
        private final AtomicBoolean running = new AtomicBoolean(false);

        private Selector selector;
        private DatagramChannel listener;
        private Thread thread;
        private long lastLimitLogNanos;

        private VoiceRelay(Config config) throws UnknownHostException {
            this.config = config;
            InetAddress backendHost = InetAddress.getByName(config.voiceBackendHost);
            this.backendAddress = new InetSocketAddress(backendHost, config.voiceBackendPort);
        }

        private void start() throws IOException {
            if (!running.compareAndSet(false, true)) {
                throw new IllegalStateException("UDP-релей уже запущен");
            }

            try {
                InetAddress bindHost = InetAddress.getByName(config.voiceBindAddress);
                InetSocketAddress bindAddress =
                        new InetSocketAddress(bindHost, config.voiceListenPort);

                selector = Selector.open();
                listener = DatagramChannel.open(protocolFamily(bindHost));
                listener.setOption(StandardSocketOptions.SO_REUSEADDR, true);
                listener.setOption(StandardSocketOptions.SO_RCVBUF, config.socketBufferBytes);
                listener.setOption(StandardSocketOptions.SO_SNDBUF, config.socketBufferBytes);
                listener.configureBlocking(false);
                listener.bind(bindAddress);
                listener.register(selector, SelectionKey.OP_READ);

                thread = new Thread(this::run, "voice-udp-relay");
                thread.setDaemon(true);
                thread.start();
            } catch (IOException | RuntimeException e) {
                running.set(false);
                closeQuietly(listener);
                closeQuietly(selector);
                throw e;
            }
        }

        private void run() {
            ByteBuffer buffer = ByteBuffer.allocateDirect(MAX_UDP_PAYLOAD);
            long lastCleanupNanos = System.nanoTime();

            try {
                while (running.get()) {
                    selector.select(1_000);

                    Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                    while (keys.hasNext()) {
                        SelectionKey key = keys.next();
                        keys.remove();

                        if (!key.isValid() || !key.isReadable()) continue;

                        if (key.channel() == listener) {
                            receiveFromClients(buffer);
                        } else {
                            VoiceSession session = (VoiceSession) key.attachment();
                            receiveFromBackend(session, buffer);
                        }
                    }

                    long now = System.nanoTime();
                    if (now - lastCleanupNanos >= TimeUnit.SECONDS.toNanos(1)) {
                        removeExpiredSessions(now);
                        lastCleanupNanos = now;
                    }
                }
            } catch (IOException e) {
                if (running.get()) {
                    log("Ошибка UDP-релея Simple Voice Chat: " + e.getMessage());
                }
            } catch (RuntimeException e) {
                if (running.get()) {
                    log("Критическая ошибка UDP-релея Simple Voice Chat: " + e.getMessage());
                    e.printStackTrace(System.out);
                }
            } finally {
                running.set(false);
                closeAllSessions();
                closeQuietly(listener);
                closeQuietly(selector);
            }
        }

        private void receiveFromClients(ByteBuffer buffer) throws IOException {
            while (running.get()) {
                buffer.clear();
                SocketAddress source = listener.receive(buffer);
                if (source == null) return;
                if (!(source instanceof InetSocketAddress clientAddress)) continue;

                VoiceSession session = sessions.get(clientAddress);
                if (session == null || session.closed.get()) {
                    try {
                        session = openSession(clientAddress);
                    } catch (IOException | RuntimeException e) {
                        log("Не удалось открыть voice UDP-сессию для " + clientAddress
                                + ": " + e.getMessage());
                        continue;
                    }
                    if (session == null) continue;
                }

                session.touch();
                buffer.flip();
                int packetSize = buffer.remaining();

                try {
                    int sent = session.backend.write(buffer);
                    if (sent == packetSize) {
                        VOICE_UPSTREAM_PACKETS.incrementAndGet();
                    }
                } catch (IOException e) {
                    log("Voice UDP " + clientAddress + " -> backend: " + e.getMessage());
                    closeSession(session, false);
                }
            }
        }

        private void receiveFromBackend(VoiceSession session, ByteBuffer buffer) {
            if (session == null || session.closed.get()) return;

            try {
                while (running.get()) {
                    buffer.clear();
                    SocketAddress source = session.backend.receive(buffer);
                    if (source == null) return;

                    session.touch();
                    buffer.flip();
                    int packetSize = buffer.remaining();
                    int sent = listener.send(buffer, session.clientAddress);
                    if (sent == packetSize) {
                        VOICE_DOWNSTREAM_PACKETS.incrementAndGet();
                    }
                }
            } catch (IOException e) {
                if (running.get() && !session.closed.get()) {
                    log("Voice UDP backend -> " + session.clientAddress
                            + ": " + e.getMessage());
                }
                closeSession(session, false);
            }
        }

        private VoiceSession openSession(InetSocketAddress clientAddress) throws IOException {
            if (sessions.size() >= config.voiceMaxSessions) {
                long now = System.nanoTime();
                if (now - lastLimitLogNanos >= LIMIT_LOG_INTERVAL_NANOS) {
                    lastLimitLogNanos = now;
                    log("Отклонены новые voice UDP-сессии: достигнут лимит "
                            + config.voiceMaxSessions);
                }
                return null;
            }

            DatagramChannel backend = null;
            try {
                backend = DatagramChannel.open(protocolFamily(backendAddress.getAddress()));
                backend.setOption(StandardSocketOptions.SO_RCVBUF, config.socketBufferBytes);
                backend.setOption(StandardSocketOptions.SO_SNDBUF, config.socketBufferBytes);
                backend.configureBlocking(false);
                backend.connect(backendAddress);

                VoiceSession session = new VoiceSession(clientAddress, backend);
                backend.register(selector, SelectionKey.OP_READ, session);
                sessions.put(clientAddress, session);

                int id = VOICE_TOTAL.incrementAndGet();
                VOICE_ACTIVE.incrementAndGet();
                log("Voice UDP #" + id + " открыт: " + clientAddress
                        + " -> " + backendAddress);
                return session;
            } catch (IOException | RuntimeException e) {
                closeQuietly(backend);
                throw e;
            }
        }

        private void removeExpiredSessions(long now) {
            long timeout = TimeUnit.MILLISECONDS.toNanos(config.voiceSessionTimeoutMs);
            for (VoiceSession session : sessions.values()) {
                if (now - session.lastActivityNanos >= timeout) {
                    closeSession(session, true);
                }
            }
        }

        private void closeSession(VoiceSession session, boolean timedOut) {
            if (!session.closed.compareAndSet(false, true)) return;

            sessions.remove(session.clientAddress, session);
            closeQuietly(session.backend);
            int active = VOICE_ACTIVE.decrementAndGet();

            if (timedOut) {
                log("Voice UDP-сессия закрыта по тайм-ауту: " + session.clientAddress
                        + " | активно: " + active);
            }
        }

        private void closeAllSessions() {
            for (VoiceSession session : sessions.values()) {
                closeSession(session, false);
            }
        }

        @Override
        public void close() {
            if (!running.compareAndSet(true, false)) return;

            if (selector != null) selector.wakeup();
            closeQuietly(listener);
            closeAllSessions();

            if (thread != null && thread != Thread.currentThread()) {
                try {
                    thread.join(2_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            closeQuietly(selector);
        }

        private static ProtocolFamily protocolFamily(InetAddress address) {
            return address instanceof Inet6Address
                    ? StandardProtocolFamily.INET6
                    : StandardProtocolFamily.INET;
        }

        private static final class VoiceSession {
            private final InetSocketAddress clientAddress;
            private final DatagramChannel backend;
            private final AtomicBoolean closed = new AtomicBoolean(false);
            private volatile long lastActivityNanos = System.nanoTime();

            private VoiceSession(
                    InetSocketAddress clientAddress,
                    DatagramChannel backend
            ) {
                this.clientAddress = clientAddress;
                this.backend = backend;
            }

            private void touch() {
                lastActivityNanos = System.nanoTime();
            }
        }
    }

    private record Config(
            String bindAddress,
            int listenPort,
            String backendHost,
            int backendPort,
            int connectTimeoutMs,
            int maxConnections,
            int backlog,
            int socketBufferBytes,
            boolean tcpNoDelay,
            boolean forwardClientIp,
            String forwardingSecret,
            boolean offlineMotdEnabled,
            String offlineMotdLine1,
            String offlineMotdLine2,
            boolean voiceEnabled,
            String voiceBindAddress,
            int voiceListenPort,
            String voiceBackendHost,
            int voiceBackendPort,
            int voiceSessionTimeoutMs,
            int voiceMaxSessions
    ) {
        static Config load() throws IOException {
            Properties relay = loadProperties("relay.properties");
            Properties server = loadProperties("server.properties");

            String bindAddress = firstNonBlank(
                    System.getenv("BIND_ADDRESS"),
                    System.getProperty("bind.address"),
                    relay.getProperty("bind-address"),
                    "0.0.0.0"
            );

            String listenPortRaw = firstNonBlank(
                    System.getenv("LISTEN_PORT"),
                    System.getenv("SERVER_PORT"),
                    System.getenv("PORT"),
                    System.getProperty("listen.port"),
                    relay.getProperty("listen-port"),
                    server.getProperty("server-port"),
                    "25565"
            );

            String backendHost = firstNonBlank(
                    System.getenv("BACKEND_HOST"),
                    System.getProperty("backend.host"),
                    relay.getProperty("backend-host"),
                    null
            );

            String backendPortRaw = firstNonBlank(
                    System.getenv("BACKEND_PORT"),
                    System.getProperty("backend.port"),
                    relay.getProperty("backend-port"),
                    null
            );

            if (backendHost == null || backendPortRaw == null) {
                createExampleConfigIfMissing();
                throw new IllegalArgumentException(
                        "Укажи backend-host и backend-port в relay.properties"
                );
            }

            String voiceListenPortRaw = firstNonBlank(
                    System.getenv("VOICE_LISTEN_PORT"),
                    System.getProperty("voice.listen.port"),
                    relay.getProperty("voice-listen-port"),
                    null
            );

            String voiceEnabledRaw = firstNonBlank(
                    System.getenv("VOICE_ENABLED"),
                    System.getProperty("voice.enabled"),
                    relay.getProperty("voice-enabled"),
                    null
            );

            boolean voiceEnabled = voiceEnabledRaw == null
                    ? voiceListenPortRaw != null
                    : parseBoolean("voice-enabled", voiceEnabledRaw);

            String voiceBindAddress = firstNonBlank(
                    System.getenv("VOICE_BIND_ADDRESS"),
                    System.getProperty("voice.bind.address"),
                    relay.getProperty("voice-bind-address"),
                    bindAddress
            );

            String voiceBackendHost = firstNonBlank(
                    System.getenv("VOICE_BACKEND_HOST"),
                    System.getProperty("voice.backend.host"),
                    relay.getProperty("voice-backend-host"),
                    backendHost
            );

            String voiceBackendPortRaw = firstNonBlank(
                    System.getenv("VOICE_BACKEND_PORT"),
                    System.getProperty("voice.backend.port"),
                    relay.getProperty("voice-backend-port"),
                    "24454"
            );

            boolean forwardClientIp = parseBoolean(
                    "forward-client-ip",
                    firstNonBlank(
                            System.getenv("FORWARD_CLIENT_IP"),
                            System.getProperty("forward.client.ip"),
                            relay.getProperty("forward-client-ip"),
                            "false"
                    )
            );

            String forwardingSecret = firstNonBlank(
                    System.getenv("FORWARDING_SECRET"),
                    System.getProperty("forwarding.secret"),
                    relay.getProperty("forwarding-secret"),
                    null
            );
            if (forwardClientIp && (forwardingSecret == null
                    || forwardingSecret.getBytes(StandardCharsets.UTF_8).length < 32)) {
                throw new IllegalArgumentException(
                        "При forward-client-ip=true задай forwarding-secret длиной минимум 32 байта"
                );
            }

            boolean offlineMotdEnabled = parseBoolean(
                    "offline-motd-enabled",
                    relay.getProperty("offline-motd-enabled", "true")
            );
            String offlineMotdLine1 = relay.getProperty(
                    "offline-motd-line1", "&7Извините, главный сервер не отвечает"
            );
            String offlineMotdLine2 = relay.getProperty(
                    "offline-motd-line2", "&7Он выключен, или у хоста тех работы."
            );

            return new Config(
                    bindAddress,
                    parsePort("listen-port", listenPortRaw),
                    backendHost,
                    parsePort("backend-port", backendPortRaw),
                    parseInt(relay, "connect-timeout-ms", 10_000, 1_000, 120_000),
                    parseInt(relay, "max-connections", 200, 1, 10_000),
                    parseInt(relay, "backlog", 128, 1, 10_000),
                    parseInt(relay, "socket-buffer-bytes", 262_144, 8_192, 4_194_304),
                    parseBoolean("tcp-no-delay", relay.getProperty("tcp-no-delay", "true")),
                    forwardClientIp,
                    forwardingSecret,
                    offlineMotdEnabled,
                    offlineMotdLine1,
                    offlineMotdLine2,
                    voiceEnabled,
                    voiceBindAddress,
                    parsePort("voice-listen-port", firstNonBlank(voiceListenPortRaw, "24454")),
                    voiceBackendHost,
                    parsePort("voice-backend-port", voiceBackendPortRaw),
                    parseInt(relay, "voice-session-timeout-ms", 60_000, 5_000, 3_600_000),
                    parseInt(relay, "voice-max-sessions", 200, 1, 10_000)
            );
        }

        private static Properties loadProperties(String filename) throws IOException {
            Properties properties = new Properties();
            Path path = Path.of(filename);
            if (Files.isRegularFile(path)) {
                try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
            }
            return properties;
        }

        private static void createExampleConfigIfMissing() {
            Path path = Path.of("relay.properties");
            if (Files.exists(path)) return;

            String example = """
                    # Порт, который выдал российский панельный хостинг
                    listen-port=25565

                    # Адрес основного Minecraft-сервера
                    backend-host=CHANGE_ME
                    backend-port=25565

                    # Simple Voice Chat (отдельный порт UDP)
                    voice-enabled=true
                    voice-listen-port=24454
                    voice-backend-port=24454

                    # Дополнительные параметры
                    bind-address=0.0.0.0
                    connect-timeout-ms=10000
                    max-connections=200
                    backlog=128
                    socket-buffer-bytes=262144
                    tcp-no-delay=true
                    forward-client-ip=false
                    # Один секрет в relay и backend-плагине (минимум 32 байта)
                    forwarding-secret=CHANGE_ME_TO_A_LONG_RANDOM_SECRET
                    offline-motd-enabled=true
                    offline-motd-line1=&7Извините, главный сервер не отвечает
                    offline-motd-line2=&7Он выключен, или у хоста тех работы.
                    voice-session-timeout-ms=60000
                    voice-max-sessions=200
                    """;

            try {
                Files.writeString(path, example);
            } catch (IOException ignored) {
            }
        }

        private static int parsePort(String name, String value) {
            int port;
            try {
                port = Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(name + " должен быть числом: " + value);
            }

            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException(name + " должен быть от 1 до 65535");
            }
            return port;
        }

        private static int parseInt(
                Properties properties,
                String name,
                int defaultValue,
                int min,
                int max
        ) {
            String value = properties.getProperty(name);
            if (value == null || value.isBlank()) return defaultValue;

            int parsed;
            try {
                parsed = Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(name + " должен быть числом: " + value);
            }

            if (parsed < min || parsed > max) {
                throw new IllegalArgumentException(name + " должен быть от " + min + " до " + max);
            }
            return parsed;
        }

        private static boolean parseBoolean(String name, String value) {
            if ("true".equalsIgnoreCase(value.trim())) return true;
            if ("false".equalsIgnoreCase(value.trim())) return false;
            throw new IllegalArgumentException(name + " должен быть true или false: " + value);
        }

        private static String firstNonBlank(String... values) {
            for (String value : values) {
                if (value != null && !value.isBlank()) return value.trim();
            }
            return null;
        }
    }
}

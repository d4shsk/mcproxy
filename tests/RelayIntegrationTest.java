import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class RelayIntegrationTest {
    private static final String SECRET =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    public static void main(String[] args) throws Exception {
        Path relayJar = Path.of(args[0]).toAbsolutePath();
        Path runDirectory = Files.createTempDirectory("mc-relay-test-");

        try (ServerSocket backend = new ServerSocket(0);
             DatagramSocket voiceBackend = new DatagramSocket(0)) {
            int relayPort;
            try (ServerSocket portReservation = new ServerSocket(0)) {
                relayPort = portReservation.getLocalPort();
            }
            int voiceRelayPort;
            try (DatagramSocket portReservation = new DatagramSocket(0)) {
                voiceRelayPort = portReservation.getLocalPort();
            }

            Files.writeString(runDirectory.resolve("relay.properties"), """
                    bind-address=127.0.0.1
                    listen-port=%d
                    backend-host=127.0.0.1
                    backend-port=%d
                    voice-enabled=true
                    voice-bind-address=127.0.0.1
                    voice-listen-port=%d
                    voice-backend-host=127.0.0.1
                    voice-backend-port=%d
                    forward-client-ip=true
                    forwarding-secret=%s
                    """.formatted(
                            relayPort,
                            backend.getLocalPort(),
                            voiceRelayPort,
                            voiceBackend.getLocalPort(),
                            SECRET
                    ));

            Process relay = new ProcessBuilder("java", "-jar", relayJar.toString())
                    .directory(runDirectory.toFile())
                    .redirectErrorStream(true)
                    .start();
            List<String> logs = new ArrayList<>();
            CompletableFuture<Void> started = new CompletableFuture<>();
            Thread logReader = new Thread(() -> {
                try (var reader = relay.inputReader(StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (logs) {
                            logs.add(line);
                        }
                        if (line.contains("Minecraft TCP Relay запущен")) {
                            started.complete(null);
                        }
                    }
                } catch (Exception e) {
                    started.completeExceptionally(e);
                }
            });
            logReader.start();

            try {
                started.get(5, TimeUnit.SECONDS);
                verifyLoginForwarding(backend, relayPort);
                verifyStatusIsTransparent(backend, relayPort);
                verifyVoiceUdp(voiceBackend, voiceRelayPort);
                backend.close();
                verifyOfflineMotd(relayPort);
                relay.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8));
                relay.getOutputStream().flush();
                if (!relay.waitFor(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Relay не остановился по команде stop");
                }
                if (relay.exitValue() != 0) {
                    throw new AssertionError("Relay завершился с кодом " + relay.exitValue()
                            + "\n" + String.join("\n", logs));
                }
            } finally {
                relay.destroyForcibly();
                logReader.join(2_000);
            }
        }

        System.out.println("RelayIntegrationTest: OK");
    }

    private static void verifyLoginForwarding(ServerSocket backend, int relayPort) throws Exception {
        byte[] original = handshake("relay.example.com", 28_939, 2);
        try (Socket client = new Socket("127.0.0.1", relayPort);
             Socket serverSide = accept(backend)) {
            client.getOutputStream().write(original);
            client.getOutputStream().flush();

            byte[] packet = readFramedPacket(serverSide.getInputStream());
            PacketReader reader = new PacketReader(packet);
            assertEquals(0, reader.varInt(), "packet id");
            assertEquals(763, reader.varInt(), "protocol");
            String address = reader.string();
            assertEquals(28_939, reader.unsignedShort(), "server port");
            assertEquals(2, reader.varInt(), "next state");

            String marker = "\0MCRELAY1\0";
            int markerIndex = address.lastIndexOf(marker);
            if (markerIndex < 0) throw new AssertionError("Нет MCRELAY1 в login handshake");
            assertEquals("relay.example.com", address.substring(0, markerIndex), "original host");

            String[] fields = address.substring(markerIndex + marker.length()).split("\0", -1);
            assertEquals(4, fields.length, "forwarding fields");
            assertEquals("127.0.0.1", fields[0], "client IP");
            long timestamp = Long.parseLong(fields[2]);
            if (Math.abs(System.currentTimeMillis() / 1_000L - timestamp) > 5) {
                throw new AssertionError("Некорректный timestamp");
            }

            String signed = "relay.example.com\n" + fields[0] + "\n"
                    + fields[1] + "\n" + fields[2];
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(signed.getBytes(StandardCharsets.UTF_8));
            byte[] supplied = Base64.getUrlDecoder().decode(fields[3]);
            if (!MessageDigest.isEqual(expected, supplied)) {
                throw new AssertionError("Неверная HMAC-подпись");
            }

            byte[] continuation = {1, 2, 3, 4};
            client.getOutputStream().write(continuation);
            client.getOutputStream().flush();
            byte[] received = serverSide.getInputStream().readNBytes(continuation.length);
            if (!MessageDigest.isEqual(continuation, received)) {
                throw new AssertionError("TCP после handshake передаётся неверно");
            }
        }
    }

    private static void verifyStatusIsTransparent(ServerSocket backend, int relayPort)
            throws Exception {
        byte[] status = handshake("relay.example.com", 28_939, 1);
        try (Socket client = new Socket("127.0.0.1", relayPort);
             Socket serverSide = accept(backend)) {
            client.getOutputStream().write(status);
            client.getOutputStream().flush();
            byte[] received = readWholeFrame(serverSide.getInputStream());
            if (!MessageDigest.isEqual(status, received)) {
                throw new AssertionError("STATUS handshake был изменён");
            }
        }
    }

    private static void verifyVoiceUdp(DatagramSocket backend, int relayPort) throws Exception {
        byte[] request = "voice-upstream".getBytes(StandardCharsets.UTF_8);
        byte[] response = "voice-downstream".getBytes(StandardCharsets.UTF_8);

        try (DatagramSocket client = new DatagramSocket()) {
            client.setSoTimeout(5_000);
            backend.setSoTimeout(5_000);
            client.send(new DatagramPacket(
                    request,
                    request.length,
                    new InetSocketAddress("127.0.0.1", relayPort)
            ));

            DatagramPacket atBackend = new DatagramPacket(new byte[128], 128);
            backend.receive(atBackend);
            assertBytes(request, atBackend.getData(), atBackend.getLength(), "voice upstream");

            backend.send(new DatagramPacket(
                    response,
                    response.length,
                    atBackend.getSocketAddress()
            ));
            DatagramPacket atClient = new DatagramPacket(new byte[128], 128);
            client.receive(atClient);
            assertBytes(response, atClient.getData(), atClient.getLength(), "voice downstream");
        }
    }

    private static void verifyOfflineMotd(int relayPort) throws Exception {
        try (Socket client = new Socket("127.0.0.1", relayPort)) {
            client.setSoTimeout(5_000);
            OutputStream output = client.getOutputStream();
            output.write(handshake("proxy.example.com", 28_939, 1));
            output.write(new byte[]{1, 0}); // STATUS request: length=1, packet id=0
            output.flush();

            byte[] response = readFramedPacket(client.getInputStream());
            PacketReader reader = new PacketReader(response);
            assertEquals(0, reader.varInt(), "offline STATUS packet id");
            String json = reader.string();
            if (!json.contains("Извините, главный сервер не отвечает")
                    || !json.contains("Он выключен, или у хоста тех работы.")
                    || !json.contains("\"color\":\"gray\"")) {
                throw new AssertionError("Неверный offline MOTD JSON: " + json);
            }

            byte[] ping = new byte[9];
            ping[0] = 1;
            long payload = 0x0102030405060708L;
            for (int i = 0; i < 8; i++) {
                ping[8 - i] = (byte) (payload >>> (i * 8));
            }
            ByteArrayOutputStream framedPing = new ByteArrayOutputStream();
            varInt(framedPing, ping.length);
            framedPing.write(ping);
            output.write(framedPing.toByteArray());
            output.flush();

            byte[] pong = readFramedPacket(client.getInputStream());
            assertBytes(ping, pong, pong.length, "offline STATUS pong");
        }
    }

    private static Socket accept(ServerSocket server) throws Exception {
        server.setSoTimeout(5_000);
        return server.accept();
    }

    private static byte[] handshake(String host, int port, int nextState) throws Exception {
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        varInt(packet, 0);
        varInt(packet, 763);
        string(packet, host);
        packet.write((port >>> 8) & 0xFF);
        packet.write(port & 0xFF);
        varInt(packet, nextState);

        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        varInt(frame, packet.size());
        packet.writeTo(frame);
        return frame.toByteArray();
    }

    private static byte[] readFramedPacket(InputStream input) throws Exception {
        int length = readVarInt(input);
        byte[] packet = input.readNBytes(length);
        if (packet.length != length) throw new AssertionError("Неполный пакет");
        return packet;
    }

    private static byte[] readWholeFrame(InputStream input) throws Exception {
        int length = readVarInt(input);
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        varInt(result, length);
        result.write(input.readNBytes(length));
        return result.toByteArray();
    }

    private static int readVarInt(InputStream input) throws Exception {
        int value = 0;
        int position = 0;
        while (position < 5) {
            int current = input.read();
            if (current < 0) throw new AssertionError("EOF в VarInt");
            value |= (current & 0x7F) << (position * 7);
            if ((current & 0x80) == 0) return value;
            position++;
        }
        throw new AssertionError("Слишком длинный VarInt");
    }

    private static void varInt(OutputStream output, int value) throws Exception {
        do {
            int current = value & 0x7F;
            value >>>= 7;
            if (value != 0) current |= 0x80;
            output.write(current);
        } while (value != 0);
    }

    private static void string(OutputStream output, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        varInt(output, bytes.length);
        output.write(bytes);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertBytes(byte[] expected, byte[] actual, int length, String message) {
        byte[] exact = new byte[length];
        System.arraycopy(actual, 0, exact, 0, length);
        if (!MessageDigest.isEqual(expected, exact)) {
            throw new AssertionError(message + " не совпадает");
        }
    }

    private static final class PacketReader {
        private final DataInputStream input;

        private PacketReader(byte[] packet) {
            input = new DataInputStream(new ByteArrayInputStream(packet));
        }

        private int varInt() throws Exception {
            return readVarInt(input);
        }

        private String string() throws Exception {
            return new String(input.readNBytes(varInt()), StandardCharsets.UTF_8);
        }

        private int unsignedShort() throws Exception {
            return input.readUnsignedShort();
        }
    }
}

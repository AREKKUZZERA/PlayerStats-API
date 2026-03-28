package com.plp.statsplugin;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.FileReader;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class StatsUtil {

    private static final Gson GSON = new Gson();

    /** Список известных секций статистики Minecraft */
    private static final String[] STAT_SECTIONS = {
        "minecraft:custom",
        "minecraft:mined",
        "minecraft:crafted",
        "minecraft:used",
        "minecraft:broken",
        "minecraft:picked_up",
        "minecraft:dropped",
        "minecraft:killed",
        "minecraft:killed_by"
    };

    private static volatile File statsFolder;
    private static volatile Logger logger;

    private StatsUtil() {}

    public static void setStatsFolder(File folder) {
        statsFolder = folder;
    }

    public static void setLogger(Logger pluginLogger) {
        logger = pluginLogger;
    }

    // -------------------------------------------------------------------------
    // Чтение JSON файла статистики
    // -------------------------------------------------------------------------

    public static JsonObject readStats(UUID uuid) {
        if (uuid == null) return null;

        File dir = statsFolder;
        if (dir == null || !dir.isDirectory()) {
            log(Level.WARNING, "Папка stats/ не задана или недоступна.");
            return null;
        }

        File file = new File(dir, uuid + ".json");
        if (!file.exists()) return null;

        try (FileReader reader = new FileReader(file)) {
            return GSON.fromJson(reader, JsonObject.class);
        } catch (Exception e) {
            log(Level.WARNING, "Ошибка чтения " + file.getName() + ": " + e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Выборка значений
    // -------------------------------------------------------------------------

    /**
     * Ищет ключ во всех известных секциях статистики.
     */
    public static int getAnyStat(JsonObject root, String statKey) {
        JsonObject statsRoot = getStatsRoot(root);
        if (statsRoot == null) return 0;

        for (String section : STAT_SECTIONS) {
            int v = readIntFromSection(statsRoot, section, statKey);
            if (v != 0) return v;
        }
        return 0;
    }

    /**
     * Читает значение из конкретной секции.
     */
    public static int getStatInSection(JsonObject root, String section, String statKey) {
        if (section == null || statKey == null) return 0;
        JsonObject statsRoot = getStatsRoot(root);
        if (statsRoot == null) return 0;
        return readIntFromSection(statsRoot, section, statKey);
    }

    /**
     * Проверяет наличие ключа в секции.
     */
    public static boolean sectionHasStatKey(JsonObject root, String section, String statKey) {
        if (section == null || statKey == null) return false;
        JsonObject statsRoot = getStatsRoot(root);
        if (statsRoot == null) return false;
        JsonObject sec = getSection(statsRoot, section);
        return sec != null && sec.has(statKey);
    }

    /**
     * Возвращает набор секций, присутствующих в объекте статистики.
     */
    public static Set<String> getAvailableStatSections(JsonObject root) {
        Set<String> result = new HashSet<>();
        JsonObject statsRoot = getStatsRoot(root);
        if (statsRoot == null) return result;
        for (Map.Entry<String, JsonElement> entry : statsRoot.entrySet()) {
            if (entry.getValue() != null && entry.getValue().isJsonObject()) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Внутренние хелперы
    // -------------------------------------------------------------------------

    private static JsonObject getStatsRoot(JsonObject root) {
        if (root == null) return null;
        JsonElement el = root.get("stats");
        return (el != null && el.isJsonObject()) ? el.getAsJsonObject() : null;
    }

    private static JsonObject getSection(JsonObject statsRoot, String section) {
        JsonElement el = statsRoot.get(section);
        return (el != null && el.isJsonObject()) ? el.getAsJsonObject() : null;
    }

    private static int readIntFromSection(JsonObject statsRoot, String section, String key) {
        JsonObject sec = getSection(statsRoot, section);
        if (sec == null || !sec.has(key)) return 0;
        try {
            return sec.get(key).getAsInt();
        } catch (Exception e) {
            return 0;
        }
    }

    private static void log(Level level, String message) {
        if (logger != null) logger.log(level, message);
    }
}

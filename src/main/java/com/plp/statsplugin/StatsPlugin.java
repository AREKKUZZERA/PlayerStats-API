package com.plp.statsplugin;

import com.google.gson.JsonObject;
import java.io.File;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public class StatsPlugin extends JavaPlugin {

    private StatsManager statsManager;
    private StatsHistoryManager historyManager;
    private WebServer webServer;
    private BukkitTask autoUpdateTask;
    private int webBoundPort = -1;

    // Тики в секунду → минутах: 1 тик = 1/20 сек = 1/1200 мин
    private static final int TICKS_PER_MINUTE = 1200;

    @Override
    public void onEnable() {
        try {
            getLogger().info("Starting PlayerStatsAPI v" + getPluginMeta().getVersion()
                    + " on Java " + System.getProperty("java.version")
                    + ", server " + Bukkit.getVersion() + ".");

            getLogger().info("Startup step: loading default config.");
            saveDefaultConfig();

            getLogger().info("Startup step: wiring shared stats utilities.");
            StatsUtil.setLogger(getLogger());
            StatsUtil.setStatsFolder(resolveStatsFolder());

            getLogger().info("Startup step: initializing history and stats managers.");
            historyManager = new StatsHistoryManager(getDataFolder(), getLogger(), getHistoryMaxPointsPerPlayer());
            statsManager = new StatsManager(this);
            Bukkit.getPluginManager().registerEvents(statsManager, this);

            getLogger().info("Startup step: preloading player stats.");
            statsManager.preloadAllStatsAsync();

            getLogger().info("Startup step: scheduling periodic refresh.");
            restartStatsAutoUpdate();

            getLogger().info("Startup step: starting web server.");
            restartWebServer();

            getLogger().info("PlayerStatsAPI v" + getPluginMeta().getVersion() + " enabled successfully.");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "PlayerStatsAPI failed during startup.", e);
            throw new IllegalStateException("PlayerStatsAPI startup failed", e);
        }
    }

    @Override
    public void onDisable() {
        if (webServer != null) {
            webServer.stop();
        }
        if (autoUpdateTask != null) {
            autoUpdateTask.cancel();
        }
        getLogger().info("PlayerStatsAPI v" + getPluginMeta().getVersion() + " отключён.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        return switch (cmd.getName().toLowerCase()) {
            case "playerstatsapi" -> cmdPlayerStatsApi(sender, args);
            case "stat" -> cmdStat(sender, args);
            case "stats" -> cmdStats(sender, args);
            case "statsreload" -> cmdStatsReload(sender);
            case "statsonline" -> cmdStatsOnline(sender);
            case "statstop" -> cmdStatsTop(sender, args);
            default -> false;
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        return switch (cmd.getName().toLowerCase()) {
            case "playerstatsapi" -> tabCompletePlayerStatsApi(args);
            case "stat", "stats" -> tabCompletePlayers(args);
            case "statstop" -> tabCompleteTop(args);
            default -> List.of();
        };
    }

    // =========================================================================
    // Команды
    // =========================================================================

    private boolean cmdPlayerStatsApi(CommandSender sender, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendPlayerStatsApiHelp(sender);
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "reload" -> cmdStatsReload(sender);
            case "status" -> cmdPlayerStatsApiStatus(sender);
            case "synclog" -> cmdPlayerStatsApiSyncLog(sender, args);
            default -> {
                sender.sendMessage("§cНеизвестная подкоманда: §f" + args[0]);
                sendPlayerStatsApiHelp(sender);
                yield true;
            }
        };
    }

    private void sendPlayerStatsApiHelp(CommandSender sender) {
        sender.sendMessage("§6[PlayerStatsAPI] §fКоманды:");
        sender.sendMessage(" §e/psa help §7— показать помощь");
        sender.sendMessage(" §e/psa status §7— состояние плагина");
        sender.sendMessage(" §e/psa reload §7— перечитать config.yml и статистику");
        sender.sendMessage(" §e/psa synclog <on|off> §7— включить или выключить [Sync] лог");
        sender.sendMessage(" §e/stat <игрок> <minecraft:ключ> §7— одна статистика");
        sender.sendMessage(" §e/stats <игрок> §7— сводка игрока");
        sender.sendMessage(" §e/statstop <minecraft:ключ> [лимит] §7— топ игроков");
        sender.sendMessage(" §e/statsonline §7— онлайн-игроки");
    }

    private boolean cmdPlayerStatsApiStatus(CommandSender sender) {
        sender.sendMessage("§6[PlayerStatsAPI] §fСтатус:");
        sender.sendMessage(" §7Кеш игроков: §a" + statsManager.getStatsCache().size());
        sender.sendMessage(" §7Онлайн: §a" + statsManager.getOnlinePlayerIdSet().size());
        sender.sendMessage(" §7Авто-обновление: §a" + getStatsUpdateIntervalSeconds() + " сек.");
        sender.sendMessage(" §7Sync-лог: §a" + (isSyncUpdateLoggingEnabled() ? "on" : "off"));
        sender.sendMessage(" §7Web API: §a" + (webServer != null ? "on, порт " + webBoundPort : "off"));
        sender.sendMessage(" §7Топ по умолчанию: §a" + getDefaultTopLimit() + "§7, максимум: §a" + getMaxTopLimit());
        return true;
    }

    private boolean cmdPlayerStatsApiSyncLog(CommandSender sender, String[] args) {
        if (args.length != 2 || (!args[1].equalsIgnoreCase("on") && !args[1].equalsIgnoreCase("off"))) {
            sender.sendMessage("§eИспользование: §f/psa synclog <on|off>");
            return true;
        }

        boolean enabled = args[1].equalsIgnoreCase("on");
        setSyncUpdateLoggingEnabled(enabled);
        sender.sendMessage("§a[Stats] Sync-лог " + (enabled ? "включён." : "выключен."));
        return true;
    }

    /**
     * /stat <игрок> <minecraft:ключ>
     * Показывает конкретное значение статистики (работает и с оффлайн-игроками).
     */
    private boolean cmdStat(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage("§eИспользование: §f/stat <игрок> <minecraft:ключ>");
            return true;
        }

        UUID uuid = resolvePlayerUUID(args[0]);
        if (uuid == null) {
            sender.sendMessage("§cИгрок §f" + args[0] + " §cне найден.");
            return true;
        }

        String statKey = args[1];
        JsonObject stats = statsManager.getFullStats(uuid);
        int value = StatsUtil.getAnyStat(stats, statKey);
        String name = statsManager.getPlayerName(uuid);

        sender.sendMessage("§6[Stats] §f" + name + " §7» §e" + statKey + " §f= §a" + value);
        return true;
    }

    /**
     * /stats <игрок>
     * Краткая сводка: смерти, игровое время, прыжки, блоки, убийства.
     */
    private boolean cmdStats(CommandSender sender, String[] args) {
        if (args.length != 1) {
            sender.sendMessage("§eИспользование: §f/stats <игрок>");
            return true;
        }

        UUID uuid = resolvePlayerUUID(args[0]);
        if (uuid == null) {
            sender.sendMessage("§cИгрок §f" + args[0] + " §cне найден.");
            return true;
        }

        JsonObject stats = statsManager.getFullStats(uuid);
        String name = statsManager.getPlayerName(uuid);

        int deaths = StatsUtil.getStatInSection(stats, "minecraft:custom", "minecraft:deaths");
        int playtimeTk = StatsUtil.getStatInSection(stats, "minecraft:custom", "minecraft:play_time");
        int jumps = StatsUtil.getStatInSection(stats, "minecraft:custom", "minecraft:jump");
        int killed = StatsUtil.getStatInSection(stats, "minecraft:custom", "minecraft:mob_kills");
        int playerKills = StatsUtil.getStatInSection(stats, "minecraft:custom", "minecraft:player_kills");
        int dmgDealt = StatsUtil.getStatInSection(stats, "minecraft:custom", "minecraft:damage_dealt");
        int dmgTaken = StatsUtil.getStatInSection(stats, "minecraft:custom", "minecraft:damage_taken");
        int distWalk = StatsUtil.getStatInSection(stats, "minecraft:custom", "minecraft:walk_one_cm");
        int itemsCraft = StatsUtil.totalSection(stats, "minecraft:crafted");
        int blocksMined = StatsUtil.totalSection(stats, "minecraft:mined");

        int playtimeMin = playtimeTk / TICKS_PER_MINUTE;
        int hours = playtimeMin / 60;
        int mins = playtimeMin % 60;
        double distKm = distWalk / 100_000.0; // сантиметры → км

        sender.sendMessage("§6§l[PlayerStats] §r§f" + name);
        sender.sendMessage(" §7Время игры:    §f" + hours + "ч " + mins + "м");
        sender.sendMessage(" §7Смертей:       §f" + deaths);
        sender.sendMessage(" §7Прыжков:       §f" + jumps);
        sender.sendMessage(" §7Пройдено:      §f" + String.format("%.2f", distKm) + " км");
        sender.sendMessage(" §7Убито мобов:   §f" + killed);
        sender.sendMessage(" §7Убито игроков: §f" + playerKills);
        sender.sendMessage(" §7Урон нанесён:  §f" + dmgDealt);
        sender.sendMessage(" §7Урон получен:  §f" + dmgTaken);
        sender.sendMessage(" §7Блоков добыто: §f" + blocksMined);
        sender.sendMessage(" §7Скрафчено:     §f" + itemsCraft);
        return true;
    }

    /**
     * /statsreload
     * Принудительно перечитывает статистику всех игроков из файлов.
     */
    private boolean cmdStatsReload(CommandSender sender) {
        reloadRuntimeConfig();
        sender.sendMessage("§eПерезагрузка статистики...");
        statsManager.preloadAllStatsAsync(() -> sender.sendMessage("§a[Stats] Статистика перезагружена."));
        return true;
    }

    /**
     * /statsonline
     * Список онлайн-игроков с UUID.
     */
    private boolean cmdStatsOnline(CommandSender sender) {
        List<UUID> online = statsManager.getOnlinePlayerIds();
        if (online.isEmpty()) {
            sender.sendMessage("§7Нет онлайн-игроков.");
            return true;
        }
        sender.sendMessage("§6[Stats] §fОнлайн: §a" + online.size());
        for (UUID uuid : online) {
            String name = statsManager.getPlayerName(uuid);
            sender.sendMessage("  §f" + name + " §8(" + uuid + ")");
        }
        return true;
    }

    /**
     * /statstop <minecraft:ключ> [лимит]
     * Топ игроков по выбранному ключу статистики.
     */
    private boolean cmdStatsTop(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("§eИспользование: §f/statstop <minecraft:ключ> [лимит]");
            return true;
        }

        String statKey = args[0];
        int limit = getDefaultTopLimit();
        if (args.length >= 2) {
            try {
                limit = Math.min(Math.max(1, Integer.parseInt(args[1])), getMaxTopLimit());
            } catch (NumberFormatException e) {
                sender.sendMessage("§cНекорректный лимит: §f" + args[1]);
                return true;
            }
        }

        final String key = statKey;
        final int lim = limit;

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            Map<UUID, JsonObject> cache = statsManager.getStatsCache();
            List<Map.Entry<UUID, JsonObject>> entries = new ArrayList<>(cache.entrySet());
            entries.sort((a, b) -> {
                int av = StatsUtil.getAnyStat(a.getValue(), key);
                int bv = StatsUtil.getAnyStat(b.getValue(), key);
                return Integer.compare(bv, av);
            });

            int max = Math.min(lim, entries.size());
            List<String> lines = new ArrayList<>(max + 2);
            lines.add("§6[Stats] §fТоп §e" + max + " §fпо §e" + key + "§f:");
            for (int i = 0; i < max; i++) {
                UUID uuid = entries.get(i).getKey();
                int value = StatsUtil.getAnyStat(entries.get(i).getValue(), key);
                String name = statsManager.getPlayerName(uuid);
                lines.add(" §7" + (i + 1) + ". §f" + name + " §8— §a" + value);
            }

            Bukkit.getScheduler().runTask(this, () -> lines.forEach(sender::sendMessage));
        });
        return true;
    }

    // =========================================================================
    // Tab-complete
    // =========================================================================

    private List<String> tabCompletePlayers(String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase();
        List<String> result = new ArrayList<>();
        for (String name : statsManager.getAllKnownNames()) {
            if (name.toLowerCase().startsWith(prefix)) result.add(name);
        }
        return result;
    }

    private List<String> tabCompleteTop(String[] args) {
        if (args.length != 1) return List.of();
        // Популярные ключи
        List<String> suggestions = List.of(
                "minecraft:deaths",
                "minecraft:jump",
                "minecraft:play_time",
                "minecraft:mob_kills",
                "minecraft:player_kills",
                "minecraft:damage_dealt",
                "minecraft:damage_taken",
                "minecraft:walk_one_cm",
                "minecraft:fall_one_cm");
        String prefix = args[0].toLowerCase();
        return suggestions.stream().filter(s -> s.startsWith(prefix)).toList();
    }

    private List<String> tabCompletePlayerStatsApi(String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return List.of("help", "reload", "status", "synclog").stream()
                    .filter(s -> s.startsWith(prefix))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("synclog")) {
            String prefix = args[1].toLowerCase();
            return List.of("on", "off").stream()
                    .filter(s -> s.startsWith(prefix))
                    .toList();
        }
        return List.of();
    }

    // =========================================================================
    // Хелперы
    // =========================================================================

    /**
     * Резолвит UUID игрока: сначала онлайн, потом кэш имён, потом Bukkit.getOfflinePlayer.
     */
    private UUID resolvePlayerUUID(String nameOrUUID) {
        // Попробуем как UUID напрямую
        try {
            return UUID.fromString(nameOrUUID);
        } catch (IllegalArgumentException ignored) {
        }

        // Имя → UUID из кэша
        UUID cached = statsManager.getUUID(nameOrUUID);
        if (cached != null) return cached;

        // Поиск через Bukkit (медленно, но как последний шанс)
        OfflinePlayer op = Bukkit.getOfflinePlayer(nameOrUUID);
        if (op != null && op.hasPlayedBefore()) return op.getUniqueId();

        return null;
    }

    private void startWebServer() {
        int port = getConfig().getInt("web.port", 8080);
        if (!isValidPort(port)) {
            getLogger().severe("Некорректный web-порт: " + port + ". Web-сервер не запущен.");
            return;
        }

        String bindStr = getConfig().getString("web.bind-address", "0.0.0.0");
        int maxPlayers = Math.max(0, getConfig().getInt("web.max-response-players", 0));
        int maxTop = Math.max(1, getConfig().getInt("web.max-top-results", 20));
        boolean corsEnabled = getConfig().getBoolean("web.cors.enabled", false);
        String corsOrigin = getConfig().getString("web.cors.allow-origin", "*");

        boolean rlEnabled = getConfig().getBoolean("web.rate-limit.enabled", false);
        int rlRequests = Math.max(1, getConfig().getInt("web.rate-limit.requests-per-window", 60));
        long rlWindowMs = Math.max(1000L, getConfig().getLong("web.rate-limit.window-seconds", 60) * 1000L);

        InetAddress bindAddress = resolveBindAddress(bindStr);
        WebServer.Settings settings = new WebServer.Settings(
                bindAddress, maxPlayers, maxTop, corsEnabled, corsOrigin, rlEnabled, rlRequests, rlWindowMs);

        webServer = new WebServer(statsManager, historyManager, getLogger(), settings);

        // Генерируем fallback-порты: port+1, port+2, ... port+9
        int[] fallbacks = new int[9];
        for (int i = 0; i < fallbacks.length; i++) fallbacks[i] = port + i + 1;

        webBoundPort = webServer.start(port, fallbacks);
        if (webBoundPort == -1) {
            webServer = null;
            getLogger()
                    .severe("Не удалось занять ни один порт из диапазона "
                            + port + "–" + (port + fallbacks.length)
                            + ". Web API не запущен. Измените web.port в config.yml.");
        } else {
            if (webBoundPort != port) {
                getLogger()
                        .warning("Порт " + port + " был занят. Web API запущен на резервном порту " + webBoundPort
                                + ".");
            }
            getLogger().info("Web API запущен на " + bindAddress.getHostAddress() + ":" + webBoundPort);
        }
    }

    private void reloadRuntimeConfig() {
        reloadConfig();
        historyManager.updateMaxPointsPerPlayer(getHistoryMaxPointsPerPlayer());
        StatsUtil.setStatsFolder(resolveStatsFolder());
        restartStatsAutoUpdate();
        restartWebServer();
    }

    private void restartStatsAutoUpdate() {
        if (autoUpdateTask != null) {
            autoUpdateTask.cancel();
            autoUpdateTask = null;
        }

        int intervalTicks = 20 * getStatsUpdateIntervalSeconds();
        if (intervalTicks > 0) {
            autoUpdateTask = Bukkit.getScheduler()
                    .runTaskTimer(this, statsManager::updateAllOnlinePlayers, intervalTicks, intervalTicks);
        } else {
            getLogger().warning("stats.update-interval-seconds <= 0: авто-обновление статистики отключено.");
        }
    }

    private void restartWebServer() {
        if (webServer != null) {
            webServer.stop();
            webServer = null;
            webBoundPort = -1;
        }

        if (getConfig().getBoolean("web.enabled", true)) {
            startWebServer();
        } else {
            getLogger().info("Web API отключён в конфиге.");
        }
    }

    private File resolveStatsFolder() {
        String customFolder =
                getConfigString("stats.folder", "stats-folder", "").trim();
        if (!customFolder.isEmpty()) {
            File candidate = new File(customFolder);
            if (!candidate.isAbsolute()) {
                candidate = new File(getServer().getWorldContainer(), customFolder);
            }
            if (candidate.isDirectory()) {
                getLogger().info("Stats-папка: " + candidate.getAbsolutePath());
                return candidate;
            }
            getLogger().warning("stats-folder не найден: " + candidate.getAbsolutePath());
        }

        String worldName = getConfigString("stats.world", "stats-world", "world");
        File statsDir = findStatsDirByWorld(worldName);
        if (statsDir != null) {
            getLogger().info("Stats-папка мира: " + statsDir.getAbsolutePath());
            return statsDir;
        }

        getLogger().warning("Не удалось найти папку stats/. Статистика недоступна.");
        return null;
    }

    private File findStatsDirByWorld(String worldName) {
        if (worldName != null && !worldName.isBlank()) {
            var world = Bukkit.getWorld(worldName);
            if (world != null) {
                File stats = new File(world.getWorldFolder(), "stats");
                if (stats.isDirectory()) return stats;
            }
        }
        for (var world : Bukkit.getWorlds()) {
            File stats = new File(world.getWorldFolder(), "stats");
            if (stats.isDirectory()) return stats;
        }
        return null;
    }

    private boolean isValidPort(int port) {
        return port > 0 && port <= 65535;
    }

    int getStatsUpdateIntervalSeconds() {
        return getConfigInt("stats.update-interval-seconds", "update-interval-seconds", 60);
    }

    public StatsHistoryManager getHistoryManager() {
        return historyManager;
    }

    private int getHistoryMaxPointsPerPlayer() {
        return Math.max(1, getConfigInt("history.max-points-per-player", null, 2880));
    }

    boolean isSyncUpdateLoggingEnabled() {
        return getConfigBoolean("stats.log-sync-updates", "log-sync-updates", false);
    }

    private void setSyncUpdateLoggingEnabled(boolean enabled) {
        getConfig().set("stats.log-sync-updates", enabled);
        saveConfig();
    }

    private int getDefaultTopLimit() {
        int max = getMaxTopLimit();
        int value = getConfigInt("commands.default-top-limit", null, 10);
        return Math.min(Math.max(1, value), max);
    }

    private int getMaxTopLimit() {
        return Math.max(1, getConfigInt("commands.max-top-limit", null, 50));
    }

    private String getConfigString(String primaryPath, String legacyPath, String defaultValue) {
        if (getConfig().contains(primaryPath)) {
            return getConfig().getString(primaryPath, defaultValue);
        }
        return getConfig().getString(legacyPath, defaultValue);
    }

    private int getConfigInt(String primaryPath, String legacyPath, int defaultValue) {
        if (getConfig().contains(primaryPath)) {
            return getConfig().getInt(primaryPath, defaultValue);
        }
        return legacyPath == null ? defaultValue : getConfig().getInt(legacyPath, defaultValue);
    }

    private boolean getConfigBoolean(String primaryPath, String legacyPath, boolean defaultValue) {
        if (getConfig().contains(primaryPath)) {
            return getConfig().getBoolean(primaryPath, defaultValue);
        }
        return getConfig().getBoolean(legacyPath, defaultValue);
    }

    private InetAddress resolveBindAddress(String address) {
        try {
            return InetAddress.getByName(address);
        } catch (UnknownHostException e) {
            getLogger().log(Level.WARNING, "Некорректный bind-address «" + address + "», использую 0.0.0.0");
            try {
                return InetAddress.getByName("0.0.0.0");
            } catch (UnknownHostException ignored) {
                return InetAddress.getLoopbackAddress();
            }
        }
    }
}

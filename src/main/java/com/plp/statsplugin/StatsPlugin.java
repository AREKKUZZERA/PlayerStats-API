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

public class StatsPlugin extends JavaPlugin {

    private StatsManager statsManager;
    private WebServer webServer;

    // Тики в секунду → минутах: 1 тик = 1/20 сек = 1/1200 мин
    private static final int TICKS_PER_MINUTE = 1200;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        StatsUtil.setLogger(getLogger());
        StatsUtil.setStatsFolder(resolveStatsFolder());

        statsManager = new StatsManager(this);
        Bukkit.getPluginManager().registerEvents(statsManager, this);

        statsManager.preloadAllStatsAsync();

        int intervalTicks = 20 * getConfig().getInt("update-interval-seconds", 60);
        if (intervalTicks > 0) {
            Bukkit.getScheduler()
                    .runTaskTimer(this, statsManager::updateAllOnlinePlayers, intervalTicks, intervalTicks);
        } else {
            getLogger().warning("update-interval-seconds <= 0: авто-обновление статистики отключено.");
        }

        boolean webEnabled = getConfig().getBoolean("web.enabled", true);
        if (webEnabled) {
            startWebServer();
        } else {
            getLogger().info("Web API отключён в конфиге.");
        }

        getLogger().info("PlayerStatsAPI v" + getPluginMeta().getVersion() + " включён.");
    }

    @Override
    public void onDisable() {
        if (webServer != null) {
            webServer.stop();
        }
        getLogger().info("PlayerStatsAPI v" + getPluginMeta().getVersion() + " отключён.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        return switch (cmd.getName().toLowerCase()) {
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
            case "stat", "stats" -> tabCompletePlayers(args);
            case "statstop" -> tabCompleteTop(args);
            default -> List.of();
        };
    }

    // =========================================================================
    // Команды
    // =========================================================================

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
        int limit = 10;
        if (args.length >= 2) {
            try {
                limit = Math.min(Math.max(1, Integer.parseInt(args[1])), 50);
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

        webServer = new WebServer(statsManager, getLogger(), settings);

        // Генерируем fallback-порты: port+1, port+2, ... port+9
        int[] fallbacks = new int[9];
        for (int i = 0; i < fallbacks.length; i++) fallbacks[i] = port + i + 1;

        int boundPort = webServer.start(port, fallbacks);
        if (boundPort == -1) {
            getLogger()
                    .severe("Не удалось занять ни один порт из диапазона "
                            + port + "–" + (port + fallbacks.length)
                            + ". Web API не запущен. Измените web.port в config.yml.");
        } else {
            if (boundPort != port) {
                getLogger()
                        .warning("Порт " + port + " был занят. Web API запущен на резервном порту " + boundPort + ".");
            }
            getLogger().info("Web API запущен на " + bindAddress.getHostAddress() + ":" + boundPort);
        }
    }

    private File resolveStatsFolder() {
        String customFolder = getConfig().getString("stats-folder", "").trim();
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

        String worldName = getConfig().getString("stats-world", "world");
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

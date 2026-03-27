package com.plp.statsplugin;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.logging.Level;

public class StatsPlugin extends JavaPlugin {

    private StatsManager statsManager;
    private WebServer webServer;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        StatsUtil.setLogger(getLogger());
        StatsUtil.setStatsFolder(resolveStatsFolder());

        statsManager = new StatsManager(this);
        Bukkit.getPluginManager().registerEvents(statsManager, this);

        // Асинхронная предзагрузка статистики всех известных игроков
        statsManager.preloadAllStatsAsync();

        // Периодическое обновление статистики онлайн-игроков
        int intervalTicks = 20 * getConfig().getInt("update-interval-seconds", 60);
        if (intervalTicks > 0) {
            Bukkit.getScheduler().runTaskTimer(
                    this,
                    statsManager::updateAllOnlinePlayers,
                    intervalTicks,
                    intervalTicks
            );
        } else {
            getLogger().warning("update-interval-seconds <= 0: авто-обновление статистики отключено.");
        }

        // Web API
        boolean webEnabled = getConfig().getBoolean("web.enabled", true);
        if (webEnabled) {
            startWebServer();
        } else {
            getLogger().info("Web API отключён в конфиге.");
        }

        getLogger().info("PlayerStatsAPI v" + getDescription().getVersion() + " включён.");
    }

    @Override
    public void onDisable() {
        if (webServer != null) {
            webServer.stop();
        }
        getLogger().info("PlayerStatsAPI отключён.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("stat")) return false;

        if (args.length != 2) {
            sender.sendMessage("Использование: /stat <игрок> <minecraft:ключ>");
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            sender.sendMessage("Игрок не найден или оффлайн.");
            return true;
        }

        String statKey = args[1];
        int value = statsManager.getStat(target.getUniqueId(), statKey);
        sender.sendMessage("Статистика " + target.getName() + ": " + statKey + " = " + value);
        return true;
    }

    // -------------------------------------------------------------------------

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

        InetAddress bindAddress = resolveBindAddress(bindStr);
        WebServer.Settings settings = new WebServer.Settings(bindAddress, maxPlayers, maxTop, corsEnabled, corsOrigin);

        webServer = new WebServer(statsManager, getLogger(), settings);
        webServer.start(port);
        getLogger().info("Web API запущен на " + bindAddress.getHostAddress() + ":" + port);
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
        // Фолбэк: первый мир с папкой stats
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

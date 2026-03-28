package com.plp.statsplugin;

import com.google.gson.JsonObject;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class StatsManager implements Listener {

    private final StatsPlugin plugin;
    private final Logger log;

    /** uuid → полный JSON со статистикой */
    private final ConcurrentMap<UUID, JsonObject> statsCache = new ConcurrentHashMap<>();

    /** Двусторонний маппинг: имя (в нижнем регистре) ↔ uuid */
    private final ConcurrentMap<String, UUID> nameToUuid = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, String> uuidToName = new ConcurrentHashMap<>();

    /**
     * UUID онлайн-игроков.
     * Используем keySet ConcurrentHashMap вместо Map<UUID, Boolean> — семантически точнее.
     */
    private final Set<UUID> onlineSet = ConcurrentHashMap.newKeySet();

    public StatsManager(StatsPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    // =========================================================================
    // Предзагрузка при старте
    // =========================================================================

    public void preloadAllStatsAsync() {
        OfflinePlayer[] offline = Bukkit.getOfflinePlayers();
        if (offline.length == 0) {
            log.info("Нет сохранённых игроков для предзагрузки.");
            return;
        }

        List<UUID> uuids = new ArrayList<>(offline.length);
        for (OfflinePlayer p : offline) {
            if (p == null || p.getUniqueId() == null) continue;
            uuids.add(p.getUniqueId());
            cacheName(p.getUniqueId(), p.getName());
        }

        log.info("Предзагрузка статистики: " + uuids.size() + " игроков...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> loadStats(uuids, "предзагрузка"));
    }

    // =========================================================================
    // Периодическое обновление онлайн-игроков
    // =========================================================================

    public void updateAllOnlinePlayers() {
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        if (online.isEmpty()) return;

        List<UUID> uuids = new ArrayList<>(online.size());
        for (Player p : online) {
            uuids.add(p.getUniqueId());
            onlineSet.add(p.getUniqueId());
            cacheName(p.getUniqueId(), p.getName());
        }

        // Убираем из onlineSet тех, кто уже не онлайн
        onlineSet.retainAll(new HashSet<>(uuids));

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> loadStats(uuids, "обновление онлайн"));
    }

    // =========================================================================
    // События
    // =========================================================================

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        onlineSet.add(p.getUniqueId());
        cacheName(p.getUniqueId(), p.getName());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> reloadOne(p.getUniqueId()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        onlineSet.remove(p.getUniqueId());
        // Финальное обновление кэша после выхода
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> reloadOne(p.getUniqueId()));
    }

    // =========================================================================
    // Публичное API
    // =========================================================================

    public int getStat(UUID uuid, String statKey) {
        JsonObject obj = statsCache.get(uuid);
        return obj == null ? 0 : StatsUtil.getAnyStat(obj, statKey);
    }

    public JsonObject getFullStats(UUID uuid) {
        return statsCache.getOrDefault(uuid, new JsonObject());
    }

    /** Возвращает неизменяемое представление кэша. */
    public Map<UUID, JsonObject> getStatsCache() {
        return Collections.unmodifiableMap(statsCache);
    }

    public UUID getUUID(String name) {
        if (name == null || name.isBlank()) return null;
        return nameToUuid.get(name.toLowerCase());
    }

    public String getPlayerName(UUID uuid) {
        return uuid == null ? "Unknown" : uuidToName.getOrDefault(uuid, "Unknown");
    }

    /** Все известные имена игроков (для tab-complete). */
    public Collection<String> getAllKnownNames() {
        return Collections.unmodifiableCollection(uuidToName.values());
    }

    public List<UUID> getOnlinePlayerIds() {
        return new ArrayList<>(onlineSet);
    }

    public Set<UUID> getOnlinePlayerIdSet() {
        return Collections.unmodifiableSet(onlineSet);
    }

    // =========================================================================
    // Внутренние методы
    // =========================================================================

    private void reloadOne(UUID uuid) {
        JsonObject stats = StatsUtil.readStats(uuid);
        if (stats != null) {
            statsCache.put(uuid, stats);
        } else {
            statsCache.remove(uuid);
        }
    }

    private void loadStats(List<UUID> uuids, String label) {
        long start = System.currentTimeMillis();
        int loaded = 0;

        for (UUID uuid : uuids) {
            JsonObject stats = StatsUtil.readStats(uuid);
            if (stats != null) {
                statsCache.put(uuid, stats);
                loaded++;
            } else {
                statsCache.remove(uuid);
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("[Sync] " + label + " — загружено " + loaded + "/" + uuids.size()
                + " записей за " + elapsed + " мс.");
    }

    private void cacheName(UUID uuid, String name) {
        if (uuid == null || name == null || name.isBlank()) return;
        uuidToName.put(uuid, name);
        nameToUuid.put(name.toLowerCase(), uuid);
    }
}

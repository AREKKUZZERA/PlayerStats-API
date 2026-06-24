package com.plp.statsplugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class StatsHistoryManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final File file;
    private final Logger logger;
    private int maxPointsPerPlayer;
    private final Map<UUID, PlayerActivity> players = new HashMap<>();
    private boolean dirty;

    public StatsHistoryManager(File dataFolder, Logger logger, int maxPointsPerPlayer) {
        this.file = new File(dataFolder, "history.json");
        this.logger = logger;
        this.maxPointsPerPlayer = Math.max(1, maxPointsPerPlayer);
        load();
    }

    public synchronized void recordOfflineMetadata(UUID uuid, String name, long lastSeenMillis) {
        if (uuid == null) return;
        PlayerActivity activity = player(uuid, name);
        if (lastSeenMillis > 0) {
            activity.lastSeen = Math.max(activity.lastSeen, lastSeenMillis);
            activity.firstSeen = Math.min(activity.firstSeen, lastSeenMillis);
        }
        dirty = true;
    }

    public synchronized void recordJoin(UUID uuid, String name) {
        if (uuid == null) return;
        long now = System.currentTimeMillis();
        PlayerActivity activity = player(uuid, name);
        activity.lastJoin = now;
        activity.lastSeen = now;
        activity.currentSessionStart = now;
        save();
    }

    public synchronized void recordQuit(UUID uuid, String name) {
        if (uuid == null) return;
        long now = System.currentTimeMillis();
        PlayerActivity activity = player(uuid, name);
        activity.lastQuit = now;
        activity.lastSeen = now;
        if (activity.currentSessionStart > 0) {
            activity.lastSessionMillis = Math.max(0, now - activity.currentSessionStart);
            activity.currentSessionStart = 0;
        }
        save();
    }

    public synchronized void recordSnapshot(UUID uuid, String name, JsonObject stats, boolean online) {
        if (uuid == null || stats == null) return;

        long now = System.currentTimeMillis();
        long playtimeTicks = StatsUtil.getStatInSection(stats, "minecraft:custom", "minecraft:play_time");
        PlayerActivity activity = player(uuid, name);

        long deltaTicks = 0;
        boolean reset = false;
        if (activity.lastPlaytimeTicks >= 0 && playtimeTicks >= activity.lastPlaytimeTicks) {
            deltaTicks = playtimeTicks - activity.lastPlaytimeTicks;
        } else if (activity.lastPlaytimeTicks >= 0) {
            reset = true;
        }

        activity.lastPlaytimeTicks = playtimeTicks;
        if (online) {
            activity.lastSeen = now;
            if (activity.currentSessionStart == 0) {
                activity.currentSessionStart = now;
            }
        }

        if (deltaTicks > 0 || activity.points.isEmpty() || reset) {
            PlaytimePoint point = new PlaytimePoint();
            point.timestamp = now;
            point.playtimeTicks = playtimeTicks;
            point.deltaTicks = Math.max(0, deltaTicks);
            activity.points.add(point);
            trimPoints(activity.points);
        }

        if (deltaTicks > 0) {
            activity.totalDeltaTicks += deltaTicks;
            mergeLong(activity.dailyPlaytimeTicks, dayKey(now), deltaTicks);
            mergeLong(activity.heatmapTicks, heatmapKey(now), deltaTicks);
        }

        dirty = true;
    }

    public synchronized void flush() {
        if (!dirty) return;
        if (save()) {
            dirty = false;
        }
    }

    public synchronized void updateMaxPointsPerPlayer(int maxPointsPerPlayer) {
        this.maxPointsPerPlayer = Math.max(1, maxPointsPerPlayer);
        boolean trimmed = false;
        for (PlayerActivity activity : players.values()) {
            int before = activity.points.size();
            trimPoints(activity.points);
            trimmed = trimmed || activity.points.size() != before;
        }
        if (trimmed) {
            dirty = true;
            flush();
        }
    }

    public synchronized JsonObject activityJson(UUID uuid) {
        PlayerActivity activity = players.get(uuid);
        if (activity == null) return null;

        JsonObject out = baseActivityJson(uuid, activity);
        out.add("daily_playtime_ticks", mapToObject(activity.dailyPlaytimeTicks));
        out.add("heatmap_ticks", mapToObject(activity.heatmapTicks));
        return out;
    }

    public synchronized JsonObject activitySummaryJson(UUID uuid) {
        PlayerActivity activity = players.get(uuid);
        return activity == null ? null : baseActivityJson(uuid, activity);
    }

    public synchronized JsonArray playtimeSeriesJson(UUID uuid, int limit) {
        PlayerActivity activity = players.get(uuid);
        JsonArray arr = new JsonArray();
        if (activity == null) return arr;

        int count = limit > 0 ? Math.min(limit, activity.points.size()) : activity.points.size();
        int start = Math.max(0, activity.points.size() - count);
        for (int i = start; i < activity.points.size(); i++) {
            PlaytimePoint point = activity.points.get(i);
            JsonObject o = new JsonObject();
            o.addProperty("timestamp", point.timestamp);
            o.addProperty("timestamp_iso", iso(point.timestamp));
            o.addProperty("playtime_ticks", point.playtimeTicks);
            o.addProperty("delta_ticks", point.deltaTicks);
            arr.add(o);
        }
        return arr;
    }

    public synchronized JsonArray topActivityJson(long sinceMillis, int limit) {
        List<Map.Entry<UUID, PlayerActivity>> entries = new ArrayList<>(players.entrySet());
        entries.sort(
                (a, b) -> Long.compare(deltaSince(b.getValue(), sinceMillis), deltaSince(a.getValue(), sinceMillis)));

        JsonArray arr = new JsonArray();
        int max = Math.min(Math.max(0, limit), entries.size());
        for (int i = 0; i < max; i++) {
            UUID uuid = entries.get(i).getKey();
            PlayerActivity activity = entries.get(i).getValue();
            long delta = deltaSince(activity, sinceMillis);

            JsonObject o = baseActivityJson(uuid, activity);
            o.addProperty("rank", i + 1);
            o.addProperty("delta_ticks", delta);
            o.addProperty("delta_seconds", delta / 20);
            arr.add(o);
        }
        return arr;
    }

    public synchronized JsonObject globalHeatmapJson() {
        Map<String, Long> heatmap = new TreeMap<>();
        Map<String, Long> daily = new TreeMap<>();
        for (PlayerActivity activity : players.values()) {
            mergeAll(heatmap, activity.heatmapTicks);
            mergeAll(daily, activity.dailyPlaytimeTicks);
        }

        JsonObject out = new JsonObject();
        out.add("heatmap_ticks", mapToObject(heatmap));
        out.add("daily_playtime_ticks", mapToObject(daily));
        return out;
    }

    private JsonObject baseActivityJson(UUID uuid, PlayerActivity activity) {
        JsonObject out = new JsonObject();
        out.addProperty("uuid", uuid.toString());
        out.addProperty("name", activity.name == null ? "Unknown" : activity.name);
        out.addProperty("first_seen", activity.firstSeen);
        out.addProperty("first_seen_iso", iso(activity.firstSeen));
        out.addProperty("last_seen", activity.lastSeen);
        out.addProperty("last_seen_iso", iso(activity.lastSeen));
        out.addProperty("last_join", activity.lastJoin);
        out.addProperty("last_join_iso", iso(activity.lastJoin));
        out.addProperty("last_quit", activity.lastQuit);
        out.addProperty("last_quit_iso", iso(activity.lastQuit));
        out.addProperty("last_session_millis", activity.lastSessionMillis);
        out.addProperty("active_now_millis", activeNowMillis(activity));
        out.addProperty("playtime_ticks", Math.max(0, activity.lastPlaytimeTicks));
        out.addProperty("total_recorded_delta_ticks", activity.totalDeltaTicks);
        return out;
    }

    private PlayerActivity player(UUID uuid, String name) {
        PlayerActivity activity = players.computeIfAbsent(uuid, ignored -> {
            PlayerActivity created = new PlayerActivity();
            created.firstSeen = System.currentTimeMillis();
            created.lastPlaytimeTicks = -1;
            return created;
        });
        if (name != null && !name.isBlank()) {
            activity.name = name;
        }
        return activity;
    }

    private long activeNowMillis(PlayerActivity activity) {
        return activity.currentSessionStart > 0
                ? Math.max(0, System.currentTimeMillis() - activity.currentSessionStart)
                : 0;
    }

    private long deltaSince(PlayerActivity activity, long sinceMillis) {
        long total = 0;
        for (PlaytimePoint point : activity.points) {
            if (point.timestamp >= sinceMillis) {
                total += Math.max(0, point.deltaTicks);
            }
        }
        return total;
    }

    private void trimPoints(List<PlaytimePoint> points) {
        int extra = points.size() - maxPointsPerPlayer;
        if (extra > 0) {
            points.subList(0, extra).clear();
        }
    }

    private void load() {
        if (!file.isFile()) return;
        try (FileReader reader = new FileReader(file)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null || !root.has("players") || !root.get("players").isJsonObject()) return;

            JsonObject playerRoot = root.getAsJsonObject("players");
            for (Map.Entry<String, com.google.gson.JsonElement> entry : playerRoot.entrySet()) {
                UUID uuid = UUID.fromString(entry.getKey());
                PlayerActivity activity = GSON.fromJson(entry.getValue(), PlayerActivity.class);
                if (activity != null) {
                    normalize(activity);
                    players.put(uuid, activity);
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Не удалось прочитать history.json: " + e.getMessage());
        }
    }

    private boolean save() {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                logger.warning("Не удалось создать папку " + parent.getAbsolutePath());
                return false;
            }

            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            JsonObject playerRoot = new JsonObject();
            for (Map.Entry<UUID, PlayerActivity> entry : players.entrySet()) {
                playerRoot.add(entry.getKey().toString(), GSON.toJsonTree(entry.getValue()));
            }
            root.add("players", playerRoot);

            try (FileWriter writer = new FileWriter(file)) {
                GSON.toJson(root, writer);
            }
            return true;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Не удалось сохранить history.json: " + e.getMessage());
            return false;
        }
    }

    private void normalize(PlayerActivity activity) {
        if (activity.points == null) activity.points = new ArrayList<>();
        if (activity.dailyPlaytimeTicks == null) activity.dailyPlaytimeTicks = new TreeMap<>();
        if (activity.heatmapTicks == null) activity.heatmapTicks = new TreeMap<>();
        if (activity.lastPlaytimeTicks == 0 && activity.points.isEmpty()) activity.lastPlaytimeTicks = -1;
    }

    private static void mergeLong(Map<String, Long> map, String key, long value) {
        map.merge(key, value, Long::sum);
    }

    private static void mergeAll(Map<String, Long> target, Map<String, Long> source) {
        for (Map.Entry<String, Long> entry : source.entrySet()) {
            mergeLong(target, entry.getKey(), entry.getValue());
        }
    }

    private static JsonObject mapToObject(Map<String, Long> map) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, Long> entry : new TreeMap<>(map).entrySet()) {
            out.addProperty(entry.getKey(), entry.getValue());
        }
        return out;
    }

    private static String dayKey(long epochMillis) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC)
                .format(DAY_FORMAT);
    }

    private static String heatmapKey(long epochMillis) {
        var dt = Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC);
        return dt.getDayOfWeek().getValue() + "-" + dt.getHour();
    }

    private static String iso(long epochMillis) {
        return epochMillis > 0 ? Instant.ofEpochMilli(epochMillis).toString() : null;
    }

    private static final class PlayerActivity {
        String name = "Unknown";
        long firstSeen;
        long lastSeen;
        long lastJoin;
        long lastQuit;
        long lastSessionMillis;
        long currentSessionStart;
        long lastPlaytimeTicks = -1;
        long totalDeltaTicks;
        List<PlaytimePoint> points = new ArrayList<>();
        Map<String, Long> dailyPlaytimeTicks = new TreeMap<>();
        Map<String, Long> heatmapTicks = new TreeMap<>();
    }

    private static final class PlaytimePoint {
        long timestamp;
        long playtimeTicks;
        long deltaTicks;
    }
}

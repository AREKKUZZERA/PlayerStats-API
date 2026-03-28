package com.plp.statsplugin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

public class WebServer {

    private static final Gson GSON = new Gson();

    private final StatsManager statsManager;
    private final Logger logger;
    private final Settings settings;

    private HttpServer server;

    public WebServer(StatsManager statsManager, Logger logger, Settings settings) {
        this.statsManager = statsManager;
        this.logger = logger;
        this.settings = settings;
    }

    public void start(int port) {
        try {
            server = HttpServer.create(new InetSocketAddress(settings.bindAddress(), port), 0);

            server.createContext("/moss/players/", this::handlePlayerByUUID);
            server.createContext("/moss/players", this::handleAllPlayers);
            server.createContext("/moss/player/", this::handlePlayerByName);
            server.createContext("/moss/online", this::handleOnline);
            server.createContext("/moss/summary", this::handleSummary);
            server.createContext("/moss/top/", this::handleTop);

            // Фиксированный пул потоков; 4 хватает при типичной нагрузке
            server.setExecutor(Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "PlayerStatsAPI-http");
                t.setDaemon(true);
                return t;
            }));
            server.start();
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Не удалось запустить Web-сервер.", e);
        }
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    // =========================================================================
    // GET /moss/players[?limit=N&offset=N]
    // =========================================================================
    private void handleAllPlayers(HttpExchange ex) throws IOException {
        if (!isGet(ex)) {
            send405(ex);
            return;
        }

        int limit = resolveIntParam(ex, "limit", settings.maxResponsePlayers());
        int offset = resolveIntParam(ex, "offset", 0);
        offset = Math.max(0, offset);

        Set<UUID> online = statsManager.getOnlinePlayerIdSet();
        List<UUID> uuids = sortedUuids();

        int total = uuids.size();
        int from = Math.min(offset, total);
        int to = (limit > 0) ? Math.min(from + limit, total) : total;

        JsonArray arr = new JsonArray();
        for (int i = from; i < to; i++) {
            UUID uuid = uuids.get(i);
            arr.add(playerEntry(uuid, online.contains(uuid), true));
        }

        JsonObject out = new JsonObject();
        out.addProperty("total", total);
        out.addProperty("limit", limit);
        out.addProperty("offset", offset);
        out.add("players", arr);

        sendJson(ex, 200, GSON.toJson(out));
    }

    // =========================================================================
    // GET /moss/players/<uuid>
    // =========================================================================
    private void handlePlayerByUUID(HttpExchange ex) throws IOException {
        if (!isGet(ex)) {
            send405(ex);
            return;
        }

        String uuidStr = lastPathSegment(ex);
        if (uuidStr == null) {
            sendText(ex, 400, "Usage: /moss/players/<uuid>");
            return;
        }

        try {
            UUID uuid = UUID.fromString(uuidStr);
            sendJson(ex, 200, GSON.toJson(statsManager.getFullStats(uuid)));
        } catch (IllegalArgumentException e) {
            sendText(ex, 400, "Invalid UUID");
        }
    }

    // =========================================================================
    // GET /moss/player/<name>
    // =========================================================================
    private void handlePlayerByName(HttpExchange ex) throws IOException {
        if (!isGet(ex)) {
            send405(ex);
            return;
        }

        String raw = lastPathSegment(ex);
        if (raw == null) {
            sendText(ex, 400, "Usage: /moss/player/<name>");
            return;
        }

        String name = URLDecoder.decode(raw, StandardCharsets.UTF_8);
        if (!isValidName(name)) {
            sendText(ex, 400, "Invalid player name");
            return;
        }

        UUID uuid = statsManager.getUUID(name);
        if (uuid == null) {
            sendText(ex, 404, "Player not found");
            return;
        }

        sendJson(ex, 200, GSON.toJson(statsManager.getFullStats(uuid)));
    }

    // =========================================================================
    // GET /moss/online
    // =========================================================================
    private void handleOnline(HttpExchange ex) throws IOException {
        if (!isGet(ex)) {
            send405(ex);
            return;
        }

        List<UUID> online = statsManager.getOnlinePlayerIds();
        online.sort(Comparator.comparing(UUID::toString));

        JsonArray arr = new JsonArray();
        for (UUID uuid : online) arr.add(playerEntry(uuid, true, true));

        sendJson(ex, 200, GSON.toJson(arr));
    }

    // =========================================================================
    // GET /moss/summary
    // =========================================================================
    private void handleSummary(HttpExchange ex) throws IOException {
        if (!isGet(ex)) {
            send405(ex);
            return;
        }

        long jumps = 0, deaths = 0, playtime = 0, mined = 0, crafted = 0;

        for (JsonObject player : statsManager.getStatsCache().values()) {
            try {
                var statRoot = player.getAsJsonObject("stats");
                if (statRoot == null) continue;

                var custom = statRoot.getAsJsonObject("minecraft:custom");
                if (custom != null) {
                    jumps += longVal(custom, "minecraft:jump");
                    deaths += longVal(custom, "minecraft:deaths");
                    playtime += longVal(custom, "minecraft:play_time");
                }
                var minedObj = statRoot.getAsJsonObject("minecraft:mined");
                var craftedObj = statRoot.getAsJsonObject("minecraft:crafted");
                if (minedObj != null)
                    for (var k : minedObj.keySet()) mined += minedObj.get(k).getAsLong();
                if (craftedObj != null)
                    for (var k : craftedObj.keySet())
                        crafted += craftedObj.get(k).getAsLong();
            } catch (Exception ignored) {
            }
        }

        JsonObject totals = new JsonObject();
        totals.addProperty("total_jumps", jumps);
        totals.addProperty("total_deaths", deaths);
        totals.addProperty("total_playtime", playtime);
        totals.addProperty("blocks_mined", mined);
        totals.addProperty("items_crafted", crafted);

        JsonObject out = new JsonObject();
        out.addProperty("players", statsManager.getStatsCache().size());
        out.add("totals", totals);

        sendJson(ex, 200, GSON.toJson(out));
    }

    // =========================================================================
    // GET /moss/top/<stat_key>[?section=<section>&limit=N]
    // GET /moss/top/<section>/<stat_key>[?limit=N]
    // =========================================================================
    private void handleTop(HttpExchange ex) throws IOException {
        if (!isGet(ex)) {
            send405(ex);
            return;
        }

        String[] parts = ex.getRequestURI().getPath().split("/");
        // parts: ["", "moss", "top", ...]
        if (parts.length < 4) {
            sendText(ex, 400, "Usage: /moss/top/<stat_key>");
            return;
        }

        String section = null;
        String statKey;

        if (parts.length >= 5) {
            // /moss/top/<section>/<stat_key>
            section = URLDecoder.decode(parts[3], StandardCharsets.UTF_8).trim();
            statKey = URLDecoder.decode(parts[4], StandardCharsets.UTF_8).trim();
        } else {
            // /moss/top/<stat_key>[?section=...]
            statKey = URLDecoder.decode(parts[3], StandardCharsets.UTF_8).trim();
            String qs = getParam(ex, "section");
            if (qs != null && !qs.isBlank()) section = qs.trim();
        }

        if (!isValidStatKey(statKey)) {
            sendText(ex, 400, "Invalid stat key");
            return;
        }
        if (section != null && !isValidStatKey(section)) {
            sendText(ex, 400, "Invalid section");
            return;
        }

        final String finalSection = section;

        // Сортировка по убыванию значения
        List<Map.Entry<UUID, JsonObject>> entries =
                new ArrayList<>(statsManager.getStatsCache().entrySet());
        entries.sort((a, b) -> {
            int av = topValue(a.getValue(), statKey, finalSection);
            int bv = topValue(b.getValue(), statKey, finalSection);
            return Integer.compare(bv, av);
        });

        int limit = resolveIntParam(ex, "limit", settings.maxTopResults());
        if (limit <= 0) limit = settings.maxTopResults();
        int max = Math.min(limit, entries.size());

        JsonArray arr = new JsonArray();
        for (int i = 0; i < max; i++) {
            UUID uuid = entries.get(i).getKey();
            int value = topValue(entries.get(i).getValue(), statKey, finalSection);

            JsonObject o = new JsonObject();
            o.addProperty("rank", i + 1);
            o.addProperty("uuid", uuid.toString());
            o.addProperty("name", statsManager.getPlayerName(uuid));
            o.addProperty("value", value);
            o.addProperty("stat_key", statKey);
            if (finalSection != null) o.addProperty("section", finalSection);
            arr.add(o);
        }

        sendJson(ex, 200, GSON.toJson(arr));
    }

    // =========================================================================
    // Хелперы HTTP
    // =========================================================================

    private void sendJson(HttpExchange ex, int code, String body) throws IOException {
        send(ex, code, body, "application/json; charset=UTF-8");
    }

    private void sendText(HttpExchange ex, int code, String body) throws IOException {
        send(ex, code, body, "text/plain; charset=UTF-8");
    }

    private void send405(HttpExchange ex) throws IOException {
        sendText(ex, 405, "Method Not Allowed");
    }

    private void send(HttpExchange ex, int code, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        if (settings.corsEnabled()) {
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", settings.corsAllowOrigin());
        }
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    // =========================================================================
    // Хелперы данных
    // =========================================================================

    private JsonObject playerEntry(UUID uuid, boolean online, boolean includeStats) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", uuid.toString());
        o.addProperty("name", statsManager.getPlayerName(uuid));
        o.addProperty("online", online);
        if (includeStats) o.add("stats", statsManager.getFullStats(uuid));
        return o;
    }

    private int topValue(JsonObject root, String statKey, String section) {
        return (section == null)
                ? StatsUtil.getAnyStat(root, statKey)
                : StatsUtil.getStatInSection(root, section, statKey);
    }

    private List<UUID> sortedUuids() {
        List<UUID> list = new ArrayList<>(statsManager.getStatsCache().keySet());
        list.sort(Comparator.comparing(UUID::toString));
        return list;
    }

    private static long longVal(JsonObject obj, String key) {
        if (!obj.has(key)) return 0;
        try {
            return obj.get(key).getAsLong();
        } catch (Exception e) {
            return 0;
        }
    }

    // =========================================================================
    // Хелперы запроса
    // =========================================================================

    private boolean isGet(HttpExchange ex) {
        return ex.getRequestMethod().equalsIgnoreCase("GET");
    }

    private String lastPathSegment(HttpExchange ex) {
        String[] parts = ex.getRequestURI().getPath().split("/");
        return (parts.length >= 4) ? parts[parts.length - 1] : null;
    }

    private String getParam(HttpExchange ex, String key) {
        String query = ex.getRequestURI().getQuery();
        if (query == null || query.isBlank()) return null;
        for (String part : query.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && kv[0].equalsIgnoreCase(key)) {
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /**
     * Читает числовой параметр из query string; возвращает defaultValue при ошибке.
     */
    private int resolveIntParam(HttpExchange ex, String key, int defaultValue) {
        String val = getParam(ex, key);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private boolean isValidStatKey(String key) {
        return key != null && !key.isBlank() && key.length() <= 128 && key.matches("[a-z0-9_:\\-.]+");
    }

    private boolean isValidName(String name) {
        return name != null && !name.isBlank() && name.length() <= 16 && name.matches("[A-Za-z0-9_]+");
    }

    // =========================================================================
    // Settings record
    // =========================================================================

    public record Settings(
            InetAddress bindAddress,
            int maxResponsePlayers,
            int maxTopResults,
            boolean corsEnabled,
            String corsAllowOrigin) {}
}

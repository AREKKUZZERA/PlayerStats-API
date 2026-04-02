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
    private final RateLimiter rateLimiter; // null = отключён

    private HttpServer server;

    public WebServer(StatsManager statsManager, Logger logger, Settings settings) {
        this.statsManager = statsManager;
        this.logger = logger;
        this.settings = settings;
        this.rateLimiter = settings.rateLimitEnabled()
                ? new RateLimiter(settings.rateLimitRequests(), settings.rateLimitWindowMillis())
                : null;
    }

    /**
     * Attempts to bind on {@code port}. If that port is busy and
     * {@code fallbackPorts} are provided, tries each in order.
     *
     * @return the port actually bound, or -1 if every candidate failed
     */
    public int start(int port, int... fallbackPorts) {
        int[] candidates = new int[1 + fallbackPorts.length];
        candidates[0] = port;
        System.arraycopy(fallbackPorts, 0, candidates, 1, fallbackPorts.length);

        for (int candidate : candidates) {
            try {
                server = HttpServer.create(new InetSocketAddress(settings.bindAddress(), candidate), 0);

                server.createContext("/moss/players/", this::handlePlayerByUUID);
                server.createContext("/moss/players", this::handleAllPlayers);
                server.createContext("/moss/player/", this::handlePlayerByName);
                server.createContext("/moss/online", this::handleOnline);
                server.createContext("/moss/summary", this::handleSummary);
                server.createContext("/moss/top/", this::handleTop);
                server.createContext("/moss/health", this::handleHealth);

                server.setExecutor(Executors.newFixedThreadPool(4, r -> {
                    Thread t = new Thread(r, "PlayerStatsAPI-http");
                    t.setDaemon(true);
                    return t;
                }));
                server.start();
                return candidate;
            } catch (java.net.BindException e) {
                logger.warning("Порт " + candidate + " занят, пробую следующий...");
                server = null;
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Не удалось запустить Web-сервер на порту " + candidate + ".", e);
                server = null;
            }
        }
        return -1;
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    private boolean handlePreflight(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            return false;
        }

        var headers = ex.getResponseHeaders();
        if (settings.corsEnabled()) {
            headers.set("Access-Control-Allow-Origin", settings.corsAllowOrigin());
            headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            headers.set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Request-Id");
            headers.set("Access-Control-Max-Age", "86400");
            headers.set("Vary", "Origin");
        }

        ex.sendResponseHeaders(204, -1);
        ex.close();
        return true;
    }
    // =========================================================================
    // Rate-limit guard  — вызывается в начале каждого обработчика
    // =========================================================================

    /**
     * Проверяет лимит. Если лимит превышен — отправляет 429 и возвращает true
     * (обработчик должен сразу выйти). Иначе — добавляет заголовки и возвращает false.
     */
    private boolean rateLimited(HttpExchange ex) throws IOException {
        if (rateLimiter == null) return false;

        String ip = ex.getRemoteAddress().getAddress().getHostAddress();
        boolean allowed = rateLimiter.tryAcquire(ip);

        // Стандартные RateLimit-заголовки (draft-ietf-httpapi-ratelimit-headers)
        ex.getResponseHeaders().set("X-RateLimit-Limit", String.valueOf(rateLimiter.getMaxRequests()));
        ex.getResponseHeaders().set("X-RateLimit-Remaining", String.valueOf(rateLimiter.remainingRequests(ip)));
        ex.getResponseHeaders().set("X-RateLimit-Reset", String.valueOf(rateLimiter.windowResetMillis(ip) / 1000L));

        if (!allowed) {
            ex.getResponseHeaders().set("Retry-After", String.valueOf(rateLimiter.getWindowMillis() / 1000L));
            sendText(ex, 429, "Too Many Requests");
            return true;
        }
        return false;
    }

    // =========================================================================
    // GET /moss/players[?limit=N&offset=N&stats=true]
    // =========================================================================
    private void handleAllPlayers(HttpExchange ex) throws IOException {
        if (handlePreflight(ex)) return;

        if (!isGet(ex)) {
            send405(ex);
            return;
        }
        if (rateLimited(ex)) return;

        int limit = resolveIntParam(ex, "limit", settings.maxResponsePlayers());
        int offset = Math.max(0, resolveIntParam(ex, "offset", 0));
        boolean includeStats = "true".equalsIgnoreCase(getParam(ex, "stats"));

        Set<UUID> online = statsManager.getOnlinePlayerIdSet();
        List<UUID> uuids = sortedUuids();

        int total = uuids.size();
        int from = Math.min(offset, total);
        int to = (limit > 0) ? Math.min(from + limit, total) : total;

        JsonArray arr = new JsonArray();
        for (int i = from; i < to; i++) {
            UUID uuid = uuids.get(i);
            arr.add(playerEntry(uuid, online.contains(uuid), includeStats));
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
        if (handlePreflight(ex)) return;

        if (!isGet(ex)) {
            send405(ex);
            return;
        }
        if (rateLimited(ex)) return;

        String uuidStr = lastPathSegment(ex);
        if (uuidStr == null || uuidStr.isBlank()) {
            sendText(ex, 400, "Usage: /moss/players/<uuid>");
            return;
        }

        try {
            UUID uuid = UUID.fromString(uuidStr);
            boolean online = statsManager.getOnlinePlayerIdSet().contains(uuid);
            sendJson(ex, 200, GSON.toJson(playerEntry(uuid, online, true)));
        } catch (IllegalArgumentException e) {
            sendText(ex, 400, "Invalid UUID");
        }
    }

    // =========================================================================
    // GET /moss/player/<name>
    // =========================================================================
    private void handlePlayerByName(HttpExchange ex) throws IOException {
        if (handlePreflight(ex)) return;
        if (!isGet(ex)) {
            send405(ex);
            return;
        }
        if (rateLimited(ex)) return;

        String raw = lastPathSegment(ex);
        if (raw == null || raw.isBlank()) {
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

        boolean online = statsManager.getOnlinePlayerIdSet().contains(uuid);
        sendJson(ex, 200, GSON.toJson(playerEntry(uuid, online, true)));
    }

    // =========================================================================
    // GET /moss/online
    // =========================================================================
    private void handleOnline(HttpExchange ex) throws IOException {
        if (handlePreflight(ex)) return;
        if (!isGet(ex)) {
            send405(ex);
            return;
        }
        if (rateLimited(ex)) return;

        List<UUID> online = statsManager.getOnlinePlayerIds();
        online.sort(Comparator.comparing(UUID::toString));

        JsonArray arr = new JsonArray();
        for (UUID uuid : online) arr.add(playerEntry(uuid, true, false));

        JsonObject out = new JsonObject();
        out.addProperty("count", online.size());
        out.add("players", arr);

        sendJson(ex, 200, GSON.toJson(out));
    }

    // =========================================================================
    // GET /moss/summary
    // =========================================================================
    private void handleSummary(HttpExchange ex) throws IOException {
        if (handlePreflight(ex)) return;

        if (!isGet(ex)) {
            send405(ex);
            return;
        }
        if (rateLimited(ex)) return;

        long jumps = 0, deaths = 0, playtime = 0, mined = 0, crafted = 0, playerKills = 0, mobKills = 0, dmgDealt = 0;

        for (JsonObject player : statsManager.getStatsCache().values()) {
            jumps += StatsUtil.getStatInSection(player, "minecraft:custom", "minecraft:jump");
            deaths += StatsUtil.getStatInSection(player, "minecraft:custom", "minecraft:deaths");
            playtime += StatsUtil.getStatInSection(player, "minecraft:custom", "minecraft:play_time");
            playerKills += StatsUtil.getStatInSection(player, "minecraft:custom", "minecraft:player_kills");
            mobKills += StatsUtil.getStatInSection(player, "minecraft:custom", "minecraft:mob_kills");
            dmgDealt += StatsUtil.getStatInSection(player, "minecraft:custom", "minecraft:damage_dealt");
            mined += StatsUtil.totalSection(player, "minecraft:mined");
            crafted += StatsUtil.totalSection(player, "minecraft:crafted");
        }

        JsonObject totals = new JsonObject();
        totals.addProperty("total_jumps", jumps);
        totals.addProperty("total_deaths", deaths);
        totals.addProperty("total_playtime_ticks", playtime);
        totals.addProperty("total_player_kills", playerKills);
        totals.addProperty("total_mob_kills", mobKills);
        totals.addProperty("total_damage_dealt", dmgDealt);
        totals.addProperty("blocks_mined", mined);
        totals.addProperty("items_crafted", crafted);

        JsonObject out = new JsonObject();
        out.addProperty("players_total", statsManager.getStatsCache().size());
        out.addProperty("players_online", statsManager.getOnlinePlayerIdSet().size());
        out.add("totals", totals);

        sendJson(ex, 200, GSON.toJson(out));
    }

    // =========================================================================
    // GET /moss/top/<stat_key>[?section=<s>&limit=N]
    // GET /moss/top/<section>/<stat_key>[?limit=N]
    // =========================================================================
    private void handleTop(HttpExchange ex) throws IOException {
        if (handlePreflight(ex)) return;
        if (!isGet(ex)) {
            send405(ex);
            return;
        }
        if (rateLimited(ex)) return;

        String[] parts = ex.getRequestURI().getPath().split("/");
        if (parts.length < 4) {
            sendText(ex, 400, "Usage: /moss/top/<stat_key> or /moss/top/<section>/<stat_key>");
            return;
        }

        String section;
        String statKey;

        if (parts.length >= 5) {
            section = URLDecoder.decode(parts[3], StandardCharsets.UTF_8).trim();
            statKey = URLDecoder.decode(parts[4], StandardCharsets.UTF_8).trim();
        } else {
            statKey = URLDecoder.decode(parts[3], StandardCharsets.UTF_8).trim();
            String qs = getParam(ex, "section");
            section = (qs != null && !qs.isBlank()) ? qs.trim() : null;
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

        Set<UUID> online = statsManager.getOnlinePlayerIdSet();
        JsonArray arr = new JsonArray();
        for (int i = 0; i < max; i++) {
            UUID uuid = entries.get(i).getKey();
            int value = topValue(entries.get(i).getValue(), statKey, finalSection);

            JsonObject o = new JsonObject();
            o.addProperty("rank", i + 1);
            o.addProperty("uuid", uuid.toString());
            o.addProperty("name", statsManager.getPlayerName(uuid));
            o.addProperty("online", online.contains(uuid));
            o.addProperty("value", value);
            o.addProperty("stat_key", statKey);
            if (finalSection != null) o.addProperty("section", finalSection);
            arr.add(o);
        }

        sendJson(ex, 200, GSON.toJson(arr));
    }

    // =========================================================================
    // GET /moss/health  — служебный эндпоинт без rate limit
    // =========================================================================
    private void handleHealth(HttpExchange ex) throws IOException {
        if (handlePreflight(ex)) return;
        if (!isGet(ex)) {
            send405(ex);
            return;
        }

        JsonObject out = new JsonObject();
        out.addProperty("status", "ok");
        out.addProperty("players_cached", statsManager.getStatsCache().size());
        out.addProperty("players_online", statsManager.getOnlinePlayerIdSet().size());
        out.addProperty("rate_limit", rateLimiter != null);
        if (rateLimiter != null) {
            out.addProperty(
                    "rate_limit_rps", (long) rateLimiter.getMaxRequests() * 1000 / rateLimiter.getWindowMillis());
        }

        sendJson(ex, 200, GSON.toJson(out));
    }

    // =========================================================================
    // HTTP helpers
    // =========================================================================

    private void sendJson(HttpExchange ex, int code, String body) throws IOException {
        send(ex, code, body, "application/json; charset=UTF-8");
    }

    private void sendText(HttpExchange ex, int code, String body) throws IOException {
        send(ex, code, body, "text/plain; charset=UTF-8");
    }

    private void send405(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Allow", "GET, OPTIONS");
        sendText(ex, 405, "Method Not Allowed");
    }

    private void send(HttpExchange ex, int code, String body, String contentType) throws IOException {
        var headers = ex.getResponseHeaders();
        headers.set("Content-Type", contentType);

        if (settings.corsEnabled()) {
            headers.set("Access-Control-Allow-Origin", settings.corsAllowOrigin());
            headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            headers.set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Request-Id");
            headers.set("Vary", "Origin");
        }

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, bytes.length);

        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    // =========================================================================
    // Data helpers
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

    // =========================================================================
    // Request helpers
    // =========================================================================

    private boolean isGet(HttpExchange ex) {
        return ex.getRequestMethod().equalsIgnoreCase("GET");
    }

    private String lastPathSegment(HttpExchange ex) {
        String path = ex.getRequestURI().getPath();
        String[] parts = path.split("/");
        if (parts.length < 4) return null;
        String segment = parts[parts.length - 1];
        return segment.isBlank() ? null : segment;
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

    private int resolveIntParam(HttpExchange ex, String key, int defaultValue) {
        String val = getParam(ex, key);
        if (val == null) return defaultValue;
        try {
            return Math.max(0, Integer.parseInt(val));
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
            String corsAllowOrigin,
            boolean rateLimitEnabled,
            int rateLimitRequests,
            long rateLimitWindowMillis) {

        /** Обратная совместимость — без rate limit. */
        public Settings(
                InetAddress bindAddress,
                int maxResponsePlayers,
                int maxTopResults,
                boolean corsEnabled,
                String corsAllowOrigin) {
            this(bindAddress, maxResponsePlayers, maxTopResults, corsEnabled, corsAllowOrigin, false, 60, 60_000L);
        }
    }
}

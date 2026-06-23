package autismclient.util;

import autismclient.AutismClientAddon;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class AutismDupeRadar {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String PROVIDER_ID = "dupedb";
    private static final String PROVIDER_LABEL = "DupeDB";
    private static final String BASE_URL = "https://dupedb.net";
    private static final String CLIENT_ID = "catr-dupedb-radar";
    private static final long INDEX_CACHE_MS = TimeUnit.HOURS.toMillis(6);
    private static final long FINDINGS_CACHE_MS = TimeUnit.MINUTES.toMillis(30);
    private static final int PLUGIN_INDEX_CACHE_VERSION = 4;
    private static final int PLUGIN_INDEX_PAGE_SIZE = 100;
    private static final int PLUGIN_INDEX_MAX_PAGES = 10;
    private static final int FINDINGS_CACHE_VERSION = 6;
    private static final int SERVER_FINDINGS_CACHE_VERSION = 6;
    private static final String EXPLOIT_SEARCH_STATUSES = "working,verified,patched,unverified";
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 12000;
    private static final int MAX_FINDINGS_PER_PLUGIN = 50;
    private static final List<String> SERVER_SCALAR_KEYS = List.of(
        "serverIp", "server_ip", "server", "domain", "address", "ip",
        "serverAddress", "server_address", "host", "hostname",
        "affectedServer", "affected_server", "affectedServerIp", "affected_server_ip",
        "sightingServerIp", "sighting_server_ip", "sightingIp", "sighting_ip",
        "reportedServerIp", "reported_server_ip", "reportedServer", "reported_server"
    );
    private static final List<String> SERVER_COLLECTION_KEYS = List.of(
        "serverIps", "server_ips", "servers", "domains", "addresses", "ips",
        "affectedServers", "affected_servers", "affectedServerIps", "affected_server_ips",
        "sightedServerIps", "sighted_server_ips", "sightedIps", "sighted_ips",
        "pluginServerIps", "plugin_server_ips", "plugin_server_ips_pending",
        "reportedServerIps", "reported_server_ips"
    );
    private static final List<String> SERVER_OBJECT_VALUE_KEYS = List.of(
        "ip", "serverIp", "server_ip", "server", "domain", "address", "host", "hostname",
        "sightingServerIp", "sighting_server_ip", "sightingIp", "sighting_ip"
    );
    private static final AtomicInteger WORKER_ID = new AtomicInteger();
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "AUTISM-DupeRadar-" + WORKER_ID.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    });
    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static final Object CACHE_LOCK = new Object();
    private static final Path CACHE_FILE = AutismClientAddon.FOLDER.toPath().resolve("providers").resolve("dupedb-cache.json");
    private static ProviderCache cache = loadCache();
    private static volatile RadarState state = initialState();

    private AutismDupeRadar() {
    }

    public static RadarState state() {
        ensureUserFromCache();
        return state;
    }

    public static String providerLabel() {
        return PROVIDER_LABEL;
    }

    public static String sourceUrl() {
        return BASE_URL;
    }

    public static void login() {
        RadarState current = state;
        if (current.authenticating()) return;
        if (current.authenticated()) return;
        int generation = GENERATION.incrementAndGet();
        state = state.withBusy(true, false, "Opening DupeDB login...", null);
        CompletableFuture.runAsync(() -> runLogin(generation), EXECUTOR);
    }

    public static void logout() {
        GENERATION.incrementAndGet();
        synchronized (CACHE_LOCK) {
            cache.accessToken = null;
            cache.refreshToken = null;
            cache.username = null;
            cache.userUpdatedAt = 0L;
            saveCacheLocked();
        }
        state = state.withAuth(false, null).withBusy(false, false, "Logged out.", null).withMatches(List.of(), 0, false);
    }

    public static void refreshUser() {
        int generation = GENERATION.incrementAndGet();
        state = state.withBusy(true, false, "Refreshing DupeDB user...", null);
        CompletableFuture.runAsync(() -> {
            try {
                String token = ensureAccessToken();
                if (token == null || token.isBlank()) {
                    publish(generation, state.withAuth(false, null).withBusy(false, false, "Login required.", "No DupeDB token saved."));
                    return;
                }
                String username = fetchUserAuthorized();
                synchronized (CACHE_LOCK) {
                    cache.username = username;
                    cache.userUpdatedAt = System.currentTimeMillis();
                    saveCacheLocked();
                }
                publish(generation, state.withAuth(true, username).withBusy(false, false, "User refreshed.", null));
            } catch (Exception ex) {
                publish(generation, state.withBusy(false, false, "User refresh failed.", cleanError(ex)));
            }
        }, EXECUTOR);
    }

    public static void checkServer(List<RadarPluginSnapshot> plugins, boolean forceRefresh) {
        checkServer(plugins, RadarServerSnapshot.EMPTY, forceRefresh);
    }

    public static void checkServer(List<RadarPluginSnapshot> plugins, RadarServerSnapshot server, boolean forceRefresh) {
        List<RadarPluginSnapshot> snapshots = plugins == null ? List.of() : List.copyOf(plugins);
        RadarServerSnapshot serverSnapshot = server == null ? RadarServerSnapshot.EMPTY : server;
        int generation = GENERATION.incrementAndGet();
        state = state.withBusy(false, true, forceRefresh ? "Refreshing radar data..." : "Checking server...", null)
            .withPluginCount(snapshots.size());
        CompletableFuture.runAsync(() -> runCheck(generation, snapshots, serverSnapshot, forceRefresh), EXECUTOR);
    }

    public static void clearServerResults() {
        GENERATION.incrementAndGet();
        state = state.withMatches(List.of(), 0, false).withBusy(false, false, "No server checked.", null);
    }

    public static void cancel() {
        GENERATION.incrementAndGet();
        state = state.withBusy(false, false, "Canceled.", null);
    }

    public static String copyReport() {
        RadarState current = state();
        StringBuilder sb = new StringBuilder();
        sb.append("Radar - ").append(PROVIDER_LABEL).append('\n');
        sb.append("Status: ").append(current.status()).append('\n');
        if (current.error() != null && !current.error().isBlank()) {
            sb.append("Error: ").append(current.error()).append('\n');
        }
        sb.append("Plugins checked: ").append(current.detectedPluginCount()).append('\n');
        sb.append("Matches: ").append(current.matches().size()).append("\n\n");
        if (current.matches().isEmpty()) {
            sb.append("No matches.\n");
            return sb.toString();
        }
        for (RadarMatch match : current.matches()) {
            sb.append("- ").append(match.displayLabel())
                .append(" [").append(match.matchConfidence()).append(" ")
                .append(match.matchSource().label()).append("]")
                .append(" findings=").append(match.findings().size())
                .append(" source=").append(match.sourceUrl())
                .append('\n');
        }
        return sb.toString();
    }

    public static void open(String url) {
        if (url == null || url.isBlank()) return;
        try {
            AutismLinks.open(safeFindingUrl(url, ""));
        } catch (Exception ignored) {
        }
    }

    private static RadarState initialState() {
        ProviderCache loaded = cache;
        boolean authed = loaded != null && loaded.accessToken != null && !loaded.accessToken.isBlank();
        String username = loaded == null ? null : loaded.username;
        return new RadarState(PROVIDER_ID, PROVIDER_LABEL, authed, false, false, username,
            authed ? "Ready." : "Login required.", null, List.of(), 0, 0L, false);
    }

    private static void ensureUserFromCache() {
        ProviderCache loaded = cache;
        if (loaded == null) return;
        boolean authed = loaded.accessToken != null && !loaded.accessToken.isBlank();
        RadarState current = state;
        if (current.authenticated() != authed || !Objects.equals(current.username(), loaded.username)) {
            state = current.withAuth(authed, loaded.username);
        }
    }

    private static void runLogin(int generation) {
        String verifier = randomUrlToken(48);
        String challenge = codeChallenge(verifier);
        String stateToken = randomUrlToken(24);
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            server.setSoTimeout(120000);
            int port = server.getLocalPort();
            String redirect = "http://127.0.0.1:" + port + "/callback";
            String loginUrl = BASE_URL
                + "/api/oauth/authorize?response_type=code"
                + "&client_id=" + url(CLIENT_ID)
                + "&redirect_uri=" + url(redirect)
                + "&code_challenge=" + url(challenge)
                + "&code_challenge_method=S256"
                + "&state=" + url(stateToken);
            AutismLinks.open(loginUrl);
            publish(generation, state.withBusy(true, false, "Waiting for browser login...", null));

            try (Socket socket = server.accept()) {
                socket.setSoTimeout(5000);
                BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String request = reader.readLine();
                String code = null;
                String returnedState = null;
                if (request != null) {
                    int start = request.indexOf(' ');
                    int end = request.indexOf(' ', start + 1);
                    if (start >= 0 && end > start) {
                        URI uri = URI.create("http://127.0.0.1" + request.substring(start + 1, end));
                        Map<String, String> params = parseQuery(uri.getRawQuery());
                        code = params.get("code");
                        returnedState = params.get("state");
                    }
                }
                String responseBody = "<html><body style=\"font-family:sans-serif;background:#111;color:#eee\"><h2>AUTISM Radar login received.</h2>You can close this tab.</body></html>";
                byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
                OutputStream out = socket.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + responseBytes.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(responseBytes);
                out.flush();

                if (code == null || code.isBlank()) throw new IOException("No authorization code returned.");
                if (!stateToken.equals(returnedState)) throw new IOException("OAuth state did not match.");

                TokenResponse token = exchangeCode(code, verifier, redirect);
                String username = fetchUser(token.accessToken);
                synchronized (CACHE_LOCK) {
                    cache.accessToken = token.accessToken;
                    cache.refreshToken = token.refreshToken;
                    cache.username = username;
                    cache.userUpdatedAt = System.currentTimeMillis();
                    saveCacheLocked();
                }
                publish(generation, state.withAuth(true, username).withBusy(false, false, "Logged in.", null));
            }
        } catch (Exception ex) {
            publish(generation, state.withBusy(false, false, "Login failed.", cleanError(ex)));
        }
    }

    private static void runCheck(int generation, List<RadarPluginSnapshot> plugins, RadarServerSnapshot server, boolean forceRefresh) {
        try {
            String token = ensureAccessToken();
            if (token == null || token.isBlank()) {
                publish(generation, state.withAuth(false, null).withBusy(false, false, "Login required.", "Log in to DupeDB before checking."));
                return;
            }

            List<String> index = getPluginIndex(token, forceRefresh);
            Map<String, String> indexByKey = new TreeMap<>();
            for (String plugin : index) {
                String key = normalize(plugin);
                if (!key.isBlank()) indexByKey.putIfAbsent(key, plugin);
            }

            Map<String, RadarFindingHit> dedupedHits = new LinkedHashMap<>();
            List<RadarPluginSnapshot> orderedPlugins = new ArrayList<>(plugins);
            orderedPlugins.removeIf(snapshot -> snapshot == null || !snapshot.isPluginIdentity());
            orderedPlugins.sort(Comparator
                .comparingInt((RadarPluginSnapshot snapshot) -> findPluginMatch(snapshot, indexByKey) == null ? 1 : 0)
                .thenComparing(RadarPluginSnapshot::displayName, String.CASE_INSENSITIVE_ORDER));
            for (RadarPluginSnapshot snapshot : orderedPlugins) {
                try {
                    List<PluginMatch> pluginMatches = findPluginMatches(snapshot, indexByKey);
                    Map<String, RadarFinding> mergedFindings = new LinkedHashMap<>();
                    PluginMatch matchedRecord = null;
                    for (PluginMatch pluginMatch : pluginMatches) {
                        List<RadarFinding> variantFindings = getFindings(token, pluginMatch.providerPlugin, forceRefresh);
                        if (variantFindings.isEmpty()) continue;
                        if (matchedRecord == null) matchedRecord = pluginMatch;
                        for (RadarFinding finding : variantFindings) {
                            String key = findingDedupeKey(finding);
                            RadarFinding existing = mergedFindings.get(key);
                            if (existing == null || finding.sortTimestampMs() > existing.sortTimestampMs()) {
                                mergedFindings.put(key, finding);
                            }
                        }
                    }
                    if (mergedFindings.isEmpty()) {
                        PluginSearchResult direct = getDirectPluginFindings(token, snapshot, forceRefresh);
                        if (direct.match != null && !direct.findings.isEmpty()) {
                            matchedRecord = direct.match;
                            for (RadarFinding finding : direct.findings) {
                                String key = findingDedupeKey(finding);
                                RadarFinding existing = mergedFindings.get(key);
                                if (existing == null || finding.sortTimestampMs() > existing.sortTimestampMs()) {
                                    mergedFindings.put(key, finding);
                                }
                            }
                        }
                    }
                    if (matchedRecord == null || mergedFindings.isEmpty()) continue;
                    List<RadarFinding> findings = new ArrayList<>(mergedFindings.values());
                    findings.sort(Comparator.comparingLong(RadarFinding::sortTimestampMs).reversed());
                    RadarMatch match = new RadarMatch(snapshot.displayName(), matchedRecord.providerPlugin, matchedRecord.confidence,
                        matchedRecord.source, matchedRecord.score, snapshot.displayName(), BASE_URL + "/plugins/" + urlPath(matchedRecord.providerPlugin),
                        PROVIDER_LABEL, findings, newestTimestamp(findings));
                    addDedupedHits(dedupedHits, match);
                } catch (Exception ignored) {

                }
            }

            for (RadarServerIdentity identity : server.identities()) {
                if (identity == null || identity.value().isBlank()) continue;
                List<RadarFinding> findings = getServerFindings(token, identity, forceRefresh);
                if (findings.isEmpty()) continue;
                MatchSource source = identity.ip() ? MatchSource.SERVER_IP : MatchSource.SERVER_DOMAIN;
                RadarMatch match = new RadarMatch(identity.value(), identity.value(), "Server", source, 1.0D,
                    identity.value(), BASE_URL, PROVIDER_LABEL, findings, newestTimestamp(findings));
                addDedupedHits(dedupedHits, match);
            }

            List<RadarMatch> matches = collapseHits(dedupedHits);
            matches.sort(Comparator
                .comparingLong(RadarMatch::sortTimestampMs).reversed()
                .thenComparingInt((RadarMatch match) -> matchSourceRank(match.matchSource()))
                .thenComparing(RadarMatch::detectedPlugin, String.CASE_INSENSITIVE_ORDER));
            String checkedLabel = plugins.isEmpty()
                ? "Checked server."
                : "Checked " + plugins.size() + " plugin" + (plugins.size() == 1 ? "." : "s.");
            publish(generation, state.withAuth(true, cache.username).withBusy(false, false,
                checkedLabel, null).withMatches(matches, plugins.size(), false));
        } catch (Exception ex) {
            publish(generation, state.withBusy(false, false, "Radar check failed.", cleanError(ex)).markStale());
        }
    }

    private static PluginMatch findPluginMatch(RadarPluginSnapshot snapshot, Map<String, String> indexByKey) {
        String detectedKey = normalize(snapshot.displayName());
        if (detectedKey.isBlank()) return null;
        String exact = indexByKey.get(detectedKey);
        if (exact != null) return new PluginMatch(exact, "Exact", MatchSource.PLUGIN, 1.0D);

        String detectedVersionless = AutismPluginNameMatcher.normalizeVersionless(snapshot.displayName());
        if (!detectedVersionless.isBlank()) {
            for (String providerPlugin : indexByKey.values()) {
                if (detectedVersionless.equals(AutismPluginNameMatcher.normalizeVersionless(providerPlugin))) {
                    return new PluginMatch(providerPlugin, "Exact", MatchSource.PLUGIN, 0.98D);
                }
            }
        }

        Set<String> aliases = new LinkedHashSet<>();
        aliases.add(snapshot.canonicalKey());
        aliases.add(snapshot.displayName());
        for (String channel : snapshot.channels()) {
            if (channel == null) continue;
            String clean = channel.replaceFirst("^\\?\\s*", "").replaceFirst("\\s*\\[.*$", "").trim();
            int colon = clean.indexOf(':');
            if (colon > 0) aliases.add(clean.substring(0, colon));
        }
        for (String alias : aliases) {
            String match = indexByKey.get(normalize(alias));
            if (match != null) return new PluginMatch(match, "Alias", MatchSource.PLUGIN, 0.94D);
        }

        PluginMatch fuzzy = null;
        for (String providerPlugin : indexByKey.values()) {
            double score = pluginSimilarity(snapshot.displayName(), providerPlugin);
            if (score < 0.90D) continue;
            if (fuzzy == null || score > fuzzy.score) {
                fuzzy = new PluginMatch(providerPlugin, "Fuzzy", MatchSource.PLUGIN_FUZZY, score);
            }
        }
        return fuzzy;
    }

    private static List<PluginMatch> findPluginMatches(RadarPluginSnapshot snapshot, Map<String, String> indexByKey) {
        PluginMatch primary = findPluginMatch(snapshot, indexByKey);
        if (primary == null || primary.providerPlugin == null || primary.providerPlugin.isBlank()) return List.of();
        return List.of(primary);
    }

    private static void addDedupedHits(Map<String, RadarFindingHit> hits, RadarMatch match) {
        if (hits == null || match == null || match.findings() == null) return;
        for (RadarFinding finding : match.findings()) {
            if (finding == null) continue;
            String key = findingDedupeKey(finding);
            if (key.isBlank()) continue;
            RadarFindingHit next = new RadarFindingHit(match, finding);
            RadarFindingHit current = hits.get(key);
            if (current == null || isStrongerHit(next, current)) {
                hits.put(key, next);
            }
        }
    }

    private static List<RadarMatch> collapseHits(Map<String, RadarFindingHit> hits) {
        if (hits == null || hits.isEmpty()) return List.of();
        Map<String, RadarMatchBuilder> builders = new LinkedHashMap<>();
        for (RadarFindingHit hit : hits.values()) {
            RadarMatch match = hit.match;
            String key = match.matchSource().name() + "|" + normalize(match.detectedPlugin()) + "|" + normalize(match.providerPlugin());
            RadarMatchBuilder builder = builders.computeIfAbsent(key, unused -> new RadarMatchBuilder(match));
            builder.findings.add(hit.finding);
        }
        List<RadarMatch> out = new ArrayList<>();
        for (RadarMatchBuilder builder : builders.values()) {
            builder.findings.sort(Comparator.comparingLong(RadarFinding::sortTimestampMs).reversed()
                .thenComparing(RadarFinding::title, String.CASE_INSENSITIVE_ORDER));
            out.add(builder.toMatch());
        }
        return out;
    }

    private static boolean isStrongerHit(RadarFindingHit next, RadarFindingHit current) {
        int bySource = Integer.compare(matchSourceRank(next.match.matchSource()), matchSourceRank(current.match.matchSource()));
        if (bySource != 0) return bySource < 0;
        int byScore = Double.compare(next.match.matchScore(), current.match.matchScore());
        if (byScore != 0) return byScore > 0;
        return next.finding.sortTimestampMs() > current.finding.sortTimestampMs();
    }

    private static String findingDedupeKey(RadarFinding finding) {
        if (finding == null) return "";
        if (finding.id() != null && !finding.id().isBlank()) return "id:" + normalize(finding.id());
        if (finding.sourceUrl() != null && !finding.sourceUrl().isBlank()) return "url:" + finding.sourceUrl().trim().toLowerCase(Locale.ROOT);
        return "title:" + normalize(finding.title() + ":" + finding.status());
    }

    private static long newestTimestamp(List<RadarFinding> findings) {
        long best = 0L;
        if (findings != null) {
            for (RadarFinding finding : findings) {
                if (finding != null) best = Math.max(best, finding.sortTimestampMs());
            }
        }
        return best == 0L ? System.currentTimeMillis() : best;
    }

    private static int matchSourceRank(MatchSource source) {
        return switch (source == null ? MatchSource.PLUGIN_FUZZY : source) {
            case PLUGIN -> 0;
            case PLUGIN_FUZZY -> 1;
            case SERVER_DOMAIN -> 2;
            case SERVER_IP -> 3;
        };
    }

    private static double pluginSimilarity(String detected, String provider) {
        return AutismPluginNameMatcher.similarity(detected, provider);
    }

    private static List<String> getPluginIndex(String token, boolean forceRefresh) throws IOException {
        long now = System.currentTimeMillis();
        synchronized (CACHE_LOCK) {
            if (!forceRefresh && cache.pluginIndex != null && !cache.pluginIndex.isEmpty() && now - cache.pluginIndexAt <= INDEX_CACHE_MS) {
                return List.copyOf(cache.pluginIndex);
            }
        }
        Set<String> collected = new LinkedHashSet<>();
        for (int page = 1; page <= PLUGIN_INDEX_MAX_PAGES; page++) {
            JsonElement response = JsonParser.parseString(httpGetAuthorized(
                BASE_URL + "/api/plugins?page=" + page + "&limit=" + PLUGIN_INDEX_PAGE_SIZE));
            int before = collected.size();
            for (String name : parsePluginNames(response)) {
                if (name != null && !name.isBlank()) collected.add(name.trim());
            }
            if (collected.size() == before || !pluginIndexHasMore(response)) break;
        }
        List<String> names = collected.stream()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
        synchronized (CACHE_LOCK) {
            cache.pluginIndex = new ArrayList<>(names);
            cache.pluginIndexAt = now;
            cache.pluginIndexVersion = PLUGIN_INDEX_CACHE_VERSION;
            saveCacheLocked();
        }
        return names;
    }

    private static List<RadarFinding> getFindings(String token, String plugin, boolean forceRefresh) throws IOException {
        long now = System.currentTimeMillis();
        String key = normalize(plugin);
        synchronized (CACHE_LOCK) {
            CachedFindings cached = cache.findings.get(key);
            if (!forceRefresh && cached != null && now - cached.updatedAt <= FINDINGS_CACHE_MS && cached.items != null) {
                return List.copyOf(cached.items);
            }
        }
        String endpoint = pluginSearchEndpoint(plugin, "");
        String raw = httpGetAuthorized(endpoint);
        List<RadarFinding> findings = parseFindings(JsonParser.parseString(raw), plugin);
        synchronized (CACHE_LOCK) {
            CachedFindings cached = new CachedFindings();
            cached.updatedAt = now;
            cached.items = new ArrayList<>(findings);
            cache.findings.put(key, cached);
            saveCacheLocked();
        }
        return findings;
    }

    private static PluginSearchResult getDirectPluginFindings(String token, RadarPluginSnapshot snapshot, boolean forceRefresh) throws IOException {
        String queryPlugin = snapshot == null ? "" : snapshot.displayName().trim();
        if (queryPlugin.isBlank()) return PluginSearchResult.EMPTY;
        long now = System.currentTimeMillis();
        String key = "direct:" + normalize(queryPlugin);
        synchronized (CACHE_LOCK) {
            CachedFindings cached = cache.findings.get(key);
            if (!forceRefresh && cached != null && now - cached.updatedAt <= FINDINGS_CACHE_MS && cached.items != null) {
                List<RadarFinding> items = List.copyOf(cached.items);
                PluginMatch match = matchFromFindings(snapshot, items);
                return match == null ? PluginSearchResult.EMPTY : new PluginSearchResult(match, items);
            }
        }

        JsonElement response = JsonParser.parseString(httpGetAuthorized(pluginSearchEndpoint(null, queryPlugin)));
        PluginMatch match = findResponsePluginMatch(snapshot, response);
        List<RadarFinding> findings = match == null
            ? List.of()
            : parseFindings(response, match.providerPlugin, snapshot);
        synchronized (CACHE_LOCK) {
            CachedFindings cached = new CachedFindings();
            cached.updatedAt = now;
            cached.items = new ArrayList<>(findings);
            cache.findings.put(key, cached);
            saveCacheLocked();
        }
        return match == null || findings.isEmpty()
            ? PluginSearchResult.EMPTY
            : new PluginSearchResult(match, findings);
    }

    private static boolean pluginIndexHasMore(JsonElement response) {
        if (response == null || !response.isJsonObject()) return false;
        JsonElement pagination = response.getAsJsonObject().get("pagination");
        if (pagination == null || !pagination.isJsonObject()) return false;
        JsonElement hasMore = pagination.getAsJsonObject().get("hasMore");
        try {
            return hasMore != null && hasMore.getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String pluginSearchEndpoint(String plugin, String query) {
        StringBuilder endpoint = new StringBuilder(BASE_URL)
            .append("/api/exploits/search?q=").append(url(query == null ? "" : query))
            .append("&page=1");
        if (plugin != null && !plugin.isBlank()) endpoint.append("&plugin=").append(url(plugin));
        return endpoint
            .append("&status=").append(EXPLOIT_SEARCH_STATUSES)
            .append("&sort=date_submitted&order=desc&limit=").append(MAX_FINDINGS_PER_PLUGIN)
            .toString();
    }

    private static List<RadarFinding> getServerFindings(String token, RadarServerIdentity identity, boolean forceRefresh) {
        long now = System.currentTimeMillis();
        String key = (identity.ip() ? "ip:" : "domain:") + identity.value().toLowerCase(Locale.ROOT);
        synchronized (CACHE_LOCK) {
            CachedFindings cached = cache.serverFindings.get(key);
            if (!forceRefresh && cached != null && now - cached.updatedAt <= FINDINGS_CACHE_MS && cached.items != null) {
                return List.copyOf(cached.items);
            }
        }

        List<String> endpoints = List.of(
            BASE_URL + "/api/exploits/search?serverIp=" + url(identity.value())
                + "&page=1&sort=date_submitted&order=desc&limit=" + MAX_FINDINGS_PER_PLUGIN,
            BASE_URL + "/api/exploits/search?q=" + url(identity.value())
                + "&page=1&serverIp=" + url(identity.value())
                + "&sort=date_submitted&order=desc&limit=" + MAX_FINDINGS_PER_PLUGIN,
            BASE_URL + "/api/sightings/search?q=" + url(identity.value())
                + "&page=1&serverIp=" + url(identity.value())
                + "&sort=date_submitted&order=desc&limit=" + MAX_FINDINGS_PER_PLUGIN,
            BASE_URL + "/api/sightings/search?q=" + url(identity.value())
                + "&page=1&sort=date_submitted&order=desc&limit=" + MAX_FINDINGS_PER_PLUGIN
        );
        Map<String, RadarFinding> unique = new LinkedHashMap<>();
        for (String endpoint : endpoints) {
            try {
                JsonElement response = JsonParser.parseString(httpGetAuthorized(endpoint));
                mergeFindings(unique, parseServerFindings(response, identity));
                if (endpoint.contains("/api/exploits/search")) {
                    mergeFindings(unique, fetchServerFindingDetails(token, response, identity));
                }
            } catch (Exception ignored) {
            }
        }
        List<RadarFinding> findings = new ArrayList<>(unique.values());
        findings.sort(Comparator.comparingLong(RadarFinding::sortTimestampMs).reversed()
            .thenComparing(RadarFinding::title, String.CASE_INSENSITIVE_ORDER));
        synchronized (CACHE_LOCK) {
            CachedFindings cached = new CachedFindings();
            cached.updatedAt = now;
            cached.items = new ArrayList<>(findings);
            cache.serverFindings.put(key, cached);
            saveCacheLocked();
        }
        return findings;
    }

    private static void mergeFindings(Map<String, RadarFinding> unique, List<RadarFinding> findings) {
        if (unique == null || findings == null || findings.isEmpty()) return;
        for (RadarFinding finding : findings) {
            if (finding == null) continue;
            String key = findingDedupeKey(finding);
            if (key.isBlank()) key = normalize(finding.title() + ":" + finding.sourceUrl());
            RadarFinding existing = unique.get(key);
            if (existing == null || finding.sortTimestampMs() > existing.sortTimestampMs()) {
                unique.put(key, finding);
            }
        }
    }

    private static List<RadarFinding> fetchServerFindingDetails(String token, JsonElement searchResponse, RadarServerIdentity identity) {
        Set<String> ids = new LinkedHashSet<>();
        collectPotentialExploitIds(searchResponse, ids);
        if (ids.isEmpty()) return List.of();
        Map<String, RadarFinding> unique = new LinkedHashMap<>();
        int fetched = 0;
        for (String id : ids) {
            if (id == null || id.isBlank() || !looksLikeExploitId(id)) continue;
            if (fetched++ >= MAX_FINDINGS_PER_PLUGIN) break;
            try {
                JsonElement detail = JsonParser.parseString(httpGetAuthorized(BASE_URL + "/api/exploits/" + urlPath(id)));
                mergeFindings(unique, parseServerFindings(detail, identity));
            } catch (Exception ignored) {
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static void collectPotentialExploitIds(JsonElement element, Set<String> out) {
        if (element == null || element.isJsonNull() || out == null) return;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) collectPotentialExploitIds(child, out);
            return;
        }
        if (!element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject();
        String id = firstString(object, "exploitId", "exploit_id", "id", "_id", "slug", "uuid");
        if (id != null && looksLikeExploitId(id)) out.add(id.trim());
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            JsonElement value = entry.getValue();
            if (value != null && (value.isJsonArray() || value.isJsonObject())) {
                collectPotentialExploitIds(value, out);
            }
        }
    }

    private static List<String> parsePluginNames(JsonElement element) {
        List<String> out = new ArrayList<>();
        collectPluginNames(element, out);
        return out;
    }

    private static void collectPluginNames(JsonElement element, List<String> out) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) collectPluginNames(child, out);
            return;
        }
        if (!element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject();
        String name = firstString(object, "name", "plugin", "pluginName", "title");
        if (name != null && !name.isBlank()) out.add(name.trim());
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (entry.getValue() != null && (entry.getValue().isJsonArray() || entry.getValue().isJsonObject())) {
                collectPluginNames(entry.getValue(), out);
            }
        }
    }

    private static List<RadarFinding> parseFindings(JsonElement element, String plugin) {
        return parseFindings(element, plugin, null);
    }

    private static List<RadarFinding> parseFindings(JsonElement element, String plugin, RadarPluginSnapshot expectedPlugin) {
        List<JsonObject> objects = new ArrayList<>();
        collectFindingObjects(element, objects);
        Map<String, RadarFinding> unique = new LinkedHashMap<>();
        long fallbackTimestamp = System.currentTimeMillis();
        for (JsonObject object : objects) {
            PluginMatch objectMatch = expectedPlugin == null ? null : findObjectPluginMatch(expectedPlugin, object);
            if (expectedPlugin != null && objectMatch == null) continue;
            String id = firstString(object, "id", "_id", "slug", "uuid");
            String title = firstString(object, "title", "name", "summary");
            String status = normalizeStatus(firstString(object, "status", "state"));
            if (status.equals("Unknown")) {
                if (hasTruthy(object, "marked_working_at", "working_at", "verified_at")) status = "Working";
                else if (hasTruthy(object, "marked_patched_at", "patched_at")) status = "Patched";
            }
            if (title == null || title.isBlank()) continue;
            String summary = firstString(object, "description", "summary", "short_description", "details");
            String url = firstString(object, "redirectUrl", "redirect_url", "publicUrl", "public_url", "canonicalUrl", "canonical_url", "url", "source", "link");
            if ((url == null || url.isBlank()) && object.has("sources") && object.get("sources").isJsonArray()) {
                for (JsonElement source : object.getAsJsonArray("sources")) {
                    if (source != null && source.isJsonPrimitive()) {
                        String candidate = source.getAsString();
                        if (candidate != null && !candidate.isBlank()) {
                            url = candidate;
                            break;
                        }
                    }
                }
            }
            url = safeFindingUrl(url, id == null || id.isBlank() ? title : id);
            long timestamp = parseTimestampMs(object, fallbackTimestamp);
            String finalId = id == null || id.isBlank() ? normalize(title + ":" + status) : id;
            String affectedPlugin = objectMatch == null ? plugin : objectMatch.providerPlugin;
            unique.putIfAbsent(finalId, new RadarFinding(finalId, title.trim(), status, safeSummary(summary), affectedPlugin, url, PROVIDER_LABEL, timestamp, extractAffectedServers(object)));
        }
        List<RadarFinding> out = new ArrayList<>(unique.values());
        out.sort(Comparator.comparingLong(RadarFinding::sortTimestampMs).reversed()
            .thenComparing(RadarFinding::title, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private static PluginMatch findResponsePluginMatch(RadarPluginSnapshot snapshot, JsonElement response) {
        if (snapshot == null || response == null || response.isJsonNull()) return null;
        List<JsonObject> objects = new ArrayList<>();
        collectFindingObjects(response, objects);
        PluginMatch best = null;
        for (JsonObject object : objects) {
            PluginMatch candidate = findObjectPluginMatch(snapshot, object);
            if (candidate != null && (best == null || candidate.score > best.score)) best = candidate;
        }
        return best;
    }

    private static PluginMatch findObjectPluginMatch(RadarPluginSnapshot snapshot, JsonObject object) {
        if (snapshot == null || object == null) return null;
        List<String> references = AutismPluginNameMatcher.extractProviderReferences(object);
        if (references.isEmpty()) return null;

        Set<String> detectedNames = new LinkedHashSet<>();
        detectedNames.add(snapshot.displayName());
        detectedNames.add(snapshot.canonicalKey());
        for (String channel : snapshot.channels()) {
            if (channel == null) continue;
            String clean = channel.replaceFirst("^\\?\\s*", "").replaceFirst("\\s*\\[.*$", "").trim();
            int colon = clean.indexOf(':');
            if (colon > 0) detectedNames.add(clean.substring(0, colon));
        }

        String bestReference = null;
        double bestScore = 0.90D;
        for (String detected : detectedNames) {
            String candidate = AutismPluginNameMatcher.bestMatchingReference(detected, references, 0.90D);
            if (candidate == null) continue;
            double score = AutismPluginNameMatcher.similarity(detected, candidate);
            if (bestReference == null || score > bestScore) {
                bestReference = candidate;
                bestScore = score;
            }
        }
        if (bestReference == null) return null;
        boolean exact = AutismPluginNameMatcher.normalizeVersionless(snapshot.displayName())
            .equals(AutismPluginNameMatcher.normalizeVersionless(bestReference));
        return new PluginMatch(bestReference, exact ? "Exact" : "Fuzzy",
            exact ? MatchSource.PLUGIN : MatchSource.PLUGIN_FUZZY, bestScore);
    }

    private static PluginMatch matchFromFindings(RadarPluginSnapshot snapshot, List<RadarFinding> findings) {
        if (snapshot == null || findings == null || findings.isEmpty()) return null;
        List<String> references = new ArrayList<>();
        for (RadarFinding finding : findings) {
            if (finding != null && finding.affectedPlugin() != null && !finding.affectedPlugin().isBlank()) {
                references.add(finding.affectedPlugin());
            }
        }
        String best = AutismPluginNameMatcher.bestMatchingReference(snapshot.displayName(), references, 0.90D);
        if (best == null) return null;
        double score = AutismPluginNameMatcher.similarity(snapshot.displayName(), best);
        boolean exact = AutismPluginNameMatcher.normalizeVersionless(snapshot.displayName())
            .equals(AutismPluginNameMatcher.normalizeVersionless(best));
        return new PluginMatch(best, exact ? "Exact" : "Fuzzy",
            exact ? MatchSource.PLUGIN : MatchSource.PLUGIN_FUZZY, score);
    }

    private static List<RadarFinding> parseServerFindings(JsonElement element, RadarServerIdentity identity) {
        if (identity == null || identity.value().isBlank()) return List.of();
        List<JsonObject> objects = new ArrayList<>();
        collectFindingObjects(element, objects);
        Map<String, RadarFinding> unique = new LinkedHashMap<>();
        long fallbackTimestamp = System.currentTimeMillis();
        for (JsonObject object : objects) {
            String matchedServer = matchedServerIdentity(object, identity.value());
            if (matchedServer.isBlank()) continue;
            String id = firstString(object, "exploitId", "exploit_id", "id", "_id", "slug", "uuid");
            String title = firstString(object, "exploitName", "exploit_name", "title", "name", "summary");
            if (title == null || title.isBlank()) continue;
            String status = normalizeStatus(firstString(object, "status", "state", "sightingStatus", "sighting_status"));
            if (status.equals("Unknown") && object.has("isPatched")) {
                try {
                    status = object.get("isPatched").getAsInt() == 1 ? "Patched" : "Working";
                } catch (Exception ignored) {
                }
            }
            if (status.equals("Unknown")) {
                if (hasTruthy(object, "sightingPatched", "sighting_patched", "patched", "marked_patched_at", "patched_at")) status = "Patched";
                else if (hasTruthy(object, "sightingVerified", "sighting_verified", "verified")) status = "Verified";
                else if (hasTruthy(object, "marked_working_at", "working_at", "verified_at")) status = "Working";
            }
            String summary = "Matched server " + matchedServer;
            String url = firstString(object, "redirectUrl", "redirect_url", "publicUrl", "public_url", "canonicalUrl", "canonical_url", "url", "source", "link");
            url = safeFindingUrl(url, id == null || id.isBlank() ? title : id);
            long timestamp = parseTimestampMs(object, fallbackTimestamp);
            String finalId = id == null || id.isBlank() ? normalize(title + ":" + status + ":" + identity.value()) : id;
            unique.putIfAbsent(finalId, new RadarFinding(finalId, title.trim(), status, safeSummary(summary), identity.value(), url, PROVIDER_LABEL, timestamp, List.of(matchedServer)));
        }
        List<RadarFinding> out = new ArrayList<>(unique.values());
        out.sort(Comparator.comparingLong(RadarFinding::sortTimestampMs).reversed()
            .thenComparing(RadarFinding::title, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private static void collectFindingObjects(JsonElement element, List<JsonObject> out) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) collectFindingObjects(child, out);
            return;
        }
        if (!element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject();
        String title = firstString(object, "exploitName", "exploit_name", "title", "name", "summary");
        String status = firstString(object, "status", "state", "sightingStatus", "sighting_status",
            "marked_working_at", "marked_patched_at", "verified_at", "created_at", "updated_at",
            "published_at", "submitted_at", "date_submitted", "sighting_server_ip", "sightingServerIp");
        if (status == null && object.has("isPatched")) status = "isPatched";
        if (title != null && (status != null || hasAnyServerValue(object))) {
            out.add(object);
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (entry.getValue() != null && (entry.getValue().isJsonArray() || entry.getValue().isJsonObject())) {
                collectFindingObjects(entry.getValue(), out);
            }
        }
    }

    private static String matchedServerIdentity(JsonObject object, String identity) {
        if (object == null || identity == null || identity.isBlank()) return "";
        String needle = normalizeServerIdentity(identity);
        if (needle.isBlank()) return "";
        for (String key : SERVER_SCALAR_KEYS) {
            String match = matchedServerValue(object.get(key), needle);
            if (!match.isBlank()) return match;
        }
        for (String key : SERVER_COLLECTION_KEYS) {
            String match = matchedServerValue(object.get(key), needle);
            if (!match.isBlank()) return match;
        }
        for (String key : List.of("sightedIps", "sighted_ips")) {
            JsonElement sightings = object.get(key);
            if (sightings == null || !sightings.isJsonArray()) continue;
            for (JsonElement child : sightings.getAsJsonArray()) {
                if (child == null || child.isJsonNull()) continue;
                if (child.isJsonObject()) {
                    JsonObject sighting = child.getAsJsonObject();
                    String match = matchedServerValue(firstPresent(sighting, SERVER_OBJECT_VALUE_KEYS.toArray(String[]::new)), needle);
                    if (!match.isBlank()) return match;
                } else {
                    String match = matchedServerValue(child, needle);
                    if (!match.isBlank()) return match;
                }
            }
        }
        return "";
    }

    private static List<String> extractAffectedServers(JsonObject object) {
        if (object == null) return List.of();
        Set<String> servers = new LinkedHashSet<>();
        for (String key : SERVER_SCALAR_KEYS) {
            collectServerValues(object.get(key), servers);
        }
        for (String key : SERVER_COLLECTION_KEYS) {
            collectServerValues(object.get(key), servers);
        }
        for (String key : List.of("sightedIps", "sighted_ips")) {
            JsonElement sightings = object.get(key);
            if (sightings == null || !sightings.isJsonArray()) continue;
            for (JsonElement child : sightings.getAsJsonArray()) {
                if (child == null || child.isJsonNull()) continue;
                if (child.isJsonObject()) {
                    collectServerValues(firstPresent(child.getAsJsonObject(), SERVER_OBJECT_VALUE_KEYS.toArray(String[]::new)), servers);
                } else {
                    collectServerValues(child, servers);
                }
            }
        }
        return List.copyOf(servers);
    }

    private static void collectServerValues(JsonElement element, Set<String> out) {
        if (element == null || element.isJsonNull() || out == null) return;
        if (element.isJsonPrimitive()) {
            try {
                String raw = element.getAsString();
                String normalized = normalizeServerIdentity(raw);
                if (!normalized.isBlank()) out.add(raw.trim());
            } catch (Exception ignored) {
            }
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) collectServerValues(child, out);
            return;
        }
        if (element.isJsonObject() && element.getAsJsonObject().size() <= 4) {
            JsonObject object = element.getAsJsonObject();
            for (String key : SERVER_OBJECT_VALUE_KEYS) {
                collectServerValues(object.get(key), out);
            }
        }
    }

    private static boolean hasAnyServerValue(JsonObject object) {
        if (object == null) return false;
        for (String key : SERVER_SCALAR_KEYS) {
            if (object.has(key)) {
                Set<String> values = new LinkedHashSet<>();
                collectServerValues(object.get(key), values);
                if (!values.isEmpty()) return true;
            }
        }
        for (String key : SERVER_COLLECTION_KEYS) {
            if (object.has(key)) {
                Set<String> values = new LinkedHashSet<>();
                collectServerValues(object.get(key), values);
                if (!values.isEmpty()) return true;
            }
        }
        return false;
    }

    private static JsonElement firstPresent(JsonObject object, String... keys) {
        if (object == null || keys == null) return null;
        for (String key : keys) {
            if (key != null && object.has(key)) return object.get(key);
        }
        return null;
    }

    private static String matchedServerValue(JsonElement element, String needle) {
        if (element == null || element.isJsonNull()) return "";
        if (element.isJsonPrimitive()) {
            try {
                String raw = element.getAsString();
                String value = normalizeServerIdentity(raw);
                return !value.isBlank() && value.equals(needle) ? raw.trim() : "";
            } catch (Exception ignored) {
                return "";
            }
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                String match = matchedServerValue(child, needle);
                if (!match.isBlank()) return match;
            }
        }
        if (element.isJsonObject() && element.getAsJsonObject().size() <= 4) {
            JsonObject object = element.getAsJsonObject();
            for (String key : SERVER_OBJECT_VALUE_KEYS) {
                String match = matchedServerValue(object.get(key), needle);
                if (!match.isBlank()) return match;
            }
        }
        return "";
    }

    private static String normalizeServerIdentity(String value) {
        if (value == null) return "";
        String clean = value.trim().toLowerCase(Locale.ROOT);
        if (clean.startsWith("/")) clean = clean.substring(1);
        int scheme = clean.indexOf("://");
        if (scheme >= 0) clean = clean.substring(scheme + 3);
        int slash = clean.indexOf('/');
        if (slash >= 0) clean = clean.substring(0, slash);
        if (clean.startsWith("[")) {
            int close = clean.indexOf(']');
            if (close > 0) return clean.substring(1, close);
        }
        int colon = clean.lastIndexOf(':');
        if (colon > 0 && clean.indexOf(':') == colon) clean = clean.substring(0, colon);
        return clean.trim();
    }

    private static String safeFindingUrl(String rawUrl, String fallback) {
        String clean = rawUrl == null ? "" : rawUrl.trim();
        String fallbackText = fallback == null ? "" : fallback.trim();
        if (clean.isBlank()) {
            return looksLikeExploitId(fallbackText) ? BASE_URL + "/exploit/" + urlPath(fallbackText) : BASE_URL + "/?q=" + url(fallbackText);
        }
        String lower = clean.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            try {
                URI uri = URI.create(clean);
                String host = uri.getHost();
                if (host == null || !("dupedb.net".equalsIgnoreCase(host) || host.toLowerCase(Locale.ROOT).endsWith(".dupedb.net"))) {
                    return looksLikeExploitId(fallbackText) ? BASE_URL + "/exploit/" + urlPath(fallbackText) : BASE_URL + "/?q=" + url(fallbackText);
                }
                String path = uri.getPath() == null ? "" : uri.getPath();
                if (path.startsWith("/exploit/") || path.startsWith("/exploits/")) {
                    String id = path.substring(path.lastIndexOf('/') + 1);
                    return BASE_URL + "/exploit/" + urlPath(id);
                }
                return BASE_URL + "/?q=" + url(fallbackText.isBlank() ? clean : fallbackText);
            } catch (Exception ignored) {
                return looksLikeExploitId(fallbackText) ? BASE_URL + "/exploit/" + urlPath(fallbackText) : BASE_URL + "/?q=" + url(fallbackText);
            }
        }
        if (lower.startsWith("/exploit/") || lower.startsWith("/exploits/")
            || lower.startsWith(BASE_URL + "/exploit/") || lower.startsWith(BASE_URL + "/exploits/")) {
            String id = clean.substring(clean.lastIndexOf('/') + 1);
            return BASE_URL + "/exploit/" + urlPath(id);
        }
        if (clean.startsWith("/")) return BASE_URL + clean;
        if (looksLikeExploitId(clean)) return BASE_URL + "/exploit/" + urlPath(clean);
        return clean;
    }

    private static boolean looksLikeExploitId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{8,64}") && !value.contains(" ");
    }

    private static TokenResponse exchangeCode(String code, String verifier, String redirectUri) throws IOException {
        String body = "grant_type=authorization_code"
            + "&client_id=" + url(CLIENT_ID)
            + "&code=" + url(code)
            + "&code_verifier=" + url(verifier)
            + "&redirect_uri=" + url(redirectUri);
        String raw = httpPost(BASE_URL + "/api/oauth/token", body);
        JsonObject object = JsonParser.parseString(raw).getAsJsonObject();
        String accessToken = firstString(object, "access_token", "accessToken", "token");
        if (accessToken == null || accessToken.isBlank()) throw new IOException("No access token returned.");
        return new TokenResponse(accessToken, firstString(object, "refresh_token", "refreshToken"));
    }

    private static TokenResponse exchangeRefreshToken(String refreshToken) throws IOException {
        if (refreshToken == null || refreshToken.isBlank()) throw new IOException("No refresh token saved.");
        String body = "grant_type=refresh_token"
            + "&client_id=" + url(CLIENT_ID)
            + "&refresh_token=" + url(refreshToken);
        String raw = httpPost(BASE_URL + "/api/oauth/token", body);
        JsonObject object = JsonParser.parseString(raw).getAsJsonObject();
        String accessToken = firstString(object, "access_token", "accessToken", "token");
        if (accessToken == null || accessToken.isBlank()) throw new IOException("No refreshed access token returned.");
        return new TokenResponse(accessToken, firstString(object, "refresh_token", "refreshToken"));
    }

    private static String fetchUser(String token) throws IOException {
        String raw = httpGet(BASE_URL + "/api/oauth/userinfo", token);
        return parseUser(raw);
    }

    private static String fetchUserAuthorized() throws IOException {
        String raw = httpGetAuthorized(BASE_URL + "/api/oauth/userinfo");
        return parseUser(raw);
    }

    private static String parseUser(String raw) throws IOException {
        JsonElement element = JsonParser.parseString(raw);
        if (element.isJsonObject()) {
            String user = firstString(element.getAsJsonObject(), "username", "name", "display_name", "email");
            if (user != null && !user.isBlank()) return user.trim();
        }
        return "DupeDB user";
    }

    private static String httpGetAuthorized(String url) throws IOException {
        String token = ensureAccessToken();
        if (token == null || token.isBlank()) throw new IOException("No DupeDB token saved.");
        try {
            return httpGet(url, token);
        } catch (HttpStatusException ex) {
            if (!isAuthFailure(ex.code())) throw ex;
            if (!refreshAccessToken()) throw ex;
            String refreshed = currentToken();
            if (refreshed == null || refreshed.isBlank() || Objects.equals(refreshed, token)) throw ex;
            return httpGet(url, refreshed);
        }
    }

    private static String httpGet(String url, String token) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "AUTISM-Client-Radar");
        if (token != null && !token.isBlank()) connection.setRequestProperty("Authorization", "Bearer " + token);
        return readResponse(connection);
    }

    private static String httpPost(String url, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "AUTISM-Client-Radar");
        connection.setRequestProperty("Content-Length", String.valueOf(bytes.length));
        try (OutputStream out = connection.getOutputStream()) {
            out.write(bytes);
        }
        return readResponse(connection);
    }

    private static String readResponse(HttpURLConnection connection) throws IOException {
        int code = connection.getResponseCode();
        java.io.InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        if (stream == null) throw new IOException("HTTP " + code);
        try (Reader reader = new java.io.InputStreamReader(stream, StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[2048];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                sb.append(buffer, 0, read);
            }
            if (code < 200 || code >= 300) {
                throw new HttpStatusException(code, sb.toString());
            }
            return sb.toString();
        }
    }

    private static ProviderCache loadCache() {
        try {
            if (Files.exists(CACHE_FILE)) {
                try (Reader reader = Files.newBufferedReader(CACHE_FILE, StandardCharsets.UTF_8)) {
                    ProviderCache loaded = GSON.fromJson(reader, ProviderCache.class);
                    if (loaded != null) {
                        loaded.ensure();
                        return loaded;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        try {
            Path oldTokenFile = AutismClientAddon.FOLDER.toPath().getParent().resolve("dupedb-token.json");
            if (Files.exists(oldTokenFile)) {
                JsonObject object = JsonParser.parseString(Files.readString(oldTokenFile, StandardCharsets.UTF_8)).getAsJsonObject();
                String token = firstString(object, "access_token", "accessToken", "token");
                if (token != null && !token.isBlank()) {
                    ProviderCache migrated = new ProviderCache();
                    migrated.ensure();
                    migrated.accessToken = token;
                    migrated.refreshToken = firstString(object, "refresh_token", "refreshToken");
                    return migrated;
                }
            }
        } catch (Exception ignored) {
        }
        ProviderCache fresh = new ProviderCache();
        fresh.ensure();
        return fresh;
    }

    private static void saveCacheLocked() {
        try {
            Files.createDirectories(CACHE_FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(CACHE_FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(cache, writer);
            }
        } catch (Exception ignored) {
        }
    }

    private static String currentToken() {
        synchronized (CACHE_LOCK) {
            return cache == null ? null : cache.accessToken;
        }
    }

    private static String ensureAccessToken() {
        synchronized (CACHE_LOCK) {
            if (cache != null && cache.accessToken != null && !cache.accessToken.isBlank()) {
                return cache.accessToken;
            }
        }
        return refreshAccessToken() ? currentToken() : null;
    }

    private static boolean refreshAccessToken() {
        String refreshToken;
        synchronized (CACHE_LOCK) {
            refreshToken = cache == null ? null : cache.refreshToken;
        }
        if (refreshToken == null || refreshToken.isBlank()) return false;
        try {
            TokenResponse token = exchangeRefreshToken(refreshToken);
            synchronized (CACHE_LOCK) {
                cache.accessToken = token.accessToken;
                if (token.refreshToken != null && !token.refreshToken.isBlank()) {
                    cache.refreshToken = token.refreshToken;
                }
                saveCacheLocked();
            }
            RadarState current = state;
            state = current.withAuth(true, cache.username);
            return true;
        } catch (Exception ex) {
            if (ex instanceof HttpStatusException http && isAuthFailure(http.code())) {
                clearSavedAuth("DupeDB session expired.");
            }
            return false;
        }
    }

    private static void clearSavedAuth(String status) {
        synchronized (CACHE_LOCK) {
            if (cache != null) {
                cache.accessToken = null;
                cache.refreshToken = null;
                cache.username = null;
                cache.userUpdatedAt = 0L;
                saveCacheLocked();
            }
        }
        state = state.withAuth(false, null).withBusy(false, false, status == null ? "Login required." : status, null)
            .withMatches(List.of(), 0, false);
    }

    private static boolean isAuthFailure(int code) {
        return code == 401 || code == 403;
    }

    private static void publish(int generation, RadarState next) {
        if (generation == GENERATION.get()) {
            state = next;
        }
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String lower = value.trim().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
        }
        return sb.toString();
    }

    private static String normalizeStatus(String value) {
        if (value == null || value.isBlank()) return "Unknown";
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("reject")) return "Rejected";
        if (lower.contains("pending")) return "Pending";
        if (lower.contains("unverif")) return "Unverified";
        if (lower.contains("unpatch") || lower.contains("not_patched") || lower.contains("not patched")) return "Unpatched";
        if (lower.contains("work")) return "Working";
        if (lower.contains("patch")) return "Patched";
        if (lower.contains("verif")) return "Verified";
        return UiTextShortener.titleCase(value.trim());
    }

    public static int statusRank(String status) {
        String lower = status == null ? "" : status.toLowerCase(Locale.ROOT);
        if (lower.contains("work")) return 0;
        if (lower.contains("verif")) return 1;
        if (lower.contains("patch")) return 2;
        return 3;
    }

    public static int highestStatusRank(List<RadarFinding> findings) {
        int best = 3;
        if (findings != null) {
            for (RadarFinding finding : findings) {
                best = Math.min(best, statusRank(finding.status()));
            }
        }
        return best;
    }

    public static String highestStatusLabel(List<RadarFinding> findings) {
        int rank = highestStatusRank(findings);
        return switch (rank) {
            case 0 -> "Working";
            case 1 -> "Verified";
            case 2 -> "Patched";
            default -> "Unknown";
        };
    }

    public static StatusCounts statusCounts(List<RadarFinding> findings) {
        int working = 0;
        int patched = 0;
        int verified = 0;
        if (findings != null) {
            for (RadarFinding finding : findings) {
                String lower = finding.status() == null ? "" : finding.status().toLowerCase(Locale.ROOT);
                if (lower.contains("work")) working++;
                else if (lower.contains("patch")) patched++;
                else if (lower.contains("verif")) verified++;
            }
        }
        return new StatusCounts(working, patched, verified);
    }

    private static String firstString(JsonObject object, String... keys) {
        if (object == null || keys == null) return null;
        for (String key : keys) {
            if (key == null || !object.has(key)) continue;
            JsonElement value = object.get(key);
            if (value != null && value.isJsonPrimitive()) {
                try {
                    String text = value.getAsString();
                    if (text != null && !text.isBlank()) return text;
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private static boolean hasTruthy(JsonObject object, String... keys) {
        if (object == null || keys == null) return false;
        for (String key : keys) {
            if (key == null || !object.has(key)) continue;
            JsonElement value = object.get(key);
            if (value == null || value.isJsonNull()) continue;
            if (value.isJsonPrimitive()) {
                String text = value.getAsString();
                if (text != null && !text.isBlank() && !"false".equalsIgnoreCase(text) && !"0".equals(text)) return true;
            } else {
                return true;
            }
        }
        return false;
    }

    private static long parseTimestampMs(JsonObject object, long fallbackMs) {
        if (object == null) return fallbackMs;
        for (String key : List.of("updated_at", "updatedAt", "published_at", "publishedAt",
            "submitted_at", "submittedAt", "date_submitted", "dateSubmitted", "created_at", "createdAt",
            "last_activity_at", "lastActivityAt", "marked_working_at", "markedWorkingAt",
            "marked_patched_at", "markedPatchedAt", "verified_at", "verifiedAt", "working_at", "patched_at",
            "sighting_created_at", "sightingCreatedAt", "sighting_updated_at", "sightingUpdatedAt",
            "sighting_date", "sightingDate")) {
            if (!object.has(key)) continue;
            JsonElement value = object.get(key);
            long parsed = parseTimestampElement(value);
            if (parsed > 0L) return parsed;
        }
        return fallbackMs;
    }

    private static long parseTimestampElement(JsonElement value) {
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return 0L;
        try {
            if (value.getAsJsonPrimitive().isNumber()) {
                long raw = value.getAsLong();
                return raw < 10_000_000_000L ? raw * 1000L : raw;
            }
        } catch (Exception ignored) {
        }
        try {
            String text = value.getAsString();
            if (text == null || text.isBlank() || "null".equalsIgnoreCase(text)) return 0L;
            return Instant.parse(text.trim()).toEpochMilli();
        } catch (Exception ignored) {
        }
        try {
            String text = value.getAsString();
            if (text == null || text.isBlank()) return 0L;
            return java.time.OffsetDateTime.parse(text.trim()).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static String safeSummary(String summary) {
        if (summary == null) return "";
        return UiTextShortener.shorten(summary.replace('\n', ' ').replace('\r', ' ').trim(), 180);
    }

    private static String cleanError(Exception ex) {
        if (ex == null) return "Unknown error.";
        String msg = ex.getMessage();
        if (msg == null || msg.isBlank()) msg = ex.getClass().getSimpleName();
        return UiTextShortener.shorten(msg, 220);
    }

    private static String randomUrlToken(int bytes) {
        byte[] data = new byte[Math.max(16, bytes)];
        new SecureRandom().nextBytes(data);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static String codeChallenge(String verifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception ex) {
            return verifier;
        }
    }

    private static Map<String, String> parseQuery(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            String key = eq >= 0 ? part.substring(0, eq) : part;
            String value = eq >= 0 ? part.substring(eq + 1) : "";
            out.put(java.net.URLDecoder.decode(key, StandardCharsets.UTF_8), java.net.URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return out;
    }

    private static String url(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String urlPath(String value) {
        return url(value).replace("+", "%20");
    }

    public record RadarPluginSnapshot(
        String displayName,
        String canonicalKey,
        String confidence,
        int commandCount,
        List<String> channels,
        List<String> guis,
        boolean feature
    ) {
        public RadarPluginSnapshot {
            displayName = displayName == null ? "" : displayName.trim();
            canonicalKey = canonicalKey == null ? normalize(displayName) : canonicalKey.trim();
            confidence = confidence == null ? "Unknown" : confidence.trim();
            channels = channels == null ? List.of() : List.copyOf(channels);
            guis = guis == null ? List.of() : List.copyOf(guis);
        }

        public boolean isPluginIdentity() {
            if (feature || displayName.isBlank()) return false;
            String lowerConfidence = confidence.toLowerCase(Locale.ROOT);
            return lowerConfidence.contains("exact") || lowerConfidence.contains("strong");
        }
    }

    public enum MatchSource {
        PLUGIN("Plugin"),
        PLUGIN_FUZZY("Fuzzy"),
        SERVER_DOMAIN("Server"),
        SERVER_IP("IP");

        private final String label;

        MatchSource(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record RadarServerIdentity(String value, boolean ip) {
        public RadarServerIdentity {
            value = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        }
    }

    public record RadarServerSnapshot(List<RadarServerIdentity> identities) {
        public static final RadarServerSnapshot EMPTY = new RadarServerSnapshot(List.of());

        public RadarServerSnapshot {
            if (identities == null) {
                identities = List.of();
            } else {
                Map<String, RadarServerIdentity> unique = new LinkedHashMap<>();
                for (RadarServerIdentity identity : identities) {
                    if (identity == null || identity.value().isBlank()) continue;
                    unique.putIfAbsent((identity.ip() ? "ip:" : "domain:") + identity.value(), identity);
                }
                identities = List.copyOf(unique.values());
            }
        }
    }

    public record RadarFinding(
        String id,
        String title,
        String status,
        String summary,
        String affectedPlugin,
        String sourceUrl,
        String provider,
        long sortTimestampMs,
        List<String> affectedServers
    ) {
        public RadarFinding {
            affectedServers = affectedServers == null ? List.of() : List.copyOf(affectedServers);
        }
    }

    public record RadarMatch(
        String detectedPlugin,
        String providerPlugin,
        String matchConfidence,
        MatchSource matchSource,
        double matchScore,
        String matchedInput,
        String sourceUrl,
        String provider,
        List<RadarFinding> findings,
        long sortTimestampMs
    ) {
        public RadarMatch {
            detectedPlugin = detectedPlugin == null ? "" : detectedPlugin;
            providerPlugin = providerPlugin == null ? "" : providerPlugin;
            matchConfidence = matchConfidence == null ? "Unknown" : matchConfidence;
            matchSource = matchSource == null ? MatchSource.PLUGIN_FUZZY : matchSource;
            matchedInput = matchedInput == null ? "" : matchedInput;
            findings = findings == null ? List.of() : List.copyOf(findings);
        }

        public String displayLabel() {
            if (matchSource == MatchSource.SERVER_DOMAIN || matchSource == MatchSource.SERVER_IP) {
                return matchedInput == null || matchedInput.isBlank() ? detectedPlugin : matchedInput;
            }
            if (providerPlugin == null || providerPlugin.isBlank() || providerPlugin.equalsIgnoreCase(detectedPlugin)) {
                return detectedPlugin;
            }
            return detectedPlugin + " -> " + providerPlugin;
        }
    }

    public record RadarState(
        String providerId,
        String providerLabel,
        boolean authenticated,
        boolean authenticating,
        boolean checking,
        String username,
        String status,
        String error,
        List<RadarMatch> matches,
        int detectedPluginCount,
        long updatedAtMs,
        boolean stale
    ) {
        public RadarState {
            matches = matches == null ? List.of() : List.copyOf(matches);
        }

        public RadarState withAuth(boolean authenticated, String username) {
            return new RadarState(providerId, providerLabel, authenticated, authenticating, checking, username, status, error,
                matches, detectedPluginCount, updatedAtMs, stale);
        }

        public RadarState withBusy(boolean authenticating, boolean checking, String status, String error) {
            return new RadarState(providerId, providerLabel, authenticated, authenticating, checking, username,
                status == null ? this.status : status, error, matches, detectedPluginCount, updatedAtMs, stale);
        }

        public RadarState withMatches(List<RadarMatch> matches, int detectedPluginCount, boolean stale) {
            return new RadarState(providerId, providerLabel, authenticated, authenticating, checking, username, status, error,
                matches, detectedPluginCount, System.currentTimeMillis(), stale);
        }

        public RadarState withPluginCount(int count) {
            return new RadarState(providerId, providerLabel, authenticated, authenticating, checking, username, status, error,
                matches, count, updatedAtMs, stale);
        }

        public RadarState markStale() {
            return new RadarState(providerId, providerLabel, authenticated, authenticating, checking, username, status, error,
                matches, detectedPluginCount, updatedAtMs, true);
        }
    }

    public record StatusCounts(int working, int patched, int verified) {
    }

    private record TokenResponse(String accessToken, String refreshToken) {
    }

    private static final class HttpStatusException extends IOException {
        private final int code;

        private HttpStatusException(int code, String body) {
            super("HTTP " + code + ": " + UiTextShortener.shorten(body == null ? "" : body, 180));
            this.code = code;
        }

        private int code() {
            return code;
        }
    }

    private static final class PluginMatch {
        final String providerPlugin;
        final String confidence;
        final MatchSource source;
        final double score;

        PluginMatch(String providerPlugin, String confidence, MatchSource source, double score) {
            this.providerPlugin = providerPlugin;
            this.confidence = confidence;
            this.source = source;
            this.score = score;
        }
    }

    private static final class PluginSearchResult {
        static final PluginSearchResult EMPTY = new PluginSearchResult(null, List.of());

        final PluginMatch match;
        final List<RadarFinding> findings;

        PluginSearchResult(PluginMatch match, List<RadarFinding> findings) {
            this.match = match;
            this.findings = findings == null ? List.of() : List.copyOf(findings);
        }
    }

    private static final class RadarFindingHit {
        final RadarMatch match;
        final RadarFinding finding;

        RadarFindingHit(RadarMatch match, RadarFinding finding) {
            this.match = match;
            this.finding = finding;
        }
    }

    private static final class RadarMatchBuilder {
        final RadarMatch prototype;
        final List<RadarFinding> findings = new ArrayList<>();

        RadarMatchBuilder(RadarMatch prototype) {
            this.prototype = prototype;
        }

        RadarMatch toMatch() {
            long newest = newestTimestamp(findings);
            return new RadarMatch(
                prototype.detectedPlugin(),
                prototype.providerPlugin(),
                prototype.matchConfidence(),
                prototype.matchSource(),
                prototype.matchScore(),
                prototype.matchedInput(),
                prototype.sourceUrl(),
                prototype.provider(),
                findings,
                newest
            );
        }
    }

    private static final class ProviderCache {
        String accessToken;
        String refreshToken;
        String username;
        long userUpdatedAt;
        long pluginIndexAt;
        int pluginIndexVersion;
        List<String> pluginIndex = new ArrayList<>();
        Map<String, CachedFindings> findings = new LinkedHashMap<>();
        Map<String, CachedFindings> serverFindings = new LinkedHashMap<>();
        int findingsVersion = FINDINGS_CACHE_VERSION;
        int serverFindingsVersion = SERVER_FINDINGS_CACHE_VERSION;

        void ensure() {
            if (pluginIndex == null) pluginIndex = new ArrayList<>();
            if (findings == null) findings = new LinkedHashMap<>();
            if (serverFindings == null) serverFindings = new LinkedHashMap<>();
            if (pluginIndexVersion != PLUGIN_INDEX_CACHE_VERSION) {
                pluginIndex = new ArrayList<>();
                pluginIndexAt = 0L;
                pluginIndexVersion = PLUGIN_INDEX_CACHE_VERSION;
            }
            if (findingsVersion != FINDINGS_CACHE_VERSION) {
                findings = new LinkedHashMap<>();
                findingsVersion = FINDINGS_CACHE_VERSION;
            }
            if (serverFindingsVersion != SERVER_FINDINGS_CACHE_VERSION) {
                serverFindings = new LinkedHashMap<>();
                serverFindingsVersion = SERVER_FINDINGS_CACHE_VERSION;
            }
        }
    }

    private static final class CachedFindings {
        long updatedAt;
        List<RadarFinding> items = new ArrayList<>();
    }

    private static final class UiTextShortener {
        static String shorten(String value, int max) {
            if (value == null) return "";
            String clean = value.trim();
            if (clean.length() <= max) return clean;
            return clean.substring(0, Math.max(0, max - 3)).trim() + "...";
        }

        static String titleCase(String value) {
            if (value == null || value.isBlank()) return "";
            String lower = value.trim().toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ');
            StringBuilder sb = new StringBuilder(lower.length());
            boolean upperNext = true;
            for (int i = 0; i < lower.length(); i++) {
                char c = lower.charAt(i);
                if (Character.isWhitespace(c)) {
                    upperNext = true;
                    sb.append(c);
                } else if (upperNext) {
                    sb.append(Character.toUpperCase(c));
                    upperNext = false;
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
    }
}

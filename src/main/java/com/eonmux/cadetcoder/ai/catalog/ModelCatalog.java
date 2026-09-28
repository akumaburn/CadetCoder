package com.eonmux.cadetcoder.ai.catalog;

import com.eonmux.cadetcoder.net.BoundedHttp;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.logging.CadetLogger;
import com.eonmux.cadetcoder.net.HttpRequests;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loads and caches the models.dev catalog &mdash; the same provider/model database
 * that opencode uses. Resolution order mirrors opencode's strategy:
 *
 * <ol>
 *   <li>in-memory cache (loaded once per process);</li>
 *   <li>on-disk cache under {@code &lt;baseDir&gt;/cache/models.json} when still fresh;</li>
 *   <li>live fetch from {@code https://models.dev/api.json} (written back to the disk cache);</li>
 *   <li>a stale disk cache if the fetch fails;</li>
 *   <li>a snapshot bundled in the jar as a last-resort offline fallback.</li>
 * </ol>
 */
public final class ModelCatalog {

    private static final CadetLogger LOG = CadetLogger.getLogger(ModelCatalog.class);

    static final String DEFAULT_SOURCE_URL = "https://models.dev/api.json";
    static final String BUNDLED_SNAPSHOT   = "/catalog/models-dev-snapshot.json";
    private static final Duration CACHE_TTL = Duration.ofHours(12);
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(15);
    private static final String USER_AGENT = "CadetCoder/1.0 (+models.dev)";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, ModelsDevProvider>> CATALOG_TYPE =
            new TypeReference<>() {};

    private static volatile ModelCatalog instance;

    private final AtomicReference<Map<String, ModelsDevProvider>> cache = new AtomicReference<>();

    private ModelCatalog() { }

    public static ModelCatalog getInstance() {
        ModelCatalog local = instance;
        if (local == null) {
            synchronized (ModelCatalog.class) {
                local = instance;
                if (local == null) {
                    local = new ModelCatalog();
                    instance = local;
                }
            }
        }
        return local;
    }

    /** Resets the singleton and in-memory cache (test support). */
    public static synchronized void resetInstance() {
        instance = null;
    }

    /**
     * Returns the catalog keyed by provider id, loading it on first access.
     * Never returns null &mdash; falls back to the bundled snapshot.
     */
    public Map<String, ModelsDevProvider> getProviders() {
        Map<String, ModelsDevProvider> local = cache.get();
        if (local == null) {
            synchronized (this) {
                local = cache.get();
                if (local == null) {
                    local = load(false);
                    cache.set(local);
                }
            }
        }
        return local;
    }

    public ModelsDevProvider getProvider(String providerId) {
        if (providerId == null) {
            return null;
        }
        return getProviders().get(providerId);
    }

    public ModelsDevModel findModel(String providerId, String modelId) {
        ModelsDevProvider provider = getProvider(providerId);
        if (provider == null || modelId == null) {
            return null;
        }
        return provider.getModels().get(modelId);
    }

    /**
     * The catalog entry for a model, whichever door it is reached through.
     *
     * <h2>Why the provider asked for is not always the provider listed</h2>
     *
     * <p>This catalog is keyed by the provider that serves a model. A gateway reselling other
     * vendors' models is a provider of its own -- {@code commandcode}, deliberately, because that
     * is what a failure has to name -- and models.dev does not list gateways. So every lookup made
     * for a gateway missed, and the caller fell back to an assumption: a model with a
     * million-token window was treated as having eight thousand, and the first anyone heard of it
     * was a request that could not be made to fit.</p>
     *
     * <p>A gateway names the vendor in the model id, as {@code deepseek/deepseek-v4-flash}, so
     * when the gateway itself is not listed the vendor it names is asked about the same model. The
     * model is the same model either way; only the door differs.</p>
     *
     * @param providerId the provider the request goes to
     * @param modelId    the model id as configured
     * @return the entry, or {@code null} when neither the provider nor the vendor lists it
     */
    public ModelsDevModel findServedModel(String providerId, String modelId) {
        ModelsDevModel served = findModel(providerId, modelId);
        if (served != null) {
            return served;
        }
        served = findModel(lowerCased(providerId), lowerCased(modelId));
        if (served != null) {
            return served;
        }
        return findVendorsOwnEntry(modelId);
    }

    /**
     * The entry the vendor named in a {@code vendor/model} id publishes for it.
     *
     * @param modelId the model id as configured
     * @return the entry, or {@code null} when the id names no vendor or the vendor does not list it
     */
    private ModelsDevModel findVendorsOwnEntry(String modelId) {
        if (modelId == null) {
            return null;
        }
        String id    = lowerCased(modelId);
        int    first = id.indexOf('/');
        if (first <= 0 || first == id.length() - 1) {
            return null;
        }
        ModelsDevModel byFirstPart = findModel(id.substring(0, first), id.substring(first + 1));
        if (byFirstPart != null) {
            return byFirstPart;
        }
        // An id of three parts or more, such as `azure/openai/gpt-4o`: the vendor is what stands
        // in front of the model's own name rather than what stands at the front of the id.
        int last = id.lastIndexOf('/');
        return last == first ? null : findModel(id.substring(0, last), id.substring(last + 1));
    }

    /** @return {@code text} lower-cased, or {@code null} when it is null */
    private static String lowerCased(String text) {
        return text == null ? null : text.toLowerCase(java.util.Locale.ROOT);
    }

    /** Forces a re-fetch from models.dev, updating the disk and in-memory caches. */
    public synchronized void refresh() {
        Map<String, ModelsDevProvider> loaded = load(true);
        cache.set(loaded);
    }

    // --- loading ---------------------------------------------------------

    private Map<String, ModelsDevProvider> load(boolean force) {
        Path cacheFile = cacheFile();

        // 1. fresh disk cache
        if (!force && cacheFile != null && isFresh(cacheFile)) {
            Map<String, ModelsDevProvider> fromDisk = tryParse(readFile(cacheFile), "disk cache");
            if (fromDisk != null) {
                return fromDisk;
            }
        }

        // 2. live fetch (written back to disk)
        String fetched = fetch();
        if (fetched != null) {
            Map<String, ModelsDevProvider> parsed = tryParse(fetched, "models.dev");
            if (parsed != null) {
                writeCache(cacheFile, fetched);
                return parsed;
            }
        }

        // 3. stale disk cache (better than nothing)
        if (cacheFile != null && Files.exists(cacheFile)) {
            Map<String, ModelsDevProvider> stale = tryParse(readFile(cacheFile), "stale disk cache");
            if (stale != null) {
                return stale;
            }
        }

        // 4. bundled snapshot
        Map<String, ModelsDevProvider> snapshot = tryParse(readBundledSnapshot(), "bundled snapshot");
        if (snapshot != null) {
            return snapshot;
        }

        LOG.warn("Model catalog could not be loaded from any source; returning empty catalog");
        return new LinkedHashMap<>();
    }

    private Map<String, ModelsDevProvider> tryParse(String json, String source) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Map<String, ModelsDevProvider> map = MAPPER.readValue(json, CATALOG_TYPE);
            // models.dev keys providers/models by id; backfill ids from keys defensively.
            for (Map.Entry<String, ModelsDevProvider> e : map.entrySet()) {
                ModelsDevProvider p = e.getValue();
                if (p.getId() == null) {
                    p.setId(e.getKey());
                }
                for (Map.Entry<String, ModelsDevModel> me : p.getModels().entrySet()) {
                    if (me.getValue().getId() == null) {
                        me.getValue().setId(me.getKey());
                    }
                }
            }
            LOG.info("Model catalog loaded from " + source + " (" + map.size() + " providers)");
            return map;
        } catch (Exception e) {
            LOG.warn("Failed to parse model catalog from " + source + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Ceiling for the models.dev catalog fetch, body included.
     *
     * <p>The request timeout above it bounds the wait for HEADERS only, so without this a fetch
     * that stops mid-body blocks the caller forever. The catalog is large, so this is well above the header timeout.</p>
     */
    private static final int FETCH_DEADLINE_SECONDS = 60;

    private String fetch() {
        try {
            HttpClient client = HttpClient.newBuilder()
                                          .connectTimeout(Duration.ofSeconds(10))
                                          .build();
            HttpRequest request = HttpRequests.to(URI.create(sourceUrl()))
                                             .timeout(FETCH_TIMEOUT)
                                             .header("User-Agent", USER_AGENT)
                                             .header("Accept", "application/json")
                                             .GET()
                                             .build();
            HttpResponse<String> response = BoundedHttp.send(client, request, FETCH_DEADLINE_SECONDS);
            if (response.statusCode() == 200) {
                return response.body();
            }
            LOG.warn("models.dev fetch returned status " + response.statusCode());
        } catch (Exception e) {
            LOG.warn("models.dev fetch failed: " + e.getMessage());
        }
        return null;
    }

    private boolean isFresh(Path cacheFile) {
        try {
            if (!Files.exists(cacheFile)) {
                return false;
            }
            long age = System.currentTimeMillis() - Files.getLastModifiedTime(cacheFile).toMillis();
            return age >= 0 && age < CACHE_TTL.toMillis();
        } catch (Exception e) {
            return false;
        }
    }

    private String readFile(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private void writeCache(Path cacheFile, String json) {
        if (cacheFile == null) {
            return;
        }
        try {
            Files.createDirectories(cacheFile.getParent());
            Path tmp = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, cacheFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOG.warn("Failed to write model catalog cache: " + e.getMessage());
        }
    }

    private String readBundledSnapshot() {
        try (InputStream in = ModelCatalog.class.getResourceAsStream(BUNDLED_SNAPSHOT)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.warn("Failed to read bundled model snapshot: " + e.getMessage());
            return null;
        }
    }

    private String sourceUrl() {
        String env = System.getenv("CADET_MODELS_URL");
        if (env != null && !env.isBlank()) {
            return env.endsWith("/api.json") ? env : env + "/api.json";
        }
        return DEFAULT_SOURCE_URL;
    }

    private Path cacheFile() {
        try {
            String baseDir = ConfigManager.getInstance().getConfig().getBaseDir();
            if (baseDir != null && !baseDir.isBlank()) {
                return Paths.get(baseDir, "cache", "models.json");
            }
        } catch (Exception e) {
            // fall through to default
        }
        try {
            return Paths.get(System.getProperty("user.home"), ".cadet", "cache", "models.json");
        } catch (Exception e) {
            return null;
        }
    }
}

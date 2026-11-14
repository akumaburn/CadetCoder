package com.eonmux.cadetcoder.build;

import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.commands.CommandUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;

/**
 * Whether a newer build is sitting in the releases of the repository this build came from.
 *
 * <h2>What is compared, and what answers the question</h2>
 *
 * <p>{@link Release#isNewerThan(String, Instant)} holds the rule; this class only fetches. The
 * fetch is one call to the GitHub API's {@code releases/latest} for the repository stamped into
 * {@link BuildInfo}, answered with the release's tag, its commit, its published time and the
 * download addresses of the jar and its checksum.</p>
 *
 * <h2>Why the start-up notice is cached and always quiet</h2>
 *
 * <p>The notice is a convenience the user never asked for on that particular morning, and it runs
 * in front of every command. So it is asked at most once a day, the answer is kept in the base
 * directory for the next start to read without asking again, and every way the ask can fail -- no
 * network, no repository, no answer -- leaves the start-up exactly as it was, one line shorter.
 * Only {@code update check}, which the user asked for, reports loudly.</p>
 */
public final class UpdateCheck {

    /** How long a cached answer stands in for a fresh one. */
    private static final Duration NOTICE_CACHE_TTL = Duration.ofHours(24);

    private static final String LATEST_RELEASE_API = "https://api.github.com/repos/%s/releases/latest";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** What a check concluded. */
    public enum State { NEWER_AVAILABLE, UP_TO_DATE, UNKNOWN }

    /**
     * @param state   what the comparison concluded
     * @param release the release the check looked at, or {@code null} when it reached none
     * @param reason  what a {@code UNKNOWN} answer is short of, in words for the user
     */
    public record Result(State state, Release release, String reason) {
    }

    private UpdateCheck() {
    }

    /**
     * Asks the origin repository what its latest release is.
     *
     * @return the release, or {@code null} when there is none, the repository is unknown to this
     *         build, or the network answer does not arrive
     */
    public static Release latestRelease() {
        String repo = BuildInfo.current().repo();
        if (repo == null) {
            return null;
        }
        HttpRequest request = com.eonmux.cadetcoder.net.HttpRequests.to(
                URI.create(String.format(LATEST_RELEASE_API, repo)))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "cadet/" + BuildInfo.current().version()
                                       + " (update check)")
                .GET()
                .build();
        try {
            HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            return parse(MAPPER.readTree(response.body()));
        } catch (IOException | InterruptedException | RuntimeException goneWrong) {
            // Interrupted is restored, so the run shutting down keeps shutting down. Everything
            // else is answered with "no release reached", which is what every caller here treats
            // it as.
            if (goneWrong instanceof InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
            return null;
        }
    }

    /**
     * The release document's facts, and the download address of its jar.
     *
     * <p>The jar is picked by the fixed name the release is published under,
     * {@code cadet-latest.jar}, so a release cannot be misread by the order its assets happen to
     * come back in. The checksum beside it is taken when it is there and left off when it is not:
     * verifying is a strengthening of the download, not a condition of it.</p>
     *
     * @param json the API's answer
     * @return the release, or {@code null} when the document is not a release or ships no jar
     */
    static Release parse(JsonNode json) {
        if (json == null || !json.isObject()
            || json.path("tag_name").asText("").isBlank()
            || json.path("draft").asBoolean(false)) {
            return null;
        }
        String jarUrl     = null;
        String sha256Url  = null;
        Iterator<JsonNode> assets = json.path("assets").elements();
        while (assets.hasNext()) {
            JsonNode asset = assets.next();
            String name = asset.path("name").asText("");
            String url  = asset.path("browser_download_url").asText("");
            if (name.isBlank() || url.isBlank()) {
                continue;
            }
            if (name.equals("cadet-latest.jar")) {
                jarUrl = url;
            } else if (name.equals("cadet-latest.jar.sha256")) {
                sha256Url = url;
            }
        }
        if (jarUrl == null) {
            return null;
        }
        return new Release(json.path("tag_name").asText(),
                           json.path("target_commitish").asText(null),
                           instant(json.path("published_at").asText(null)),
                           blankToNull(json.path("html_url").asText(null)),
                           jarUrl, sha256Url);
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    /**
     * The whole question, asked once: what the latest release is, and whether installing it would
     * move this build forward.
     *
     * @return the result; {@code UNKNOWN} carries the reason in words for the user
     */
    public static Result checkNow() {
        BuildInfo local = BuildInfo.current();
        if (!local.canBeChecked()) {
            return new Result(State.UNKNOWN, null,
                              "this build does not record the commit, time or repository it was"
                              + " made from, so there is nothing to compare a release against");
        }
        Release release = latestRelease();
        if (release == null) {
            return new Result(State.UNKNOWN, null,
                              "no release could be reached at " + local.repo()
                              + " -- it may have none yet, or the network may be down");
        }
        if (release.isNewerThan(local.commit(), local.time())) {
            return new Result(State.NEWER_AVAILABLE, release, null);
        }
        return new Result(State.UP_TO_DATE, release, null);
    }

    /**
     * The one start-up line, when a newer build is sitting there.
     *
     * <p>Cached for a day, quiet in every failure, and skipped when the command the user asked
     * for is {@code update} itself -- which answers the same question in full a moment later.</p>
     */
    public static void printNotice() {
        Cached cached = readCache();
        try {
            if (cached != null && cached.checkedAt().isAfter(Instant.now().minus(NOTICE_CACHE_TTL))) {
                announceIfNewer(cached.toRelease(), cached::tag);
                return;
            }
            Release release = latestRelease();
            if (release == null) {
                return; // Quiet: the network owes this run nothing.
            }
            writeCache(release);
            announceIfNewer(release, release::tag);
        } catch (RuntimeException neverIntoStartUp) {
            // Recorded nowhere louder than the reason the cache write below is also guarded: a
            // check that cannot run is a check that says nothing.
        }
    }

    private static void announceIfNewer(Release release, java.util.function.Supplier<String> tag) {
        BuildInfo local = BuildInfo.current();
        if (release != null && release.isNewerThan(local.commit(), local.time())) {
            StringBuilder notice = new StringBuilder("A newer build is available: ").append(tag.get())
                    .append(" (build ").append(release.commitShort())
                    .append(", published ").append(release.publishedAt()).append(")");
            if (release.htmlUrl() != null) {
                notice.append(" -- ").append(release.htmlUrl());
            }
            notice.append(". Run ").append(CommandUsage.render("update")).append(" to install it.");
            OutputFormatter.printInfo(notice.toString());
        }
    }

    /* ------------------------------------------------------------------ *
     * The cached answer                                                   *
     * ------------------------------------------------------------------ */

    private static Path cachePath() {
        try {
            String baseDir = com.eonmux.cadetcoder.config.ConfigManager.getInstance()
                                     .getConfig().getBaseDir();
            return Path.of(baseDir, "update-check.json");
        } catch (Exception e) {
            return Path.of(System.getProperty("user.home"), ".cadet", "update-check.json");
        }
    }

    /**
     * The answer the last check kept, or {@code null} when there is none.
     *
     * <p>Read defensively the way every file this feature touches is: a cache that is missing,
     * half-written or shaped by another build is no answer, and no answer only means the next
     * start-up asks again.</p>
     */
    static Cached readCache() {
        Path path = cachePath();
        if (!Files.exists(path)) {
            return null;
        }
        try {
            JsonNode json = MAPPER.readTree(path.toFile());
            JsonNode tag  = json.path("tag");
            JsonNode when = json.path("checkedAt");
            if (!tag.isTextual() || tag.asText().isBlank() || !when.canConvertToLong()) {
                return null;
            }
            return new Cached(tag.asText(),
                              json.path("commit").asText(null),
                              instant(json.path("publishedAt").asText(null)),
                              json.path("htmlUrl").asText(null),
                              json.path("jarUrl").asText(null),
                              json.path("sha256Url").asText(null),
                              Instant.ofEpochMilli(when.asLong()));
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    static void writeCache(Release release) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode node = MAPPER.createObjectNode();
            node.put("checkedAt", Instant.now().toEpochMilli());
            node.put("tag", release.tag());
            if (release.commit() != null) {
                node.put("commit", release.commit());
            }
            if (release.publishedAt() != null) {
                node.put("publishedAt", release.publishedAt().toString());
            }
            node.put("jarUrl", release.jarUrl());
            if (release.htmlUrl() != null) {
                node.put("htmlUrl", release.htmlUrl());
            }
            if (release.sha256Url() != null) {
                node.put("sha256Url", release.sha256Url());
            }
            MAPPER.writeValue(cachePath().toFile(), node);
        } catch (IOException | RuntimeException unwritable) {
            // A cache that cannot be written costs the next start-up one network call, and the
            // user nothing.
        }
    }

    /**
     * The shape of a cached answer: the release, and when it was asked about.
     *
     * @param tag         the release's tag
     * @param commit      the commit the release was cut from
     * @param publishedAt when it was published
     * @param htmlUrl     the release's page on the repository
     * @param jarUrl      the release jar's download address
     * @param sha256Url   the checksum's address, or {@code null}
     * @param checkedAt   when the question was last asked
     */
    record Cached(String tag, String commit, Instant publishedAt, String htmlUrl,
                  String jarUrl, String sha256Url, Instant checkedAt) {

        /** @return the release the cache describes, or {@code null} when it describes too little */
        Release toRelease() {
            return tag == null || jarUrl == null || publishedAt == null
                   ? null
                   : new Release(tag, commit, publishedAt, htmlUrl, jarUrl, sha256Url);
        }
    }

    /* ------------------------------------------------------------------ *
     * Plumbing                                                            *
     * ------------------------------------------------------------------ */

    private static HttpClient CLIENT;

    private static HttpClient client() {
        if (CLIENT == null) {
            CLIENT = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }
        return CLIENT;
    }

    private static Instant instant(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text.trim());
        } catch (RuntimeException notAClockReading) {
            return null;
        }
    }
}

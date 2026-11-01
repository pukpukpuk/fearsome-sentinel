package one.autremel.fearsome_sentinel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

final class Remote {
    private static final String RELEASES_URL = "https://api.github.com/repos/pukpukpuk/fearsome/releases/latest";
    private static final String MODRINTH_VERSIONS_URL = "https://api.modrinth.com/v2/project/%s/version?loaders=%s&game_versions=%s";
    private static final String USER_AGENT = "pukpukpuk/fearsome-sentinel";
    private static final String MOD_JSON = "fabric.mod.json";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    static Optional<Release> latestFearsome() throws IOException, InterruptedException {
        var response = getJson(RELEASES_URL);
        if (response.isEmpty()) return Optional.empty();

        var release = response.get().getAsJsonObject();
        var tag = release.get("tag_name").getAsString();
        var version = parseVersion(tag.startsWith("v") ? tag.substring(1) : tag);

        for (var element : release.getAsJsonArray("assets")) {
            var asset = element.getAsJsonObject();
            var name = asset.get("name").getAsString();
            if (!name.endsWith(".jar") || name.endsWith("-sources.jar")) continue;

            var digest = asset.get("digest");
            if (digest == null || digest.isJsonNull() || !digest.getAsString().startsWith("sha256:")) {
                throw new IOException("Release asset " + name + " has no sha256 digest");
            }
            var hash = digest.getAsString().substring("sha256:".length());
            return Optional.of(new Release(version, new RemoteFile(asset.get("browser_download_url").getAsString(), name, "SHA-256", hash)));
        }
        throw new IOException("Release " + tag + " has no jar asset");
    }

    static Optional<RemoteFile> findOnModrinth(String projectId, Requirement requirement, String gameVersion)
            throws IOException, InterruptedException {
        var url = MODRINTH_VERSIONS_URL.formatted(encode(projectId), encode("[\"fabric\"]"), encode("[\"" + gameVersion + "\"]"));
        var response = getJson(url);
        if (response.isEmpty()) return Optional.empty();

        for (var element : response.get().getAsJsonArray()) {
            var version = element.getAsJsonObject();
            Version parsed;
            try {
                parsed = Version.parse(version.get("version_number").getAsString());
            } catch (VersionParsingException e) {
                continue;
            }
            if (!requirement.matches(parsed)) continue;

            var file = primaryFile(version.getAsJsonArray("files"));
            var hash = file.getAsJsonObject("hashes").get("sha512").getAsString();
            return Optional.of(new RemoteFile(file.get("url").getAsString(), file.get("filename").getAsString(), "SHA-512", hash));
        }
        return Optional.empty();
    }

    static void download(RemoteFile file, Path target) throws IOException, InterruptedException {
        var response = CLIENT.send(request(file.url()), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("HTTP " + response.statusCode() + " while downloading " + file.fileName());
        }

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(file.hashAlgorithm());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        try (InputStream in = new DigestInputStream(response.body(), digest)) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }

        if (!HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(file.hash())) {
            Files.deleteIfExists(target);
            throw new IOException("Hash mismatch for " + file.fileName());
        }
    }

    static JarMetadata readJar(Path jar) throws IOException {
        try (var zip = new ZipFile(jar.toFile())) {
            var modJson = zip.getEntry(MOD_JSON);
            if (modJson == null) throw new IOException(jar.getFileName() + " has no " + MOD_JSON);
            var json = parseJson(zip.getInputStream(modJson));

            var depends = new LinkedHashMap<String, Remote.Requirement>();
            var dependsJson = json.getAsJsonObject("depends");
            if (dependsJson != null) {
                for (Map.Entry<String, JsonElement> entry : dependsJson.entrySet()) {
                    depends.put(entry.getKey(), Requirement.parse(entry.getValue()));
                }
            }

            var nested = new HashMap<String, Version>();
            var jars = json.getAsJsonArray("jars");
            if (jars != null) {
                for (JsonElement element : jars) {
                    ZipEntry entry = zip.getEntry(element.getAsJsonObject().get("file").getAsString());
                    if (entry != null) readNested(zip.getInputStream(entry), nested);
                }
            }
            return new JarMetadata(depends, nested);
        }
    }

    private static void readNested(InputStream in, Map<String, Version> provided) throws IOException {
        try (var zip = new ZipInputStream(in)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (!entry.getName().equals(MOD_JSON)) continue;

                var json = parseJson(zip);
                var version = parseVersion(json.get("version").getAsString());
                provided.put(json.get("id").getAsString(), version);
                var provides = json.getAsJsonArray("provides");
                if (provides != null) {
                    for (var id : provides) provided.put(id.getAsString(), version);
                }
                return;
            }
        }
    }

    private static JsonObject primaryFile(JsonArray files) {
        for (var element : files) {
            var file = element.getAsJsonObject();
            if (file.get("primary").getAsBoolean()) return file;
        }
        return files.get(0).getAsJsonObject();
    }

    private static Optional<JsonElement> getJson(String url) throws IOException, InterruptedException {
        var response = CLIENT.send(request(url), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) return Optional.empty();
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + " from " + url);
        return Optional.of(JsonParser.parseString(response.body()));
    }

    private static HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(TIMEOUT)
                .build();
    }

    private static JsonObject parseJson(InputStream in) {
        return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static Version parseVersion(String version) throws IOException {
        try {
            return Version.parse(version);
        } catch (VersionParsingException e) {
            throw new IOException(e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    record Release(Version version, RemoteFile file) {
    }

    record RemoteFile(String url, String fileName, String hashAlgorithm, String hash) {
    }

    record JarMetadata(Map<String, Requirement> depends, Map<String, Version> nested) {
    }

    record Requirement(List<String> raw, List<VersionPredicate> predicates) {
        static Requirement parse(JsonElement json) throws IOException {
            var raw = new ArrayList<String>();
            if (json.isJsonArray()) {
                for (JsonElement element : json.getAsJsonArray()) raw.add(element.getAsString());
            } else {
                raw.add(json.getAsString());
            }

            var predicates = new ArrayList<VersionPredicate>();
            for (var predicate : raw) {
                try {
                    predicates.add(VersionPredicate.parse(predicate));
                } catch (VersionParsingException e) {
                    throw new IOException(e);
                }
            }
            return new Requirement(raw, predicates);
        }

        boolean matches(Version version) {
            return predicates.stream().anyMatch(predicate -> predicate.test(version));
        }

        @Override
        public String toString() {
            return String.join(" | ", raw);
        }
    }
}

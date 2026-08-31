package de.morau.blockframe.benchmark.phase2a0b;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Versioned semantic benchmark-start projection. Volatile values in mixed
 * files remain outside this hash and are preserved by the transaction.
 */
public final class BenchmarkConfigProfile {
    public static final String PROFILE_DIRECTORY =
        "BlockFrame_Benchmark_Config_v1";
    public static final String REPOSITORY_MANIFEST =
        "benchmarks/fixtures/blockframe-benchmark-config-v1.json";
    public static final String CANONICAL_HEADER =
        "BLOCKFRAME_BENCHMARK_START_PROFILE_V1";
    private static final Set<String> QUOTED_OPTIONS = Set.of(
        "graphicsPreset",
        "renderClouds"
    );
    private static final String OPTIONS_PREFIX = "minecraft.options.";
    private static final String VOXELLIFT_PREFIX =
        "blockframe.config.voxellift.";
    private static final Gson GSON = new Gson();

    public record Verification(
        String benchmarkStartProfileHash,
        int projectedFieldCount,
        String status
    ) {
    }

    private final Path manifestPath;
    private final byte[] manifestBytes;
    private final TreeMap<String, String> fields;
    private final String expectedHash;

    private BenchmarkConfigProfile(
        Path manifestPath,
        byte[] manifestBytes,
        TreeMap<String, String> fields,
        String expectedHash
    ) {
        this.manifestPath = manifestPath;
        this.manifestBytes = manifestBytes;
        this.fields = fields;
        this.expectedHash = expectedHash;
    }

    public static BenchmarkConfigProfile load(Path repository)
        throws IOException {
        Path path = repository.resolve(REPOSITORY_MANIFEST)
            .toAbsolutePath()
            .normalize();
        byte[] bytes = Files.readAllBytes(path);
        JsonObject root = JsonParser.parseString(
            new String(bytes, StandardCharsets.UTF_8)
        ).getAsJsonObject();
        if (root.get("schemaVersion").getAsInt() != 1) {
            throw new IOException(
                "unsupported benchmark config profile schema"
            );
        }
        JsonObject canonical = root.getAsJsonObject("canonicalization");
        require(
            "SHA-256".equals(canonical.get("algorithm").getAsString()),
            "unexpected profile hash algorithm"
        );
        require(
            CANONICAL_HEADER.equals(
                canonical.get("header").getAsString()
            ),
            "unexpected profile canonical header"
        );
        require(
            "String.compareTo ordinal".equals(
                canonical.get("fieldOrdering").getAsString()
            ),
            "unexpected profile field ordering"
        );
        TreeMap<String, String> fields = new TreeMap<>();
        for (
            Map.Entry<String, JsonElement> entry :
                root.getAsJsonObject("fields").entrySet()
        ) {
            String key = checkedToken(entry.getKey(), "profile field");
            String value = checkedToken(
                entry.getValue().getAsString(),
                "profile value"
            );
            if (fields.put(key, value) != null) {
                throw new IOException("duplicate profile field: " + key);
            }
        }
        String expected = root.get("benchmarkStartProfileHash")
            .getAsString();
        String actual = projectionHash(fields);
        require(
            expected.equals(actual),
            "benchmark start profile hash mismatch: expected "
                + expected
                + " actual "
                + actual
        );
        return new BenchmarkConfigProfile(
            path,
            bytes,
            fields,
            expected
        );
    }

    public String benchmarkStartProfileHash() {
        return this.expectedHash;
    }

    public int projectedFieldCount() {
        return this.fields.size();
    }

    public String expectedValue(String name) throws IOException {
        return requiredField(name);
    }

    public byte[] manifestBytes() {
        return this.manifestBytes.clone();
    }

    public String canonicalProjection() {
        ArrayList<String> lines =
            new ArrayList<>(this.fields.size() + 1);
        lines.add(CANONICAL_HEADER);
        for (Map.Entry<String, String> entry : this.fields.entrySet()) {
            lines.add(entry.getKey() + "=" + entry.getValue());
        }
        return String.join("\n", lines);
    }

    public Verification verifyInstance(
        Path instance,
        String actualModHash
    ) throws IOException {
        TreeMap<String, String> actual = new TreeMap<>(this.fields);
        actual.putAll(readProjectedOptions(instance.resolve("options.txt")));
        actual.putAll(
            readProjectedProperties(
                instance.resolve("config/voxellift.properties")
            )
        );
        boolean enginePresent = Files.isRegularFile(
            instance.resolve("config/blockframe-engine.properties"),
            LinkOption.NOFOLLOW_LINKS
        );
        actual.put(
            "blockframe.config.engineFilePresent",
            Boolean.toString(enginePresent)
        );
        actual.put("runtime.modListHash", actualModHash);
        JsonObject instanceJson = readObject(
            instance.resolve("minecraftinstance.json")
        );
        actual.put(
            "runtime.minecraftVersion",
            requiredString(instanceJson, "gameVersion")
        );
        JsonObject loader = instanceJson.getAsJsonObject("baseModLoader");
        actual.put(
            "runtime.neoForgeVersion",
            requiredString(loader, "forgeVersion")
        );
        actual.put(
            "runtime.javaVersion",
            System.getProperty("java.version", "NOT_AVAILABLE")
        );
        for (Map.Entry<String, String> expected : this.fields.entrySet()) {
            String observed = actual.get(expected.getKey());
            if (!expected.getValue().equals(observed)) {
                throw new IOException(
                    "benchmark start profile field mismatch: "
                        + expected.getKey()
                        + " expected="
                        + expected.getValue()
                        + " actual="
                        + observed
                );
            }
        }
        String actualHash = projectionHash(actual);
        require(
            this.expectedHash.equals(actualHash),
            "applied benchmark start profile hash mismatch"
        );
        return new Verification(
            actualHash,
            actual.size(),
            "VERIFIED"
        );
    }

    /**
     * Verifies the immutable semantic configuration projection while the
     * independently pinned live-gate artifact inventory is staged. The
     * caller remains responsible for checking that physical mod inventory.
     */
    public Verification verifyConfiguration(Path instance)
        throws IOException {
        return verifyInstance(
            instance,
            expectedValue("runtime.modListHash")
        );
    }

    Verification verifyProjectedSnapshot(Path snapshotRoot)
        throws IOException {
        TreeMap<String, String> actual = new TreeMap<>(this.fields);
        actual.putAll(
            readProjectedOptions(snapshotRoot.resolve("options.txt"))
        );
        actual.putAll(
            readProjectedProperties(
                snapshotRoot.resolve("config/voxellift.properties")
            )
        );
        boolean enginePresent = Files.isRegularFile(
            snapshotRoot.resolve("config/blockframe-engine.properties"),
            LinkOption.NOFOLLOW_LINKS
        );
        actual.put(
            "blockframe.config.engineFilePresent",
            Boolean.toString(enginePresent)
        );
        for (Map.Entry<String, String> expected : this.fields.entrySet()) {
            String observed = actual.get(expected.getKey());
            if (!expected.getValue().equals(observed)) {
                throw new IOException(
                    "staged benchmark profile field mismatch: "
                        + expected.getKey()
                );
            }
        }
        String actualHash = projectionHash(actual);
        require(
            this.expectedHash.equals(actualHash),
            "staged benchmark start profile hash mismatch"
        );
        return new Verification(
            actualHash,
            actual.size(),
            "STAGED_VERIFIED"
        );
    }

    public void applyToSnapshot(Path snapshotRoot) throws IOException {
        Path options = snapshotRoot.resolve("options.txt");
        Path voxellift = snapshotRoot.resolve(
            "config/voxellift.properties"
        );
        rewriteKeyValueFile(
            options,
            ':',
            optionTargets(),
            true
        );
        rewriteKeyValueFile(
            voxellift,
            '=',
            propertyTargets(),
            false
        );
        boolean engineExpected = Boolean.parseBoolean(
            requiredField("blockframe.config.engineFilePresent")
        );
        boolean enginePresent = Files.isRegularFile(
            snapshotRoot.resolve("config/blockframe-engine.properties"),
            LinkOption.NOFOLLOW_LINKS
        );
        require(
            engineExpected == enginePresent,
            "blockframe-engine.properties presence mismatch"
        );
    }

    public Path manifestPath() {
        return this.manifestPath;
    }

    static String projectionHash(Map<String, String> fields) {
        TreeMap<String, String> ordered = new TreeMap<>(fields);
        ArrayList<String> lines = new ArrayList<>(ordered.size() + 1);
        lines.add(CANONICAL_HEADER);
        for (Map.Entry<String, String> entry : ordered.entrySet()) {
            lines.add(entry.getKey() + "=" + entry.getValue());
        }
        return FixtureInventory.sha256(
            String.join("\n", lines).getBytes(StandardCharsets.UTF_8)
        );
    }

    private Map<String, String> readProjectedOptions(Path path)
        throws IOException {
        Map<String, String> raw = parseKeyValueFile(path, ':');
        TreeMap<String, String> result = new TreeMap<>();
        for (Map.Entry<String, String> target : optionTargets().entrySet()) {
            String value = raw.get(target.getKey());
            if (value == null) {
                throw new IOException(
                    "missing projected options key: " + target.getKey()
                );
            }
            result.put(
                OPTIONS_PREFIX + target.getKey(),
                normalizeOption(
                    target.getKey(),
                    value,
                    target.getValue()
                )
            );
        }
        return result;
    }

    private Map<String, String> readProjectedProperties(Path path)
        throws IOException {
        Map<String, String> raw = parseKeyValueFile(path, '=');
        TreeMap<String, String> result = new TreeMap<>();
        for (
            Map.Entry<String, String> target :
                propertyTargets().entrySet()
        ) {
            String value = raw.get(target.getKey());
            if (value == null) {
                throw new IOException(
                    "missing projected BlockFrame key: "
                        + target.getKey()
                );
            }
            result.put(
                VOXELLIFT_PREFIX + target.getKey(),
                checkedToken(value.strip(), "BlockFrame property")
            );
        }
        return result;
    }

    private TreeMap<String, String> optionTargets() throws IOException {
        TreeMap<String, String> targets = new TreeMap<>();
        for (Map.Entry<String, String> entry : this.fields.entrySet()) {
            if (entry.getKey().startsWith(OPTIONS_PREFIX)) {
                targets.put(
                    entry.getKey().substring(OPTIONS_PREFIX.length()),
                    entry.getValue()
                );
            }
        }
        require(!targets.isEmpty(), "profile has no options projection");
        return targets;
    }

    private TreeMap<String, String> propertyTargets()
        throws IOException {
        TreeMap<String, String> targets = new TreeMap<>();
        for (Map.Entry<String, String> entry : this.fields.entrySet()) {
            if (entry.getKey().startsWith(VOXELLIFT_PREFIX)) {
                targets.put(
                    entry.getKey().substring(VOXELLIFT_PREFIX.length()),
                    entry.getValue()
                );
            }
        }
        require(
            !targets.isEmpty(),
            "profile has no BlockFrame property projection"
        );
        return targets;
    }

    private String requiredField(String name) throws IOException {
        String value = this.fields.get(name);
        if (value == null) {
            throw new IOException("missing profile field: " + name);
        }
        return value;
    }

    private static String normalizeOption(
        String key,
        String raw,
        String expected
    ) throws IOException {
        String value = raw.strip();
        try {
            if (QUOTED_OPTIONS.contains(key)) {
                JsonElement parsed = JsonParser.parseString(value);
                if (!parsed.isJsonPrimitive()) {
                    throw new IOException(
                        "non-primitive projected option: " + key
                    );
                }
                return parsed.getAsString();
            }
            if ("true".equals(expected) || "false".equals(expected)) {
                if (
                    !"true".equalsIgnoreCase(value)
                        && !"false".equalsIgnoreCase(value)
                ) {
                    throw new IOException(
                        "invalid boolean projected option: " + key
                    );
                }
                return Boolean.toString(Boolean.parseBoolean(value));
            }
            BigDecimal observed = new BigDecimal(value);
            BigDecimal wanted = new BigDecimal(expected);
            if (observed.compareTo(wanted) != 0) {
                return observed.stripTrailingZeros().toPlainString();
            }
            return expected;
        } catch (RuntimeException error) {
            throw new IOException(
                "unparseable projected option "
                    + key
                    + "="
                    + value,
                error
            );
        }
    }

    private static void rewriteKeyValueFile(
        Path path,
        char separator,
        Map<String, String> replacements,
        boolean quoteSelectedOptions
    ) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        ArrayList<String> output = new ArrayList<>(lines.size());
        HashSet<String> seen = new HashSet<>();
        for (String line : lines) {
            int split = line.indexOf(separator);
            if (
                split <= 0
                    || line.startsWith("#")
                    || line.startsWith("!")
            ) {
                output.add(line);
                continue;
            }
            String key = line.substring(0, split).strip();
            String replacement = replacements.get(key);
            if (replacement == null) {
                output.add(line);
                continue;
            }
            if (!seen.add(key)) {
                throw new IOException(
                    "duplicate mixed-file key while applying: " + key
                );
            }
            String encoded =
                quoteSelectedOptions && QUOTED_OPTIONS.contains(key)
                    ? GSON.toJson(replacement)
                    : replacement;
            output.add(key + separator + encoded);
        }
        if (!seen.equals(replacements.keySet())) {
            HashSet<String> missing = new HashSet<>(replacements.keySet());
            missing.removeAll(seen);
            throw new IOException(
                "missing mixed-file keys while applying: " + missing
            );
        }
        Files.write(
            path,
            output,
            StandardCharsets.UTF_8
        );
    }

    private static Map<String, String> parseKeyValueFile(
        Path path,
        char separator
    ) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("required mixed config file missing: " + path);
        }
        HashMap<String, String> values = new HashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (
                line.isBlank()
                    || line.startsWith("#")
                    || line.startsWith("!")
            ) {
                continue;
            }
            int split = line.indexOf(separator);
            if (split <= 0) {
                throw new IOException(
                    "unparseable mixed config line in "
                        + path.getFileName()
                );
            }
            String key = checkedToken(
                line.substring(0, split).strip(),
                "mixed config key"
            );
            String value = line.substring(split + 1).strip();
            if (values.put(key, value) != null) {
                throw new IOException(
                    "duplicate mixed config key: " + key
                );
            }
        }
        return Collections.unmodifiableMap(values);
    }

    private static JsonObject readObject(Path path) throws IOException {
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (RuntimeException error) {
            throw new IOException("cannot parse " + path, error);
        }
    }

    private static String requiredString(JsonObject object, String name)
        throws IOException {
        JsonElement value = object.get(name);
        if (
            value == null
                || value.isJsonNull()
                || value.getAsString().isBlank()
        ) {
            throw new IOException("missing runtime identity: " + name);
        }
        return value.getAsString();
    }

    private static String checkedToken(String value, String label)
        throws IOException {
        if (
            value == null
                || value.isBlank()
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0
                || value.indexOf('=') >= 0
        ) {
            throw new IOException("invalid " + label);
        }
        return value;
    }

    private static void require(boolean condition, String message)
        throws IOException {
        if (!condition) {
            throw new IOException(message);
        }
    }
}

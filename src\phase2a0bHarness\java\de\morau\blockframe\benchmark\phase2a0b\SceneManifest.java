package de.morau.blockframe.benchmark.phase2a0b;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Immutable, pre-measure parse of one versioned scene manifest.
 */
public final class SceneManifest {
    public record Scene(
        Phase2a0bContracts.SceneId id,
        Phase2a0bContracts.SceneType type,
        String dimension,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        float fov,
        int renderDistanceChunks,
        int simulationDistanceChunks,
        int resolutionWidth,
        int resolutionHeight,
        String windowMode,
        boolean vsync,
        int fpsLimit,
        long worldClockTotalTicks,
        String weather,
        int warmupSeconds,
        int measureSeconds,
        String requiredChunkState,
        ReplayTimeline timeline,
        long sceneHash64
    ) {
    }

    private final String fixtureHash;
    private final String benchmarkStartProfileHash;
    private final String fileHash;
    private final Scene[] scenes;

    private SceneManifest(
        String fixtureHash,
        String benchmarkStartProfileHash,
        String fileHash,
        Scene[] scenes
    ) {
        this.fixtureHash = fixtureHash;
        this.benchmarkStartProfileHash = benchmarkStartProfileHash;
        this.fileHash = fileHash;
        this.scenes = scenes;
    }

    public static SceneManifest load(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        JsonObject root = JsonParser.parseString(
            new String(bytes, StandardCharsets.UTF_8)
        ).getAsJsonObject();
        if (root.get("schemaVersion").getAsInt() != 2) {
            throw new IOException("unsupported scene manifest schema");
        }
        Path normalized = path.toAbsolutePath().normalize();
        String baseName = requiredString(root, "baseManifest");
        Path base = normalized.resolveSibling(baseName).normalize();
        if (
            !Objects.equals(normalized.getParent(), base.getParent())
                || baseName.indexOf('/') >= 0
                || baseName.indexOf('\\') >= 0
        ) {
            throw new IOException("scene base manifest path escapes fixture directory");
        }
        byte[] baseBytes = Files.readAllBytes(base);
        if (
            !requiredString(root, "baseManifestSha256").equals(
                FixtureInventory.sha256(baseBytes)
            )
        ) {
            throw new IOException("scene base manifest hash mismatch");
        }
        JsonObject baseRoot = JsonParser.parseString(
            new String(baseBytes, StandardCharsets.UTF_8)
        ).getAsJsonObject();
        if (baseRoot.get("schemaVersion").getAsInt() != 1) {
            throw new IOException("unsupported base scene manifest schema");
        }
        String startProfileHash = baseRoot.get(
            "benchmarkStartProfileHash"
        ).getAsString();
        JsonArray array = baseRoot.getAsJsonArray("scenes");
        JsonObject sceneTypes = root.getAsJsonObject("sceneTypes");
        if (sceneTypes == null || sceneTypes.size() != array.size()) {
            throw new IOException("scene type inventory mismatch");
        }
        Scene[] scenes = new Scene[array.size()];
        for (int index = 0; index < array.size(); index++) {
            JsonObject scene = array.get(index).getAsJsonObject();
            if (
                !startProfileHash.equals(
                    requiredString(
                        scene,
                        "expectedBenchmarkStartProfileHash"
                    )
                )
            ) {
                throw new IOException(
                    "scene benchmark start profile hash mismatch"
                );
            }
            String id = requiredString(scene, "id");
            scenes[index] = parseScene(
                scene,
                Phase2a0bContracts.SceneType.parse(
                    requiredString(sceneTypes, id)
                )
            );
        }
        return new SceneManifest(
            baseRoot.get("fixtureCanonicalSha256").getAsString(),
            startProfileHash,
            FixtureInventory.sha256(bytes),
            scenes
        );
    }

    public String fixtureHash() {
        return this.fixtureHash;
    }

    public String benchmarkStartProfileHash() {
        return this.benchmarkStartProfileHash;
    }

    public String fileHash() {
        return this.fileHash;
    }

    public Scene requireReadyScene(String id) throws IOException {
        Phase2a0bContracts.SceneId typed =
            Phase2a0bContracts.SceneId.parse(id);
        if (typed == Phase2a0bContracts.SceneId.UNKNOWN) {
            throw new IOException("unknown scene: " + id);
        }
        for (Scene scene : this.scenes) {
            if (scene.id() == typed) {
                if (scene.timeline() == null) {
                    throw new IOException(
                        "scene is not replay-ready: " + id
                    );
                }
                return scene;
            }
        }
        throw new IOException("unknown scene: " + id);
    }

    public Scene[] requireReadyScenes(String[] ids) throws IOException {
        if (ids == null || ids.length == 0) {
            throw new IOException("replay suite has no scenes");
        }
        Scene[] selected = new Scene[ids.length];
        for (int index = 0; index < ids.length; index++) {
            for (int earlier = 0; earlier < index; earlier++) {
                if (ids[index].equals(ids[earlier])) {
                    throw new IOException(
                        "duplicate replay scene: " + ids[index]
                    );
                }
            }
            selected[index] = requireReadyScene(ids[index]);
        }
        return selected;
    }

    private static Scene parseScene(
        JsonObject object,
        Phase2a0bContracts.SceneType type
    ) throws IOException {
        Phase2a0bContracts.SceneId id =
            Phase2a0bContracts.SceneId.parse(
                requiredString(object, "id")
            );
        if (id == Phase2a0bContracts.SceneId.UNKNOWN) {
            throw new IOException("unknown typed scene id");
        }
        int measureSeconds = object.get("measureSeconds").getAsInt();
        validateSceneContract(id, type, measureSeconds);
        String dimension = requiredString(object, "dimension");
        JsonElement positionElement = object.get("position");
        JsonArray keyframes = object.getAsJsonArray("cameraKeyframes");
        if (
            positionElement == null
                || positionElement.isJsonNull()
                || keyframes == null
                || keyframes.isEmpty()
        ) {
            return new Scene(
                id,
                type,
                dimension,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Float.NaN,
                Float.NaN,
                number(object, "fov").floatValue(),
                object.get("renderDistanceChunks").getAsInt(),
                object.get("simulationDistanceChunks").getAsInt(),
                object.get("resolutionWidth").getAsInt(),
                object.get("resolutionHeight").getAsInt(),
                requiredString(object, "windowMode"),
                object.get("vsync").getAsBoolean(),
                fpsLimit(object),
                object.get("worldClockTotalTicks").getAsLong(),
                requiredString(object, "weather"),
                object.get("warmupSeconds").getAsInt(),
                measureSeconds,
                requiredString(object, "requiredChunkState"),
                null,
                0L
            );
        }
        int count = keyframes.size();
        long[] time = new long[count];
        double[] x = new double[count];
        double[] y = new double[count];
        double[] z = new double[count];
        float[] yaw = new float[count];
        float[] pitch = new float[count];
        float[] fov = new float[count];
        for (int index = 0; index < count; index++) {
            JsonObject keyframe = keyframes.get(index).getAsJsonObject();
            time[index] = Math.multiplyExact(
                keyframe.get("timeMillis").getAsLong(),
                1_000_000L
            );
            JsonArray position = keyframe.getAsJsonArray("position");
            x[index] = position.get(0).getAsDouble();
            y[index] = position.get(1).getAsDouble();
            z[index] = position.get(2).getAsDouble();
            yaw[index] = keyframe.get("yaw").getAsFloat();
            pitch[index] = keyframe.get("pitch").getAsFloat();
            fov[index] = keyframe.get("fov").getAsFloat();
        }
        ReplayTimeline timeline = new ReplayTimeline(
            time,
            x,
            y,
            z,
            yaw,
            pitch,
            fov,
            ReplayTimeline.Interpolation.valueOf(
                requiredString(object, "interpolation")
            )
        );
        JsonArray first = positionElement.getAsJsonArray();
        long sceneHash = hashScene(
            id,
            type,
            dimension,
            timeline.hash64()
        );
        return new Scene(
            id,
            type,
            dimension,
            first.get(0).getAsDouble(),
            first.get(1).getAsDouble(),
            first.get(2).getAsDouble(),
            object.get("yaw").getAsFloat(),
            object.get("pitch").getAsFloat(),
            object.get("fov").getAsFloat(),
            object.get("renderDistanceChunks").getAsInt(),
            object.get("simulationDistanceChunks").getAsInt(),
            object.get("resolutionWidth").getAsInt(),
            object.get("resolutionHeight").getAsInt(),
            requiredString(object, "windowMode"),
            object.get("vsync").getAsBoolean(),
            fpsLimit(object),
            object.get("worldClockTotalTicks").getAsLong(),
            requiredString(object, "weather"),
            object.get("warmupSeconds").getAsInt(),
            measureSeconds,
            requiredString(object, "requiredChunkState"),
            timeline,
            sceneHash
        );
    }

    private static long hashScene(
        Phase2a0bContracts.SceneId id,
        Phase2a0bContracts.SceneType type,
        String dimension,
        long timelineHash
    ) {
        long hash = 0xcbf29ce484222325L;
        String sceneId = id.name();
        for (int index = 0; index < sceneId.length(); index++) {
            hash ^= sceneId.charAt(index);
            hash *= 0x100000001b3L;
        }
        String sceneType = type.name();
        for (int index = 0; index < sceneType.length(); index++) {
            hash ^= sceneType.charAt(index);
            hash *= 0x100000001b3L;
        }
        for (int index = 0; index < dimension.length(); index++) {
            hash ^= dimension.charAt(index);
            hash *= 0x100000001b3L;
        }
        hash ^= timelineHash;
        return hash * 0x100000001b3L;
    }

    static void validateSceneContract(
        Phase2a0bContracts.SceneId id,
        Phase2a0bContracts.SceneType type,
        int measureSeconds
    ) throws IOException {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Phase2a0bContracts.SceneType expected =
            id == Phase2a0bContracts.SceneId.DTC_IMAGE_REFERENCE
                ? Phase2a0bContracts.SceneType.IMAGE_REFERENCE
                : Phase2a0bContracts.SceneType.PERFORMANCE;
        if (type != expected) {
            throw new IOException(
                "scene type does not match scene id: " + id
            );
        }
        if (
            type == Phase2a0bContracts.SceneType.PERFORMANCE
                && measureSeconds <= 0
        ) {
            throw new IOException(
                "PERFORMANCE scene requires positive measureSeconds"
            );
        }
        if (
            type == Phase2a0bContracts.SceneType.IMAGE_REFERENCE
                && measureSeconds != 0
        ) {
            throw new IOException(
                "IMAGE_REFERENCE scene requires measureSeconds=0"
            );
        }
    }

    private static String requiredString(JsonObject object, String name)
        throws IOException {
        JsonElement element = object.get(name);
        if (
            element == null
                || element.isJsonNull()
                || element.getAsString().isBlank()
        ) {
            throw new IOException("missing scene field: " + name);
        }
        return element.getAsString();
    }

    private static Number number(JsonObject object, String name)
        throws IOException {
        Objects.requireNonNull(object, "object");
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            throw new IOException("missing scene number: " + name);
        }
        return element.getAsNumber();
    }

    private static int fpsLimit(JsonObject object) throws IOException {
        String value = requiredString(object, "fpsLimit");
        if ("UNLIMITED".equals(value)) {
            return 260;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IOException("invalid fpsLimit: " + value, error);
        }
    }
}

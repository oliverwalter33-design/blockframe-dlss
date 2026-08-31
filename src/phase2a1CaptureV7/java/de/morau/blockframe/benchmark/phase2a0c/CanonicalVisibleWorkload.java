package de.morau.blockframe.benchmark.phase2a0c;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/**
 * One development-only logical replay contract for Mojang-owned visible
 * sections. It owns no renderer, mesh, upload, Vulkan or BlockFrame object.
 */
final class CanonicalVisibleWorkload {
    static final String DISCOVER = "DISCOVER";
    static final String ENFORCE = "ENFORCE";
    private static final int SCHEMA_VERSION = 1;
    private static final String CONTRACT_ID =
        "greenfield-canonical-visible-workload-v1";
    private static final Gson GSON = new GsonBuilder()
        .disableHtmlEscaping()
        .create();

    private final Phase2a0cCaptureRuntime.Receipt receipt;
    private final String mode;
    private final Path path;
    private final String expectedFileSha256;
    private final long[] sectionNodes;
    private final long[] templateSignatures;
    private final String[] contentSha256;
    private final BlockPos[] lookupPositions;
    private final SectionRenderDispatcher.RenderSection[] resolved;
    private final String canonicalSetSha256;
    private final MatrixContract expectedMatrix;

    private boolean exactListApplied;
    private boolean templatesReady;
    private boolean preparedAtQuiescence;
    private int rawVisibleCount;
    private int filteredExtraCount;
    private String publishedFileSha256;
    private String discoveredCanonicalSetSha256;
    private int discoveredSectionCount;

    private CanonicalVisibleWorkload(
        Phase2a0cCaptureRuntime.Receipt receipt,
        String mode,
        Path path,
        String expectedFileSha256,
        long[] sectionNodes,
        long[] templateSignatures,
        String[] contentSha256,
        String canonicalSetSha256,
        MatrixContract expectedMatrix
    ) {
        this.receipt = receipt;
        this.mode = mode;
        this.path = path;
        this.expectedFileSha256 = expectedFileSha256;
        this.sectionNodes = sectionNodes;
        this.templateSignatures = templateSignatures;
        this.contentSha256 = contentSha256;
        this.canonicalSetSha256 = canonicalSetSha256;
        this.expectedMatrix = expectedMatrix;
        this.lookupPositions = new BlockPos[sectionNodes.length];
        this.resolved =
            new SectionRenderDispatcher.RenderSection[sectionNodes.length];
        for (int index = 0; index < sectionNodes.length; index++) {
            long node = sectionNodes[index];
            lookupPositions[index] = new BlockPos(
                SectionPos.x(node) << 4,
                SectionPos.y(node) << 4,
                SectionPos.z(node) << 4
            );
        }
    }

    static CanonicalVisibleWorkload load(
        Phase2a0cCaptureRuntime.Receipt receipt
    ) throws IOException, NoSuchAlgorithmException,
        Phase2a0cCaptureRuntime.ContractException {
        if (DISCOVER.equals(receipt.canonicalMode)) {
            if (!Phase2a0cCaptureRuntime.MOJANG_PROFILE.equals(
                receipt.profileId
            )) {
                throw new Phase2a0cCaptureRuntime.ContractException(
                    "CANONICAL_DISCOVERY_REQUIRES_NEUTRAL_MOJANG_PROFILE"
                );
            }
            if (Files.exists(receipt.canonicalVisibleWorkloadPath)) {
                throw new Phase2a0cCaptureRuntime.ContractException(
                    "CANONICAL_DISCOVERY_TARGET_ALREADY_EXISTS"
                );
            }
            return new CanonicalVisibleWorkload(
                receipt,
                DISCOVER,
                receipt.canonicalVisibleWorkloadPath,
                null,
                new long[0],
                new long[0],
                new String[0],
                null,
                null
            );
        }
        if (!ENFORCE.equals(receipt.canonicalMode)) {
            throw new Phase2a0cCaptureRuntime.ContractException(
                "CANONICAL_MODE_INVALID"
            );
        }
        byte[] bytes = Files.readAllBytes(
            receipt.canonicalVisibleWorkloadPath
        );
        String fileSha256 = sha256(bytes);
        if (!fileSha256.equals(
            receipt.canonicalVisibleWorkloadSha256
        )) {
            throw new Phase2a0cCaptureRuntime.ContractException(
                "CANONICAL_FILE_HASH_MISMATCH"
            );
        }
        JsonElement parsed = JsonParser.parseString(
            new String(bytes, StandardCharsets.UTF_8)
        );
        if (!parsed.isJsonObject()) {
            throw new Phase2a0cCaptureRuntime.ContractException(
                "CANONICAL_ROOT_NOT_OBJECT"
            );
        }
        JsonObject root = parsed.getAsJsonObject();
        requireInt(root, "schemaVersion", SCHEMA_VERSION);
        requireString(root, "contractId", CONTRACT_ID);
        requireString(root, "status", "CANONICAL_VISIBLE_WORKLOAD_READY");
        requireString(root, "sceneId", receipt.sceneId);
        requireString(root, "dimension", receipt.dimension);
        requireString(root, "goldenSha256", receipt.goldenSha256);
        requireString(root, "runSourceSha256", receipt.runCopySha256);
        requireString(
            root,
            "resourcePackSha256",
            receipt.resourcePackSha256
        );
        requireInt(
            root,
            "resolutionWidth",
            receipt.resolutionWidth
        );
        requireInt(
            root,
            "resolutionHeight",
            receipt.resolutionHeight
        );
        requireInt(
            root,
            "renderDistanceChunks",
            receipt.renderDistanceChunks
        );
        requireInt(
            root,
            "simulationDistanceChunks",
            receipt.simulationDistanceChunks
        );
        requireInt(root, "configuredFov", receipt.fov);
        requireInt(
            root,
            "guiScaleOption",
            receipt.guiScaleOption
        );
        requireBoolean(root, "vsync", receipt.vsync);
        requireInt(root, "fpsLimit", receipt.fpsLimit);
        requireLong(
            root,
            "fixedGameTime",
            receipt.fixedGameTime
        );
        requireLong(root, "fixedDayTime", receipt.route.fixedDayTime());

        JsonArray sections = requireArray(root, "sections");
        if (sections.isEmpty() || sections.size() > 100_000) {
            throw new Phase2a0cCaptureRuntime.ContractException(
                "CANONICAL_SECTION_COUNT_INVALID"
            );
        }
        long[] nodes = new long[sections.size()];
        long[] templates = new long[sections.size()];
        String[] content = new String[sections.size()];
        for (int index = 0; index < sections.size(); index++) {
            JsonObject section = requireObject(sections.get(index));
            nodes[index] = requireLong(section, "sectionNode");
            templates[index] = requireLong(
                section,
                "logicalMeshTemplateSignature"
            );
            content[index] = requireHash(
                section,
                "logicalBlockStateSha256"
            );
            requireInt(section, "sectionX", SectionPos.x(nodes[index]));
            requireInt(section, "sectionY", SectionPos.y(nodes[index]));
            requireInt(section, "sectionZ", SectionPos.z(nodes[index]));
        }
        String setHash = requireHash(root, "canonicalSetSha256");
        if (!setHash.equals(sectionSetSha256(nodes))) {
            throw new Phase2a0cCaptureRuntime.ContractException(
                "CANONICAL_SET_HASH_MISMATCH"
            );
        }
        ensureUnique(nodes);
        MatrixContract matrix = MatrixContract.fromJson(
            requireObject(root.get("matrixContract"))
        );
        CanonicalVisibleWorkload result =
            new CanonicalVisibleWorkload(
                receipt,
                ENFORCE,
                receipt.canonicalVisibleWorkloadPath,
                fileSha256,
                nodes,
                templates,
                content,
                setHash,
                matrix
            );
        result.publishedFileSha256 = fileSha256;
        return result;
    }

    boolean canonicalize(
        List<SectionRenderDispatcher.RenderSection> visible,
        @Nullable ViewArea viewArea
    ) {
        if (DISCOVER.equals(mode)) {
            rawVisibleCount = visible == null ? 0 : visible.size();
            exactListApplied = true;
            templatesReady = true;
            return true;
        }
        exactListApplied = false;
        templatesReady = false;
        if (visible == null || viewArea == null) {
            return false;
        }
        rawVisibleCount = visible.size();
        boolean allTemplatesReady = true;
        for (int index = 0; index < sectionNodes.length; index++) {
            SectionRenderDispatcher.RenderSection section =
                viewArea.getRenderSectionAt(lookupPositions[index]);
            if (
                section == null
                    || section.getSectionNode() != sectionNodes[index]
            ) {
                return false;
            }
            resolved[index] = section;
            if (
                templateSignature(section.getSectionMesh())
                    != templateSignatures[index]
            ) {
                allTemplatesReady = false;
            }
        }
        visible.clear();
        for (SectionRenderDispatcher.RenderSection section : resolved) {
            visible.add(section);
        }
        exactListApplied = true;
        templatesReady = allTemplatesReady;
        filteredExtraCount = Math.max(
            0,
            rawVisibleCount - sectionNodes.length
        );
        return allTemplatesReady;
    }

    void prepareAtQuiescence(
        Minecraft minecraft,
        Camera camera,
        List<SectionRenderDispatcher.RenderSection> visible,
        int compileQueueSize,
        long uploadBacklog
    ) throws IOException, NoSuchAlgorithmException,
        Phase2a0cCaptureRuntime.ContractException {
        if (preparedAtQuiescence) {
            return;
        }
        if (DISCOVER.equals(mode)) {
            discover(
                minecraft,
                camera,
                visible,
                compileQueueSize,
                uploadBacklog
            );
        } else {
            if (!exactListApplied || !templatesReady) {
                throw new Phase2a0cCaptureRuntime.ContractException(
                    "CANONICAL_LIST_NOT_EXACT_AT_QUIESCENCE"
                );
            }
            if (!canonicalSetSha256.equals(
                sectionSetSha256(currentNodes(visible))
            )) {
                throw new Phase2a0cCaptureRuntime.ContractException(
                    "CANONICAL_VISIBLE_SET_CHANGED"
                );
            }
            MatrixContract actualMatrix = MatrixContract.capture(camera);
            if (!expectedMatrix.bitExactEquals(actualMatrix)) {
                throw new Phase2a0cCaptureRuntime.ContractException(
                    "CANONICAL_CAMERA_MATRIX_MISMATCH"
                );
            }
            for (int index = 0; index < sectionNodes.length; index++) {
                String actual = logicalBlockStateSha256(
                    minecraft,
                    sectionNodes[index]
                );
                if (!contentSha256[index].equals(actual)) {
                    throw new Phase2a0cCaptureRuntime.ContractException(
                        "CANONICAL_LOGICAL_CONTENT_MISMATCH_"
                            + sectionNodes[index]
                    );
                }
            }
        }
        preparedAtQuiescence = true;
    }

    private void discover(
        Minecraft minecraft,
        Camera camera,
        List<SectionRenderDispatcher.RenderSection> visible,
        int compileQueueSize,
        long uploadBacklog
    ) throws IOException, NoSuchAlgorithmException,
        Phase2a0cCaptureRuntime.ContractException {
        if (
            visible == null
                || visible.isEmpty()
                || compileQueueSize != 0
                || uploadBacklog != 0L
        ) {
            throw new Phase2a0cCaptureRuntime.ContractException(
                "CANONICAL_DISCOVERY_NOT_QUIESCENT"
            );
        }
        SectionRenderDispatcher.RenderSection[] discovered =
            visible.toArray(
                SectionRenderDispatcher.RenderSection[]::new
            );
        Arrays.sort(
            discovered,
            Comparator.comparingLong(
                SectionRenderDispatcher.RenderSection::getSectionNode
            )
        );
        long[] nodes = new long[discovered.length];
        for (int index = 0; index < discovered.length; index++) {
            nodes[index] = discovered[index].getSectionNode();
        }
        ensureUnique(nodes);
        String setHash = sectionSetSha256(nodes);
        MatrixContract matrix = MatrixContract.capture(camera);
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("contractId", CONTRACT_ID);
        root.addProperty(
            "status",
            "CANONICAL_VISIBLE_WORKLOAD_READY"
        );
        root.addProperty("producerProfile", receipt.profileId);
        root.addProperty("producerRunId", receipt.runId);
        root.addProperty("sceneId", receipt.sceneId);
        root.addProperty("dimension", receipt.dimension);
        root.addProperty("goldenSha256", receipt.goldenSha256);
        root.addProperty("runSourceSha256", receipt.runCopySha256);
        root.addProperty(
            "resourcePackSha256",
            receipt.resourcePackSha256
        );
        root.addProperty("resolutionWidth", receipt.resolutionWidth);
        root.addProperty("resolutionHeight", receipt.resolutionHeight);
        root.addProperty(
            "renderDistanceChunks",
            receipt.renderDistanceChunks
        );
        root.addProperty(
            "simulationDistanceChunks",
            receipt.simulationDistanceChunks
        );
        root.addProperty("configuredFov", receipt.fov);
        root.addProperty(
            "guiScaleOption",
            receipt.guiScaleOption
        );
        root.addProperty("vsync", receipt.vsync);
        root.addProperty("fpsLimit", receipt.fpsLimit);
        root.addProperty("fixedGameTime", receipt.fixedGameTime);
        root.addProperty(
            "fixedDayTime",
            receipt.route.fixedDayTime()
        );
        root.addProperty("canonicalSetSha256", setHash);
        root.addProperty("sectionCount", nodes.length);
        root.addProperty(
            "ownership",
            "LOGICAL_SECTION_IDENTITIES_ONLY_NO_GPU_OR_VULKAN_HANDLES"
        );
        root.addProperty(
            "captureTiming",
            "ONCE_AFTER_QUIESCENCE_BEFORE_WARMUP_AND_MEASURE"
        );
        root.add("matrixContract", matrix.toJson());
        JsonArray entries = new JsonArray(nodes.length);
        for (int index = 0; index < visible.size(); index++) {
            SectionRenderDispatcher.RenderSection section =
                discovered[index];
            long node = section.getSectionNode();
            SectionMesh mesh = section.getSectionMesh();
            JsonObject entry = new JsonObject();
            entry.addProperty("sectionNode", node);
            entry.addProperty("sectionX", SectionPos.x(node));
            entry.addProperty("sectionY", SectionPos.y(node));
            entry.addProperty("sectionZ", SectionPos.z(node));
            entry.addProperty(
                "logicalMeshTemplateSignature",
                templateSignature(mesh)
            );
            entry.addProperty(
                "logicalBlockStateSha256",
                logicalBlockStateSha256(minecraft, node)
            );
            entry.addProperty(
                "logicalContentContract",
                "4096_BLOCK_STATE_REGISTRY_IDS_XYZ_ORDER"
            );
            entry.addProperty(
                "meshPublicationState",
                "PUBLISHED_COMPILE_AND_UPLOAD_BACKLOG_ZERO"
            );
            entry.addProperty(
                "bufferGeneration",
                "PROCESS_LOCAL_EXCLUDED_FROM_CANONICAL_MANIFEST"
            );
            JsonArray layers = new JsonArray();
            for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                SectionMesh.SectionDraw draw =
                    mesh.getSectionDraw(layer);
                if (draw == null) {
                    continue;
                }
                JsonObject layerEntry = new JsonObject();
                layerEntry.addProperty("layer", layer.name());
                layerEntry.addProperty(
                    "eligibleOpaqueSolid",
                    layer == ChunkSectionLayer.SOLID
                );
                layerEntry.addProperty(
                    "indexCount",
                    draw.indexCount()
                );
                JsonObject vertexCount = new JsonObject();
                vertexCount.addProperty("status", "NOT_AVAILABLE");
                vertexCount.addProperty(
                    "reason",
                    "MOJANG_SECTION_DRAW_ABI_EXPOSES_INDEX_COUNT_ONLY"
                );
                layerEntry.add("vertexCount", vertexCount);
                layerEntry.addProperty(
                    "indexTypeBytes",
                    draw.indexType().bytes
                );
                layerEntry.addProperty(
                    "customIndexBuffer",
                    draw.hasCustomIndexBuffer()
                );
                layerEntry.addProperty(
                    "shaderMaterialContract",
                    layer == ChunkSectionLayer.SOLID
                        ? "minecraft:pipeline/solid_terrain|minecraft:block-atlas:solid"
                        : "MOJANG_LAYER_OWNED"
                );
                layers.add(layerEntry);
            }
            entry.add("layers", layers);
            entries.add(entry);
        }
        root.add("sections", entries);
        publishWithoutReplacement(path, GSON.toJson(root));
        publishedFileSha256 = sha256(Files.readAllBytes(path));
        discoveredCanonicalSetSha256 = setHash;
        discoveredSectionCount = nodes.length;
    }

    boolean readyForGate() {
        return DISCOVER.equals(mode)
            || (exactListApplied && templatesReady);
    }

    String gateRejectionReason() {
        if (DISCOVER.equals(mode) || readyForGate()) {
            return null;
        }
        return !exactListApplied
            ? "CANONICAL_SECTION_OWNER_NOT_RESOLVED"
            : "CANONICAL_SECTION_TEMPLATE_NOT_READY";
    }

    String mode() {
        return mode;
    }

    String canonicalSetSha256() {
        return canonicalSetSha256;
    }

    String fileSha256() {
        return publishedFileSha256 != null
            ? publishedFileSha256
            : expectedFileSha256;
    }

    JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("mode", mode);
        root.addProperty(
            "status",
            preparedAtQuiescence
                ? "PASSED"
                : readyForGate()
                    ? "READY_FOR_QUIESCENCE_VERIFICATION"
                    : "NOT_READY"
        );
        root.addProperty("path", path.toString());
        if (fileSha256() != null) {
            root.addProperty("fileSha256", fileSha256());
        }
        String reportedSetSha256 = canonicalSetSha256 != null
            ? canonicalSetSha256
            : discoveredCanonicalSetSha256;
        if (reportedSetSha256 != null) {
            root.addProperty(
                "canonicalSetSha256",
                reportedSetSha256
            );
        }
        root.addProperty(
            "canonicalSectionCount",
            DISCOVER.equals(mode)
                ? discoveredSectionCount
                : sectionNodes.length
        );
        root.addProperty("rawVisibleCountLastFrame", rawVisibleCount);
        root.addProperty(
            "filteredExtraSectionsLastFrame",
            filteredExtraCount
        );
        root.addProperty("exactListApplied", exactListApplied);
        root.addProperty("templatesReady", templatesReady);
        root.addProperty(
            "manifestReadsDuringMeasure",
            0
        );
        root.addProperty(
            "fileIoDuringMeasure",
            0
        );
        return root;
    }

    static MatrixContract captureMatrix(Camera camera) {
        return MatrixContract.capture(camera);
    }

    private static long[] currentNodes(
        List<SectionRenderDispatcher.RenderSection> visible
    ) {
        long[] nodes = new long[visible.size()];
        for (int index = 0; index < visible.size(); index++) {
            nodes[index] = visible.get(index).getSectionNode();
        }
        return nodes;
    }

    private static long templateSignature(SectionMesh mesh) {
        long hash = 0xcbf29ce484222325L;
        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            SectionMesh.SectionDraw draw = mesh.getSectionDraw(layer);
            long value = layer.ordinal() + 1L;
            if (draw != null) {
                value ^= ((long) draw.indexCount() << 32);
                value ^= ((long) draw.indexType().bytes << 8);
                value ^= draw.hasCustomIndexBuffer() ? 1L : 0L;
            }
            hash ^= value;
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static String logicalBlockStateSha256(
        Minecraft minecraft,
        long sectionNode
    ) throws NoSuchAlgorithmException,
        Phase2a0cCaptureRuntime.ContractException {
        if (minecraft == null || minecraft.level == null) {
            throw new Phase2a0cCaptureRuntime.ContractException(
                "CANONICAL_WORLD_UNAVAILABLE"
            );
        }
        int sectionX = SectionPos.x(sectionNode);
        int sectionY = SectionPos.y(sectionNode);
        int sectionZ = SectionPos.z(sectionNode);
        LevelChunk chunk = minecraft.level.getChunk(sectionX, sectionZ);
        if (
            sectionY < chunk.getMinSectionY()
                || sectionY > chunk.getMaxSectionY()
        ) {
            throw new Phase2a0cCaptureRuntime.ContractException(
                "CANONICAL_SECTION_Y_OUT_OF_RANGE_" + sectionNode
            );
        }
        LevelChunkSection section = chunk.getSection(
            chunk.getSectionIndexFromSectionY(sectionY)
        );
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    updateInt(
                        digest,
                        Block.getId(section.getBlockState(x, y, z))
                    );
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sectionSetSha256(long[] nodes)
        throws NoSuchAlgorithmException {
        long[] sorted = nodes.clone();
        Arrays.sort(sorted);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (long node : sorted) {
            updateLong(digest, node);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void ensureUnique(long[] nodes)
        throws Phase2a0cCaptureRuntime.ContractException {
        long[] sorted = nodes.clone();
        Arrays.sort(sorted);
        for (int index = 1; index < sorted.length; index++) {
            if (sorted[index] == sorted[index - 1]) {
                throw new Phase2a0cCaptureRuntime.ContractException(
                    "CANONICAL_DUPLICATE_SECTION_NODE"
                );
            }
        }
    }

    private static void publishWithoutReplacement(Path target, String json)
        throws IOException {
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IOException("CANONICAL_PARENT_MISSING");
        }
        Path temporary = parent.resolve(
            target.getFileName() + ".tmp-" + ProcessHandle.current().pid()
        );
        Files.writeString(
            temporary,
            json,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        );
        try {
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String sha256(byte[] bytes)
        throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(bytes)
        );
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static void updateLong(MessageDigest digest, long value) {
        digest.update((byte) (value >>> 56));
        digest.update((byte) (value >>> 48));
        digest.update((byte) (value >>> 40));
        digest.update((byte) (value >>> 32));
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static JsonObject requireObject(JsonElement element)
        throws Phase2a0cCaptureRuntime.ContractException {
        if (element == null || !element.isJsonObject()) {
            throw new Phase2a0cCaptureRuntime.ContractException(
                "CANONICAL_OBJECT_REQUIRED"
            );
        }
        return element.getAsJsonObject();
    }

    private static JsonArray requireArray(JsonObject root, String name)
        throws Phase2a0cCaptureRuntime.ContractException {
        JsonElement element = root.get(name);
        if (element == null || !element.isJsonArray()) {
            throw new Phase2a0cCaptureRuntime.ContractException(
                "CANONICAL_ARRAY_REQUIRED_" + name
            );
        }
        return element.getAsJsonArray();
    }

    private static String requireHash(JsonObject root, String name)
        throws Phase2a0cCaptureRuntime.ContractException {
        String value = requireString(root, name);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new Phase2a0cCaptureRuntime.ContractException(
                "CANONICAL_HASH_INVALID_" + name
            );
        }
        return value;
    }

    private static String requireString(JsonObject root, String name)
        throws Phase2a0cCaptureRuntime.ContractException {
        JsonElement element = root.get(name);
        if (
            element == null
                || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()
                || element.getAsString().isBlank()
        ) {
            throw new Phase2a0cCaptureRuntime.ContractException(
                "CANONICAL_STRING_REQUIRED_" + name
            );
        }
        return element.getAsString();
    }

    private static void requireString(
        JsonObject root,
        String name,
        String expected
    ) throws Phase2a0cCaptureRuntime.ContractException {
        if (!expected.equals(requireString(root, name))) {
            throw new Phase2a0cCaptureRuntime.ContractException(
                "CANONICAL_VALUE_MISMATCH_" + name
            );
        }
    }

    private static long requireLong(JsonObject root, String name)
        throws Phase2a0cCaptureRuntime.ContractException {
        JsonElement element = root.get(name);
        try {
            if (
                element == null
                    || !element.isJsonPrimitive()
                    || !element.getAsJsonPrimitive().isNumber()
            ) {
                throw new NumberFormatException();
            }
            return element.getAsLong();
        } catch (RuntimeException error) {
            throw new Phase2a0cCaptureRuntime.ContractException(
                "CANONICAL_LONG_REQUIRED_" + name,
                error
            );
        }
    }

    private static void requireLong(
        JsonObject root,
        String name,
        long expected
    ) throws Phase2a0cCaptureRuntime.ContractException {
        if (requireLong(root, name) != expected) {
            throw new Phase2a0cCaptureRuntime.ContractException(
                "CANONICAL_VALUE_MISMATCH_" + name
            );
        }
    }

    private static void requireInt(
        JsonObject root,
        String name,
        int expected
    ) throws Phase2a0cCaptureRuntime.ContractException {
        long value = requireLong(root, name);
        if (value != expected) {
            throw new Phase2a0cCaptureRuntime.ContractException(
                "CANONICAL_VALUE_MISMATCH_" + name
            );
        }
    }

    private static void requireBoolean(
        JsonObject root,
        String name,
        boolean expected
    ) throws Phase2a0cCaptureRuntime.ContractException {
        JsonElement element = root.get(name);
        if (
            element == null
                || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isBoolean()
                || element.getAsBoolean() != expected
        ) {
            throw new Phase2a0cCaptureRuntime.ContractException(
                "CANONICAL_VALUE_MISMATCH_" + name
            );
        }
    }

    record MatrixContract(
        int[] viewMatrixRawBits,
        int[] projectionMatrixRawBits,
        int fovRawBits,
        int nearRawBits,
        int farRawBits
    ) {
        MatrixContract {
            if (
                viewMatrixRawBits == null
                    || viewMatrixRawBits.length != 16
                    || projectionMatrixRawBits == null
                    || projectionMatrixRawBits.length != 16
            ) {
                throw new IllegalArgumentException(
                    "two 4x4 matrices required"
                );
            }
            viewMatrixRawBits = viewMatrixRawBits.clone();
            projectionMatrixRawBits =
                projectionMatrixRawBits.clone();
        }

        static MatrixContract capture(Camera camera) {
            Matrix4f view = camera.getViewRotationMatrix(
                new Matrix4f()
            );
            Projection projection =
                ((de.morau.blockframe.benchmark.phase2a0c.mixin
                        .Phase2a0cCameraInvoker) camera)
                    .blockframe$phase2a1Projection();
            Matrix4f projectionMatrix = projection.getMatrix(
                new Matrix4f()
            );
            return new MatrixContract(
                rawBits(view),
                rawBits(projectionMatrix),
                Float.floatToRawIntBits(camera.getFov()),
                Float.floatToRawIntBits(projection.zNear()),
                Float.floatToRawIntBits(projection.zFar())
            );
        }

        static MatrixContract fromJson(JsonObject root)
            throws Phase2a0cCaptureRuntime.ContractException {
            return new MatrixContract(
                rawBitsFromJson(
                    requireArray(root, "viewMatrixRawBits")
                ),
                rawBitsFromJson(
                    requireArray(root, "projectionMatrixRawBits")
                ),
                (int) requireLong(root, "fovRawBits"),
                (int) requireLong(root, "nearRawBits"),
                (int) requireLong(root, "farRawBits")
            );
        }

        boolean bitExactEquals(MatrixContract other) {
            return other != null
                && Arrays.equals(
                    viewMatrixRawBits,
                    other.viewMatrixRawBits
                )
                && Arrays.equals(
                    projectionMatrixRawBits,
                    other.projectionMatrixRawBits
                )
                && fovRawBits == other.fovRawBits
                && nearRawBits == other.nearRawBits
                && farRawBits == other.farRawBits;
        }

        JsonObject toJson() {
            JsonObject root = new JsonObject();
            root.add(
                "viewMatrixRawBits",
                rawBitsToJson(viewMatrixRawBits)
            );
            root.add(
                "projectionMatrixRawBits",
                rawBitsToJson(projectionMatrixRawBits)
            );
            root.addProperty(
                "fovRawBits",
                Integer.toUnsignedLong(fovRawBits)
            );
            root.addProperty(
                "nearRawBits",
                Integer.toUnsignedLong(nearRawBits)
            );
            root.addProperty(
                "farRawBits",
                Integer.toUnsignedLong(farRawBits)
            );
            root.addProperty("fov", Float.intBitsToFloat(fovRawBits));
            root.addProperty(
                "nearPlane",
                Float.intBitsToFloat(nearRawBits)
            );
            root.addProperty(
                "farPlane",
                Float.intBitsToFloat(farRawBits)
            );
            return root;
        }

        @Override
        public int[] viewMatrixRawBits() {
            return viewMatrixRawBits.clone();
        }

        @Override
        public int[] projectionMatrixRawBits() {
            return projectionMatrixRawBits.clone();
        }

        private static int[] rawBits(Matrix4f matrix) {
            float[] values = new float[16];
            matrix.get(values);
            int[] result = new int[16];
            for (int index = 0; index < values.length; index++) {
                result[index] = Float.floatToRawIntBits(values[index]);
            }
            return result;
        }

        private static int[] rawBitsFromJson(JsonArray values)
            throws Phase2a0cCaptureRuntime.ContractException {
            if (values.size() != 16) {
                throw new Phase2a0cCaptureRuntime.ContractException(
                    "CANONICAL_MATRIX_SIZE_INVALID"
                );
            }
            int[] result = new int[16];
            for (int index = 0; index < result.length; index++) {
                long value;
                try {
                    value = values.get(index).getAsLong();
                } catch (RuntimeException error) {
                    throw new Phase2a0cCaptureRuntime.ContractException(
                        "CANONICAL_MATRIX_BITS_INVALID",
                        error
                    );
                }
                if (value < 0L || value > 0xffff_ffffL) {
                    throw new Phase2a0cCaptureRuntime.ContractException(
                        "CANONICAL_MATRIX_BITS_OUT_OF_RANGE"
                    );
                }
                result[index] = (int) value;
            }
            return result;
        }

        private static JsonArray rawBitsToJson(int[] values) {
            JsonArray result = new JsonArray(values.length);
            for (int value : values) {
                result.add(Integer.toUnsignedLong(value));
            }
            return result;
        }
    }
}

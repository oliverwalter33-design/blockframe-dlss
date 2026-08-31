package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

final class BricksCompatibilityArtifactTest {
    private static final String BRICKS_PROPERTY =
        "blockframe.bricksCompatEvidenceJar";
    private static final String MINECRAFT_PROPERTY =
        "blockframe.minecraft26057PatchedEvidenceJar";
    private static final String DISPATCHER_RESOURCE =
        "net/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher.class";
    private static final String RENDERER_INTERFACE_RESOURCE =
        "net/minecraft/client/renderer/blockentity/BlockEntityRenderer.class";
    private static final String MINECRAFT_RESOURCE =
        "net/minecraft/client/Minecraft.class";
    private static final String OPTIONS_RESOURCE =
        "net/minecraft/client/Options.class";
    private static final String LEVEL_RENDERER_RESOURCE =
        "net/minecraft/client/renderer/LevelRenderer.class";
    private static final String BLOCK_ENTITY_STATE_RESOURCE =
        "net/minecraft/client/renderer/blockentity/state/"
            + "BlockEntityRenderState.class";
    private static final String CAMERA_STATE_RESOURCE =
        "net/minecraft/client/renderer/state/level/CameraRenderState.class";
    private static final String ORDERED_COLLECTOR_RESOURCE =
        "net/minecraft/client/renderer/OrderedSubmitNodeCollector.class";
    private static final String RENDER_TYPES_RESOURCE =
        "net/minecraft/client/renderer/rendertype/RenderTypes.class";

    private static Path bricksJar;
    private static Path minecraftJar;
    private static BricksCompatibility.Evidence exactEvidence;

    @BeforeAll
    static void loadFrozenEvidence() throws IOException {
        bricksJar = requiredJar(BRICKS_PROPERTY);
        minecraftJar = requiredJar(MINECRAFT_PROPERTY);
        exactEvidence = new BricksCompatibility.Evidence(
            metadataValue(bricksJar, "modId"),
            metadataValue(bricksJar, "version"),
            sha256(Files.readAllBytes(bricksJar)),
            entry(bricksJar, BricksCompatibility.COMPOSITE_RENDERER_RESOURCE),
            entry(minecraftJar, DISPATCHER_RESOURCE),
            entry(minecraftJar, RENDERER_INTERFACE_RESOURCE),
            entry(minecraftJar, MINECRAFT_RESOURCE),
            entry(minecraftJar, OPTIONS_RESOURCE),
            entry(minecraftJar, LEVEL_RENDERER_RESOURCE),
            entry(minecraftJar, BLOCK_ENTITY_STATE_RESOURCE),
            entry(minecraftJar, CAMERA_STATE_RESOURCE),
            entry(minecraftJar, ORDERED_COLLECTOR_RESOURCE),
            entry(minecraftJar, RENDER_TYPES_RESOURCE)
        );
    }

    @Test
    void frozenActiveBricksAndPlatformContractEnablesAtSafeDefault() {
        BricksCompatibility.Decision decision = BricksCompatibility.evaluate(
            exactEvidence,
            null
        );

        assertTrue(decision.enabled(), decision::detail);
        assertEquals(
            BricksCompatibility.DEFAULT_VIEW_DISTANCE_BLOCKS,
            decision.configuredViewDistanceBlocks()
        );
        assertEquals(96, decision.configuredViewDistanceBlocks());
        assertEquals(
            BricksCompatibility.EXACT_ARTIFACT_SHA256,
            exactEvidence.artifactSha256()
        );
    }

    @Test
    void activeOwnerClassInheritsRatherThanDeclaresTheVanillaDistanceMethod() {
        ClassNode node = new ClassNode();
        new ClassReader(exactEvidence.compositeRenderer()).accept(
            node,
            ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES
        );

        assertTrue(node.methods.stream().noneMatch(method ->
            "getViewDistance".equals(method.name) && "()I".equals(method.desc)
        ));
        assertEquals(
            BricksCompatibility.COMPOSITE_RENDERER_SHA256,
            sha256(exactEvidence.compositeRenderer())
        );
    }

    @Test
    void propertyAllows64NegativeControlAndBoundedDiagnostics() {
        for (String value : new String[] {"64", "96", "128", "160"}) {
            BricksCompatibility.Decision decision = BricksCompatibility.evaluate(
                exactEvidence,
                value
            );
            assertTrue(decision.enabled(), () -> value + ": " + decision.detail());
            assertEquals(Integer.parseInt(value), decision.configuredViewDistanceBlocks());
        }
    }

    @Test
    void invalidOrExcessivePropertyFailsClosed() {
        for (String value : new String[] {"63", "161", "512", "invalid"}) {
            BricksCompatibility.Decision decision = BricksCompatibility.evaluate(
                exactEvidence,
                value
            );
            assertFalse(decision.enabled(), () -> "accepted " + value);
        }
    }

    @Test
    void everyIdentityOrBytecodeDriftFailsClosed() {
        assertFalse(BricksCompatibility.evaluate(copy(
            "not-bricks",
            exactEvidence.version(),
            exactEvidence.artifactSha256(),
            exactEvidence.compositeRenderer(),
            exactEvidence.dispatcher(),
            exactEvidence.rendererInterface(),
            exactEvidence.minecraft(),
            exactEvidence.options(),
            exactEvidence.levelRenderer(),
            exactEvidence.blockEntityState(),
            exactEvidence.cameraState(),
            exactEvidence.orderedCollector(),
            exactEvidence.renderTypes()
        ), null).enabled());
        assertFalse(BricksCompatibility.evaluate(copy(
            exactEvidence.modId(),
            "1.0.2",
            exactEvidence.artifactSha256(),
            exactEvidence.compositeRenderer(),
            exactEvidence.dispatcher(),
            exactEvidence.rendererInterface(),
            exactEvidence.minecraft(),
            exactEvidence.options(),
            exactEvidence.levelRenderer(),
            exactEvidence.blockEntityState(),
            exactEvidence.cameraState(),
            exactEvidence.orderedCollector(),
            exactEvidence.renderTypes()
        ), null).enabled());
        assertFalse(BricksCompatibility.evaluate(copy(
            exactEvidence.modId(),
            exactEvidence.version(),
            "0".repeat(64),
            exactEvidence.compositeRenderer(),
            exactEvidence.dispatcher(),
            exactEvidence.rendererInterface(),
            exactEvidence.minecraft(),
            exactEvidence.options(),
            exactEvidence.levelRenderer(),
            exactEvidence.blockEntityState(),
            exactEvidence.cameraState(),
            exactEvidence.orderedCollector(),
            exactEvidence.renderTypes()
        ), null).enabled());

        byte[][] classes = {
            exactEvidence.compositeRenderer(),
            exactEvidence.dispatcher(),
            exactEvidence.rendererInterface(),
            exactEvidence.minecraft(),
            exactEvidence.options(),
            exactEvidence.levelRenderer(),
            exactEvidence.blockEntityState(),
            exactEvidence.cameraState(),
            exactEvidence.orderedCollector(),
            exactEvidence.renderTypes()
        };
        for (int index = 0; index < classes.length; index++) {
            int changedClassIndex = index;
            byte[][] changed = classes.clone();
            changed[index] = changed[index].clone();
            changed[index][changed[index].length - 1] ^= 1;
            BricksCompatibility.Evidence evidence = copy(
                exactEvidence.modId(),
                exactEvidence.version(),
                exactEvidence.artifactSha256(),
                changed[0],
                changed[1],
                changed[2],
                changed[3],
                changed[4],
                changed[5],
                changed[6],
                changed[7],
                changed[8],
                changed[9]
            );
            assertFalse(
                BricksCompatibility.evaluate(evidence, null).enabled(),
                () -> "accepted changed class index " + changedClassIndex
            );
        }
    }

    private static BricksCompatibility.Evidence copy(
        String modId,
        String version,
        String artifactSha256,
        byte[] composite,
        byte[] dispatcher,
        byte[] rendererInterface,
        byte[] minecraft,
        byte[] options,
        byte[] levelRenderer,
        byte[] blockEntityState,
        byte[] cameraState,
        byte[] orderedCollector,
        byte[] renderTypes
    ) {
        return new BricksCompatibility.Evidence(
            modId,
            version,
            artifactSha256,
            composite,
            dispatcher,
            rendererInterface,
            minecraft,
            options,
            levelRenderer,
            blockEntityState,
            cameraState,
            orderedCollector,
            renderTypes
        );
    }

    private static Path requiredJar(String property) throws IOException {
        String raw = System.getProperty(property);
        if (raw == null || raw.isBlank()) {
            throw new IOException("Missing test property " + property);
        }
        Path path = Path.of(raw).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IOException("Missing evidence JAR " + path);
        }
        return path;
    }

    private static byte[] entry(Path jar, String resource) throws IOException {
        try (JarFile file = new JarFile(jar.toFile(), false)) {
            JarEntry entry = file.getJarEntry(resource);
            if (entry == null || entry.isDirectory()) {
                throw new IOException("Missing " + resource + " in " + jar);
            }
            try (var input = file.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    private static String metadataValue(Path jar, String key) throws IOException {
        String metadata = new String(
            entry(jar, "META-INF/neoforge.mods.toml"),
            StandardCharsets.UTF_8
        );
        Matcher matcher = Pattern.compile(
            "(?m)^" + Pattern.quote(key) + "\\s*=\\s*\"([^\"]+)\""
        ).matcher(metadata);
        if (!matcher.find()) {
            throw new IOException("Missing metadata key " + key);
        }
        return matcher.group(1);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().withUpperCase().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

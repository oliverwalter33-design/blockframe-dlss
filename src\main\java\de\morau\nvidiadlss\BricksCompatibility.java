package de.morau.nvidiadlss;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Exact, fail-closed gate for the Bricks 1.0.1 composite-renderer workaround.
 *
 * <p>This compatibility path is deliberately independent from terrain,
 * sampler, and temporal-upscaling policy. It accepts only the observed Bricks
 * artifact and the exact Minecraft 26.2 / NeoForge 26.2.0.57 classes whose
 * call site and view-distance APIs it uses.
 */
public final class BricksCompatibility {
    public static final String VIEW_DISTANCE_PROPERTY =
        "blockframe.compat.bricksCompositeViewDistanceBlocks";
    public static final int MIN_VIEW_DISTANCE_BLOCKS = 64;
    public static final int DEFAULT_VIEW_DISTANCE_BLOCKS = 96;
    public static final int MAX_VIEW_DISTANCE_BLOCKS = 160;

    static final String MOD_ID = "bricks";
    static final String EXACT_VERSION = "1.0.1";
    static final String EXACT_ARTIFACT_SHA256 =
        "B380F3678A0AB1E0BA3375994FD309D72638F5CA3503C931D40E93AEA79426B8";
    static final String COMPOSITE_RENDERER_CLASS =
        "com.matnx.omni.client.micro.CompositeBlockEntityRenderer";
    static final String COMPOSITE_RENDERER_RESOURCE =
        "com/matnx/omni/client/micro/CompositeBlockEntityRenderer.class";
    static final String COMPOSITE_RENDERER_SHA256 =
        "05BA4BBFAB703E50256D5B6CE822F33763612CC734C0C81E85F74D39E4AFB677";

    private static final String DISPATCHER_RESOURCE =
        "net/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher.class";
    private static final String DISPATCHER_SHA256 =
        "BCC8AADD8FCD4AEE1BEA4CF6E912D682FE459024072BCFE6D97AA3134343A4A5";
    private static final String RENDERER_INTERFACE_RESOURCE =
        "net/minecraft/client/renderer/blockentity/BlockEntityRenderer.class";
    private static final String RENDERER_INTERFACE_SHA256 =
        "58598A070C609B33540F3BA841E7033E993FF5F74BA8F139F7568CC3C2D6ED33";
    private static final String MINECRAFT_RESOURCE =
        "net/minecraft/client/Minecraft.class";
    private static final String MINECRAFT_SHA256 =
        "5B77C1E327D21465C4DE5D0DA8F072DC0BC20348A3AFE0B1362A88C5919F2CA6";
    private static final String OPTIONS_RESOURCE =
        "net/minecraft/client/Options.class";
    private static final String OPTIONS_SHA256 =
        "9D86E8E0B8B3373DDCADFC007D1DA929FF68FEAFA925DA382645C29613A7F217";

    private static final String LEVEL_RENDERER_RESOURCE =
        "net/minecraft/client/renderer/LevelRenderer.class";
    private static final String LEVEL_RENDERER_SHA256 =
        "227D99BBABA15E07F853C2953691C2DE35B9CD75E6C6DB704A762ACA95ACF8E6";
    private static final String BLOCK_ENTITY_STATE_RESOURCE =
        "net/minecraft/client/renderer/blockentity/state/"
            + "BlockEntityRenderState.class";
    private static final String BLOCK_ENTITY_STATE_SHA256 =
        "ADEB4F335978F3A1EABF9F23E44D61FB8329BE06A33FD4A870D6E7AB242294F2";
    private static final String CAMERA_STATE_RESOURCE =
        "net/minecraft/client/renderer/state/level/CameraRenderState.class";
    private static final String CAMERA_STATE_SHA256 =
        "96AB3C0D3E91CC9832F8440AC354EC803FECBC722330A5CAA2DD1AFC32662975";
    private static final String ORDERED_COLLECTOR_RESOURCE =
        "net/minecraft/client/renderer/OrderedSubmitNodeCollector.class";
    private static final String ORDERED_COLLECTOR_SHA256 =
        "9D480CD1B362602C074C72594828A8FC6C7C280B271962817A09A1C4F3BBDB19";
    private static final String RENDER_TYPES_RESOURCE =
        "net/minecraft/client/renderer/rendertype/RenderTypes.class";
    private static final String RENDER_TYPES_SHA256 =
        "EB028886EFE8137F285E7A5FD498BB353F0CE990DE387E7D57DF0F7B21644363";

    private static final String DISTANCE_MIXIN_SUFFIX =
        ".BricksCompositeBlockEntityDistanceMixin";
    private static final String LEVEL_RENDERER_MIXIN_SUFFIX =
        ".BricksFarLodLevelRendererMixin";
    private static final String TRY_EXTRACT_DESCRIPTOR =
        "(Lnet/minecraft/world/level/block/entity/BlockEntity;"
            + "FLnet/minecraft/client/renderer/feature/"
            + "ModelFeatureRenderer$CrumblingOverlay;Z"
            + "Lnet/minecraft/client/renderer/culling/Frustum;)"
            + "Lnet/minecraft/client/renderer/blockentity/state/"
            + "BlockEntityRenderState;";
    private static final String SHOULD_RENDER_DESCRIPTOR =
        "(Lnet/minecraft/world/level/block/entity/BlockEntity;"
            + "Lnet/minecraft/world/phys/Vec3;)Z";
    private static final String EXTRACT_RENDER_STATE_DESCRIPTOR =
        "(Lnet/minecraft/world/level/block/entity/BlockEntity;"
            + "Lnet/minecraft/client/renderer/blockentity/state/"
            + "BlockEntityRenderState;F"
            + "Lnet/minecraft/world/phys/Vec3;"
            + "Lnet/minecraft/client/renderer/feature/"
            + "ModelFeatureRenderer$CrumblingOverlay;)V";
    private static final String SUBMIT_BLOCK_ENTITIES_DESCRIPTOR =
        "(Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/state/level/LevelRenderState;"
            + "Lnet/minecraft/client/renderer/SubmitNodeCollector;)V";
    private static final String DISPATCHER_SUBMIT_DESCRIPTOR =
        "(Lnet/minecraft/client/renderer/blockentity/state/"
            + "BlockEntityRenderState;"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
            + "Lnet/minecraft/client/renderer/state/level/"
            + "CameraRenderState;)V";
    private static final String LEVEL_RENDER_DESCRIPTOR =
        "(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;"
            + "Lnet/minecraft/client/DeltaTracker;Z"
            + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;"
            + "Lorg/joml/Matrix4fc;"
            + "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
            + "Lorg/joml/Vector4f;Z)V";
    private static final String SUBMIT_FEATURES_DESCRIPTOR =
        "(Lnet/minecraft/client/renderer/state/level/LevelRenderState;"
            + "Lnet/minecraft/client/renderer/SubmitNodeCollector;Z)V";
    private static final String FRAME_GRAPH_EXECUTE_DESCRIPTOR =
        "(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;"
            + "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;)V";

    private static final Logger LOGGER = LoggerFactory.getLogger(
        "BlockFrame Bricks Compatibility"
    );
    private static final AtomicBoolean DECISION_LOGGED = new AtomicBoolean();
    private static volatile Decision runtimeDecision;

    private BricksCompatibility() {
    }

    public static boolean isBricksMixin(String mixinClassName) {
        return mixinClassName != null
            && (
                mixinClassName.endsWith(DISTANCE_MIXIN_SUFFIX)
                    || mixinClassName.endsWith(LEVEL_RENDERER_MIXIN_SUFFIX)
            );
    }

    public static boolean isExpectedMixinTarget(
        String mixinClassName,
        String targetClassName
    ) {
        if (mixinClassName == null || targetClassName == null) {
            return false;
        }
        if (mixinClassName.endsWith(DISTANCE_MIXIN_SUFFIX)) {
            return "net.minecraft.client.renderer.blockentity."
                .concat("BlockEntityRenderDispatcher")
                .equals(targetClassName);
        }
        if (mixinClassName.endsWith(LEVEL_RENDERER_MIXIN_SUFFIX)) {
            return "net.minecraft.client.renderer.LevelRenderer"
                .equals(targetClassName);
        }
        return false;
    }

    public static boolean mixinAllowed() {
        Decision decision = runtimeDecision();
        if (DECISION_LOGGED.compareAndSet(false, true)) {
            if (decision.enabled()) {
                LOGGER.info(
                    "Bricks composite LOD enabled: original renderer through "
                        + "{} blocks, cached batched far mesh through Minecraft's "
                        + "effective render distance",
                    MIN_VIEW_DISTANCE_BLOCKS
                );
            } else {
                LOGGER.info(
                    "Bricks composite render-distance fix disabled fail-closed: {}",
                    decision.detail()
                );
            }
        }
        return decision.enabled();
    }

    public static int configuredViewDistanceBlocks() {
        Decision decision = runtimeDecision();
        return decision.enabled()
            ? decision.configuredViewDistanceBlocks()
            : MIN_VIEW_DISTANCE_BLOCKS;
    }

    public static boolean isExactCompositeRenderer(Object renderer) {
        return renderer != null
            && COMPOSITE_RENDERER_CLASS.equals(renderer.getClass().getName());
    }

    private static Decision runtimeDecision() {
        Decision current = runtimeDecision;
        if (current != null) {
            return current;
        }
        synchronized (BricksCompatibility.class) {
            current = runtimeDecision;
            if (current == null) {
                current = readAndEvaluateRuntime();
                runtimeDecision = current;
            }
            return current;
        }
    }

    private static Decision readAndEvaluateRuntime() {
        int configuredDistance = parseConfiguredDistance(
            System.getProperty(VIEW_DISTANCE_PROPERTY)
        );
        if (configuredDistance < 0) {
            return Decision.disabled(
                "invalid " + VIEW_DISTANCE_PROPERTY + "; expected an integer from "
                    + MIN_VIEW_DISTANCE_BLOCKS + " through "
                    + MAX_VIEW_DISTANCE_BLOCKS
            );
        }
        try {
            FMLLoader loader = FMLLoader.getCurrentOrNull();
            if (loader == null || loader.getLoadingModList() == null) {
                return Decision.disabled("NeoForge loading metadata is unavailable");
            }
            ModFileInfo file = loader.getLoadingModList().getModFileById(MOD_ID);
            if (file == null) {
                return Decision.disabled("Bricks is absent");
            }
            IModInfo mod = file.getMods().stream()
                .filter(candidate -> MOD_ID.equals(candidate.getModId()))
                .findFirst()
                .orElse(null);
            if (mod == null) {
                return Decision.disabled("Bricks owner metadata is missing");
            }
            byte[] composite = file.getFile().getContents().readFile(
                COMPOSITE_RENDERER_RESOURCE
            );
            ClassLoader classLoader = BricksCompatibility.class.getClassLoader();
            Evidence evidence = new Evidence(
                mod.getModId(),
                mod.getVersion().toString(),
                file.getFile().getContents().getChecksum().orElse("")
                    .toUpperCase(Locale.ROOT),
                composite,
                readRequired(classLoader, DISPATCHER_RESOURCE),
                readRequired(classLoader, RENDERER_INTERFACE_RESOURCE),
                readRequired(classLoader, MINECRAFT_RESOURCE),
                readRequired(classLoader, OPTIONS_RESOURCE),
                readRequired(classLoader, LEVEL_RENDERER_RESOURCE),
                readRequired(classLoader, BLOCK_ENTITY_STATE_RESOURCE),
                readRequired(classLoader, CAMERA_STATE_RESOURCE),
                readRequired(classLoader, ORDERED_COLLECTOR_RESOURCE),
                readRequired(classLoader, RENDER_TYPES_RESOURCE)
            );
            return evaluate(evidence, configuredDistance);
        } catch (IOException | RuntimeException | LinkageError exception) {
            return Decision.disabled(
                "runtime evidence could not be inspected: "
                    + exception.getClass().getSimpleName()
            );
        }
    }

    static Decision evaluate(Evidence evidence, String configuredValue) {
        int configuredDistance = parseConfiguredDistance(configuredValue);
        if (configuredDistance < 0) {
            return Decision.disabled("invalid configured view-distance property");
        }
        return evaluate(evidence, configuredDistance);
    }

    private static Decision evaluate(Evidence evidence, int configuredDistance) {
        if (evidence == null) {
            return Decision.disabled("evidence is absent");
        }
        if (!MOD_ID.equals(evidence.modId())) {
            return Decision.disabled("owner mod ID changed");
        }
        if (!EXACT_VERSION.equals(evidence.version())) {
            return Decision.disabled("owner version changed");
        }
        if (!EXACT_ARTIFACT_SHA256.equalsIgnoreCase(evidence.artifactSha256())) {
            return Decision.disabled("owner artifact SHA-256 changed");
        }
        if (!matchesClass(
            evidence.compositeRenderer(),
            COMPOSITE_RENDERER_SHA256,
            new Member("<init>", "(Lnet/minecraft/client/renderer/blockentity/"
                + "BlockEntityRendererProvider$Context;)V"),
            new Member(
                "createRenderState",
                "()Lcom/matnx/omni/client/micro/"
                    + "CompositeBlockEntityRenderState;"
            ),
            new Member(
                "submit",
                "(Lcom/matnx/omni/client/micro/"
                    + "CompositeBlockEntityRenderState;"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
                    + "Lnet/minecraft/client/renderer/state/level/"
                    + "CameraRenderState;)V"
            )
        )) {
            return Decision.disabled("CompositeBlockEntityRenderer contract changed");
        }
        if (!matchesClass(
            evidence.dispatcher(),
            DISPATCHER_SHA256,
            new Member("tryExtractRenderState", TRY_EXTRACT_DESCRIPTOR)
        ) || rendererInvokeCount(
            evidence.dispatcher(),
            "shouldRender",
            SHOULD_RENDER_DESCRIPTOR
        ) != 1 || rendererInvokeCount(
            evidence.dispatcher(),
            "extractRenderState",
            EXTRACT_RENDER_STATE_DESCRIPTOR
        ) != 1) {
            return Decision.disabled("BlockEntityRenderDispatcher call site changed");
        }
        if (!matchesClass(
            evidence.rendererInterface(),
            RENDERER_INTERFACE_SHA256,
            new Member("getViewDistance", "()I"),
            new Member("shouldRender", SHOULD_RENDER_DESCRIPTOR)
        )) {
            return Decision.disabled("BlockEntityRenderer contract changed");
        }
        if (!matchesClass(
            evidence.minecraft(),
            MINECRAFT_SHA256,
            new Member("getInstance", "()Lnet/minecraft/client/Minecraft;")
        ) || !hasField(
            evidence.minecraft(),
            "options",
            "Lnet/minecraft/client/Options;"
        )) {
            return Decision.disabled("Minecraft client options contract changed");
        }
        if (!matchesClass(
            evidence.options(),
            OPTIONS_SHA256,
            new Member("getEffectiveRenderDistance", "()I")
        )) {
            return Decision.disabled("effective render-distance contract changed");
        }
        if (!matchesClass(
            evidence.levelRenderer(),
            LEVEL_RENDERER_SHA256,
            new Member(
                "submitBlockEntities",
                SUBMIT_BLOCK_ENTITIES_DESCRIPTOR
            ),
            new Member("render", LEVEL_RENDER_DESCRIPTOR)
        ) || dispatcherSubmitInvokeCount(evidence.levelRenderer()) != 1
            || !renderConsumesSubmittedGeometryBeforeReturn(
                evidence.levelRenderer()
            )) {
            return Decision.disabled("LevelRenderer far-batch call site changed");
        }
        if (!matchesClass(
            evidence.blockEntityState(),
            BLOCK_ENTITY_STATE_SHA256,
            new Member(
                "extractBase",
                "(Lnet/minecraft/world/level/block/entity/BlockEntity;"
                    + "Lnet/minecraft/client/renderer/blockentity/state/"
                    + "BlockEntityRenderState;"
                    + "Lnet/minecraft/client/renderer/feature/"
                    + "ModelFeatureRenderer$CrumblingOverlay;)V"
            )
        )) {
            return Decision.disabled("BlockEntityRenderState contract changed");
        }
        if (!matchesClass(
            evidence.cameraState(),
            CAMERA_STATE_SHA256
        ) || !hasField(
            evidence.cameraState(),
            "pos",
            "Lnet/minecraft/world/phys/Vec3;"
        )) {
            return Decision.disabled("CameraRenderState contract changed");
        }
        if (!matchesClass(
            evidence.orderedCollector(),
            ORDERED_COLLECTOR_SHA256,
            new Member(
                "submitCustomGeometry",
                "(Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/rendertype/RenderType;"
                    + "Lnet/minecraft/client/renderer/"
                    + "SubmitNodeCollector$CustomGeometryRenderer;)V"
            )
        )) {
            return Decision.disabled("custom-geometry collector contract changed");
        }
        if (!matchesClass(
            evidence.renderTypes(),
            RENDER_TYPES_SHA256,
            new Member(
                "entityCutout",
                "(Lnet/minecraft/resources/Identifier;)"
                    + "Lnet/minecraft/client/renderer/rendertype/RenderType;"
            ),
            new Member(
                "entityTranslucent",
                "(Lnet/minecraft/resources/Identifier;)"
                    + "Lnet/minecraft/client/renderer/rendertype/RenderType;"
            )
        )) {
            return Decision.disabled("far-LOD render-type contract changed");
        }
        return Decision.enabled(configuredDistance);
    }

    static int parseConfiguredDistance(String configuredValue) {
        if (configuredValue == null || configuredValue.isBlank()) {
            return DEFAULT_VIEW_DISTANCE_BLOCKS;
        }
        try {
            int parsed = Integer.parseInt(configuredValue.trim());
            return parsed >= MIN_VIEW_DISTANCE_BLOCKS
                    && parsed <= MAX_VIEW_DISTANCE_BLOCKS
                ? parsed
                : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static byte[] readRequired(
        ClassLoader classLoader,
        String resource
    ) throws IOException {
        try (InputStream input = classLoader.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing class resource " + resource);
            }
            return input.readAllBytes();
        }
    }

    private static boolean matchesClass(
        byte[] bytes,
        String expectedSha256,
        Member... members
    ) {
        if (bytes == null || !expectedSha256.equals(sha256(bytes))) {
            return false;
        }
        ClassNode node = classNode(bytes, ClassReader.SKIP_CODE);
        for (Member member : members) {
            boolean present = node.methods.stream().anyMatch(method ->
                member.name().equals(method.name)
                    && member.descriptor().equals(method.desc)
            );
            if (!present) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasField(
        byte[] bytes,
        String name,
        String descriptor
    ) {
        return classNode(bytes, ClassReader.SKIP_CODE).fields.stream().anyMatch(field ->
            name.equals(field.name) && descriptor.equals(field.desc)
        );
    }

    private static int rendererInvokeCount(
        byte[] dispatcherBytes,
        String invokedName,
        String invokedDescriptor
    ) {
        ClassNode node = classNode(dispatcherBytes, 0);
        MethodNode target = node.methods.stream()
            .filter(method -> "tryExtractRenderState".equals(method.name)
                && TRY_EXTRACT_DESCRIPTOR.equals(method.desc))
            .findFirst()
            .orElse(null);
        if (target == null) {
            return 0;
        }
        int count = 0;
        for (
            AbstractInsnNode instruction = target.instructions.getFirst();
            instruction != null;
            instruction = instruction.getNext()
        ) {
            if (
                instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && "net/minecraft/client/renderer/blockentity/"
                        .concat("BlockEntityRenderer").equals(call.owner)
                    && invokedName.equals(call.name)
                    && invokedDescriptor.equals(call.desc)
            ) {
                count++;
            }
        }
        return count;
    }

    private static int dispatcherSubmitInvokeCount(byte[] levelRendererBytes) {
        ClassNode node = classNode(levelRendererBytes, 0);
        MethodNode target = node.methods.stream()
            .filter(method -> "submitBlockEntities".equals(method.name)
                && SUBMIT_BLOCK_ENTITIES_DESCRIPTOR.equals(method.desc))
            .findFirst()
            .orElse(null);
        if (target == null) {
            return 0;
        }
        int count = 0;
        for (
            AbstractInsnNode instruction = target.instructions.getFirst();
            instruction != null;
            instruction = instruction.getNext()
        ) {
            if (
                instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && "net/minecraft/client/renderer/blockentity/"
                        .concat("BlockEntityRenderDispatcher")
                        .equals(call.owner)
                    && "submit".equals(call.name)
                    && DISPATCHER_SUBMIT_DESCRIPTOR.equals(call.desc)
            ) {
                count++;
            }
        }
        return count;
    }

    /**
     * Proves the reusable batch arrays are consumed inside the same pinned
     * LevelRenderer.render invocation, before a later frame can clear them.
     */
    private static boolean renderConsumesSubmittedGeometryBeforeReturn(
        byte[] levelRendererBytes
    ) {
        ClassNode node = classNode(levelRendererBytes, 0);
        MethodNode render = node.methods.stream()
            .filter(method -> "render".equals(method.name)
                && LEVEL_RENDER_DESCRIPTOR.equals(method.desc))
            .findFirst()
            .orElse(null);
        if (render == null) {
            return false;
        }
        int instructionIndex = 0;
        int submitIndex = -1;
        int executeIndex = -1;
        int submitCount = 0;
        int executeCount = 0;
        for (
            AbstractInsnNode instruction = render.instructions.getFirst();
            instruction != null;
            instruction = instruction.getNext(), instructionIndex++
        ) {
            if (!(instruction instanceof MethodInsnNode call)) {
                continue;
            }
            if (
                node.name.equals(call.owner)
                    && "submitFeatures".equals(call.name)
                    && SUBMIT_FEATURES_DESCRIPTOR.equals(call.desc)
            ) {
                submitCount++;
                submitIndex = instructionIndex;
            }
            if (
                "com/mojang/blaze3d/framegraph/FrameGraphBuilder"
                    .equals(call.owner)
                    && "execute".equals(call.name)
                    && FRAME_GRAPH_EXECUTE_DESCRIPTOR.equals(call.desc)
            ) {
                executeCount++;
                executeIndex = instructionIndex;
            }
        }
        return submitCount == 1
            && executeCount == 1
            && submitIndex >= 0
            && executeIndex > submitIndex;
    }

    private static ClassNode classNode(byte[] bytes, int flags) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(
            node,
            flags | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES
        );
        return node;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().withUpperCase().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static record Evidence(
        String modId,
        String version,
        String artifactSha256,
        byte[] compositeRenderer,
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
    }

    static record Decision(
        boolean enabled,
        int configuredViewDistanceBlocks,
        String detail
    ) {
        static Decision enabled(int configuredViewDistanceBlocks) {
            return new Decision(true, configuredViewDistanceBlocks, "exact contract matched");
        }

        static Decision disabled(String detail) {
            return new Decision(false, MIN_VIEW_DISTANCE_BLOCKS, detail);
        }
    }

    private record Member(String name, String descriptor) {
    }
}

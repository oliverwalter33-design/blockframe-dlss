package de.morau.blockframe.benchmark.phase2a0b;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import org.junit.jupiter.api.Test;

class Phase2a0bSourceContractTest {
    private final Path project = Path.of(
        System.getProperty("blockframe.projectDir")
    );

    @Test
    void productionSourceTreeContainsNoHarnessOrCpuSampler() throws Exception {
        Path production = this.project.resolve("src/main/java");
        try (var stream = Files.walk(production)) {
            assertFalse(
                stream.anyMatch(
                    path ->
                        path.toString().contains("phase2a0b")
                            || path.getFileName()
                                .toString()
                                .equals("ThreadCpuWindow.java")
                )
            );
        }
    }

    @Test
    void existingProductionJarContainsNoHarnessClasses() throws Exception {
        String modVersion = Files.readString(
                this.project.resolve("gradle.properties"),
                StandardCharsets.UTF_8
            )
            .lines()
            .filter(line -> line.startsWith("mod_version="))
            .map(line -> line.substring("mod_version=".length()).trim())
            .findFirst()
            .orElseThrow();
        Path productionJar = this.project.resolve(
            "build/libs/blockframe-dlss-" + modVersion + ".jar"
        );
        assertTrue(Files.isRegularFile(productionJar));
        try (ZipFile archive = new ZipFile(productionJar.toFile())) {
            assertFalse(
                archive.stream().anyMatch(
                    entry ->
                        entry.getName().startsWith(
                            "de/morau/blockframe/benchmark/phase2a0b/"
                        )
                )
            );
        }
    }

    @Test
    void measureHotpathsContainNoIoOrThreadDiscovery() throws Exception {
        String runtime = read("Phase2a0bRuntime.java");
        String measurement = read("MeasurementBuffer.java");
        String thread = read("ThreadCpuWindow.java");

        String finishFrame = between(
            runtime,
            "private void finishFrame",
            "private void beginMeasure"
        );
        assertFalse(finishFrame.contains("Files."));
        assertFalse(finishFrame.contains("new "));
        assertFalse(finishFrame.contains("ThreadMXBean"));

        String record = between(
            measurement,
            "public boolean record",
            "public int size"
        );
        assertFalse(record.contains("Files."));
        assertFalse(record.contains("new "));
        assertFalse(record.contains("String"));

        assertFalse(thread.contains("onFrame("));
        assertTrue(
            thread.contains(
                "there is no per-frame sampling API or sampler"
            )
        );
    }

    @Test
    void runtimeFailsClosedOnPinnedHashesAndProtectedWorld()
        throws Exception {
        String runtime = read("Phase2a0bRuntime.java");
        String runner = read("FixtureRunManager.java");
        String inventory = read("FixtureInventory.java");
        assertTrue(runtime.contains("scene hash mismatch"));
        assertTrue(runtime.contains("scene fixture hash mismatch"));
        assertTrue(
            runtime.contains(
                "scene benchmark start profile hash mismatch"
            )
        );
        assertTrue(runtime.contains("config receipt manifest hash mismatch"));
        assertTrue(runtime.contains("ConfigTransactionReceipt.readOnce"));
        assertTrue(runtime.contains("protected world selected"));
        assertTrue(runtime.contains("loaded world folder"));
        assertTrue(runner.contains("Golden fixture mismatch"));
        assertTrue(runner.contains("already exists"));
        assertTrue(inventory.contains("temporary copy preserved"));
    }

    @Test
    void noSchedulerPoolOrAffinityMutationWasIntroduced() throws Exception {
        String all;
        Path source = this.project.resolve("src/phase2a0bHarness/java");
        try (var stream = Files.walk(source)) {
            StringBuilder joined = new StringBuilder();
            for (
                Path path :
                    stream.filter(Files::isRegularFile).toList()
            ) {
                joined.append(Files.readString(path));
            }
            all = joined.toString();
        }
        assertFalse(all.contains("ExecutorService"));
        assertFalse(all.contains("ForkJoinPool"));
        assertFalse(all.contains("setPriority("));
        assertFalse(all.contains("SetThreadAffinity"));
        assertFalse(all.contains("Unsafe"));
        assertFalse(all.contains("FrameBudgetController"));
    }

    @Test
    void initializationIsPassiveEventDrivenAndScoutEscapeIsNonDestructive()
        throws Exception {
        String runtime = read("Phase2a0bRuntime.java");
        String bootstrap = read("Phase2a0bHarnessMod.java");
        String readiness = read("RenderReadinessState.java");
        assertFalse(
            bootstrap.contains(
                "Phase2a0bRuntime.initialize(Minecraft.getInstance())"
            )
        );
        assertTrue(bootstrap.contains("Phase2a0bRuntime.bootstrap("));
        assertTrue(runtime.contains("READINESS.observe("));
        assertTrue(runtime.contains("READINESS.heartbeat("));
        assertTrue(runtime.contains("camera.isInitialized()"));
        assertTrue(runtime.contains("runtime.validateLoadedRunCopy();"));
        assertTrue(
            runtime.indexOf("runtime.validateLoadedRunCopy();")
                < runtime.indexOf("READINESS.markReplayArmed(")
        );
        assertFalse(runtime.contains("INITIALIZATION_TIMEOUT"));
        assertFalse(runtime.contains("ensureInitializedOnRenderThread"));
        assertFalse(readiness.contains("Thread.sleep"));
        assertFalse(readiness.contains(".wait("));
        assertFalse(readiness.contains("LockSupport"));
        assertFalse(readiness.contains("Files."));
        assertTrue(
            runtime.contains(
                "Phase2a0bContracts.MeasurementMode.REPLAY_SUITE"
            )
        );
        assertTrue(runtime.contains("restoreBenchmarkOptions"));
    }

    @Test
    void backendPreflightUsesMojangDeviceWithoutNewProductionClassLink()
        throws Exception {
        String runtime = read("Phase2a0bRuntime.java");
        assertTrue(
            runtime.contains(
                "RenderSystem.getDevice().getDeviceInfo()"
            )
        );
        assertFalse(
            runtime.contains(
                "import de.morau.blockframe.core.BlockframeRuntime"
            )
        );
        assertFalse(
            runtime.contains(
                "import de.morau.blockframe.core.EngineCapabilities"
            )
        );
    }

    @Test
    void runtimeArtifactIsAttestedBeforeReplayOwnerPublication()
        throws Exception {
        String runtime = read("Phase2a0bRuntime.java");
        String preflight = read("Phase2a0bPreflight.java");
        assertTrue(runtime.contains("attestLoadedRuntime("));
        assertTrue(runtime.contains("ModList.get()"));
        assertTrue(
            preflight.contains(
                "blockframe-dlss-0.3.14-neoforge-26.2.jar"
            )
        );
        assertTrue(
            preflight.contains(
                "7e9b6b7130f5d6bce3c0c158897a4eeb5f2aa3f9d08c2d908b56112a70d463a5"
            )
        );
        assertTrue(
            runtime.indexOf("attestLoadedRuntime(")
                < runtime.indexOf("validatePreOwnerContract(")
        );
        assertTrue(
            runtime.indexOf("validatePreOwnerContract(")
                < runtime.indexOf(
                    "runtime.writeProcessManifest("
                        + "READINESS.ownerPublications())"
                )
        );
        assertTrue(
            runtime.indexOf(
                "runtime.writeProcessManifest("
                    + "READINESS.ownerPublications())"
            )
                < runtime.indexOf("instance = runtime;")
        );
        assertTrue(runtime.contains("runtimeAttestation"));
        assertTrue(preflight.contains("case BLOCKFRAME_IDENTITY"));
        assertTrue(preflight.contains("case BLOCKFRAME_VERSION"));
        assertTrue(preflight.contains("case BLOCKFRAME_JAR_HASH"));
    }

    @Test
    void immutableConfigHashAndStagedArtifactInventoryRemainIndependent()
        throws Exception {
        String profile = read("BenchmarkConfigProfile.java");
        String runner = read("FixtureRunManager.java");
        assertTrue(profile.contains("verifyConfiguration(Path instance)"));
        assertTrue(
            profile.contains(
                "caller remains responsible for checking that physical mod inventory"
            )
        );
        assertTrue(runner.contains("auditGoldenAndMods(instance, repository)"));
        assertTrue(runner.contains("profile.verifyConfiguration(instance)"));
    }

    @Test
    void runtimePreflightDoesNotRehashLockedConfigurationFiles()
        throws Exception {
        String runtime = read("Phase2a0bRuntime.java");
        String runner = read("FixtureRunManager.java");
        assertFalse(runtime.contains("auditRuntimeStatic("));
        assertFalse(runtime.contains("FixtureRunManager.audit("));
        String runtimeAudit = between(
            runner,
            "public static RuntimeStaticAudit auditRuntimeStatic",
            "private static StaticAudit auditGoldenAndMods"
        );
        assertFalse(runtimeAudit.contains("rawConfigHash"));
        assertFalse(runtimeAudit.contains("verifyConfiguration"));
        assertTrue(
            runner.contains(
                "complete raw/config profile verification remains"
            )
        );
        assertTrue(runner.contains("launchStatus=READY_TO_LAUNCH"));
        assertTrue(
            runner.contains(
                "externalLaunchDeadlineSeconds"
            )
        );
    }

    @Test
    void resultBoundaryIsStrictValidatedAndAtomic() throws Exception {
        String runtime = read("Phase2a0bRuntime.java");
        String result = read("Phase2a0bResultSchema.java");
        assertTrue(
            runtime.contains("Phase2a0bResultSchema.publishNew")
        );
        assertTrue(result.contains("validateForSerialization(object)"));
        assertTrue(result.contains("StandardCopyOption.ATOMIC_MOVE"));
        assertFalse(result.contains("serializeSpecialFloatingPointValues"));
        assertFalse(result.contains("REPLACE_EXISTING"));
    }

    @Test
    void minecraft26RenderAndLifecycleDescriptorsAreExact()
        throws Exception {
        GameRenderer.class.getDeclaredMethod(
            "render",
            DeltaTracker.class,
            boolean.class
        );
        Minecraft.class.getDeclaredMethod(
            "setLevel",
            ClientLevel.class
        );
        Minecraft.class.getDeclaredMethod(
            "clearClientLevel",
            Screen.class
        );
        String rendererMixin = read("Phase2a0bGameRendererMixin.java");
        String minecraftMixin = read("Phase2a0bMinecraftMixin.java");
        String mixinConfig = Files.readString(
            this.project.resolve(
                "src/phase2a0bHarness/resources/"
                    + "blockframe_phase2a0b.mixins.json"
            )
        );
        assertTrue(
            rendererMixin.contains(
                "render(Lnet/minecraft/client/DeltaTracker;Z)V"
            )
        );
        assertTrue(
            minecraftMixin.contains(
                "setLevel(Lnet/minecraft/client/multiplayer/"
                    + "ClientLevel;)V"
            )
        );
        assertTrue(
            minecraftMixin.contains(
                "clearClientLevel(Lnet/minecraft/client/gui/"
                    + "screens/Screen;)V"
            )
        );
        assertTrue(
            mixinConfig.contains("\"Phase2a0bGameRendererMixin\"")
        );
        assertTrue(
            mixinConfig.contains("\"Phase2a0bMinecraftMixin\"")
        );
    }

    @Test
    void warmReadinessPathHasNoAllocationIoOrDeadline()
        throws Exception {
        String readiness = read("RenderReadinessState.java");
        String heartbeat = between(
            readiness,
            "public boolean heartbeat",
            "public void markReplayArmed"
        );
        assertFalse(heartbeat.contains("new "));
        assertFalse(heartbeat.contains("Files."));
        assertFalse(heartbeat.contains("LOGGER"));
        assertFalse(heartbeat.contains("sleep"));
        assertFalse(heartbeat.contains("wait"));
        assertFalse(heartbeat.contains("poll"));
        assertFalse(heartbeat.contains("timeout"));
        String runtime = read("Phase2a0bRuntime.java");
        String renderHead = between(
            runtime,
            "public static void onRenderHead",
            "public static void onRenderReturn"
        );
        assertFalse(renderHead.contains("Files."));
        assertFalse(renderHead.contains("Thread.sleep"));
        assertFalse(renderHead.contains(".wait("));
    }

    private String read(String name) throws Exception {
        try (
            var stream = Files.walk(
                this.project.resolve("src/phase2a0bHarness/java")
            )
        ) {
            Path path = stream.filter(
                    candidate ->
                        candidate.getFileName().toString().equals(name)
                )
                .findFirst()
                .orElseThrow();
            return Files.readString(path, StandardCharsets.UTF_8);
        }
    }

    private static String between(
        String source,
        String start,
        String end
    ) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from);
        return source.substring(from, to);
    }
}

package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Release gate proof across source, class files and packaged SPIR-V. */
class ReleaseDiagnosticsIsolationContractTest {
    private static final int SPIRV_MAGIC = 0x07230203;
    private static final int OP_DECORATE = 71;
    private static final int OP_BRANCH_CONDITIONAL = 250;
    private static final int DECORATION_BINDING = 33;

    @Test
    void productiveMixinBytecodeContainsNoDiagnosticOwners()
        throws Exception {
        assertBytecodeExcludes(
            "de/morau/nvidiadlss/mixin/GameRendererMixin.class",
            "GpuPassDiagnostics",
            "DlssDebugCapture"
        );
        assertBytecodeExcludes(
            "de/morau/nvidiadlss/mixin/LevelRendererMixin.class",
            "recordCpuCull",
            "System.nanoTime"
        );
        assertBytecodeExcludes(
            "de/morau/nvidiadlss/mixin/"
                + "VulkanCommandEncoderLifecycleMixin.class",
            "GpuPassDiagnostics",
            "recordVulkanSubmit",
            "recordVulkanCompletion"
        );
        assertBytecodeExcludes(
            "de/morau/nvidiadlss/mixin/VulkanDeviceMixin.class",
            "GpuPassDiagnostics",
            "labelBorrowedQueues",
            "deviceFaultSnapshot"
        );

        assertBytecodeContains(
            "de/morau/nvidiadlss/mixin/"
                + "GameRendererDiagnosticsMixin.class",
            "GpuPassDiagnostics",
            "DlssDebugCapture"
        );
        assertBytecodeContains(
            "de/morau/nvidiadlss/mixin/"
                + "VulkanCommandEncoderDiagnosticsMixin.class",
            "GpuPassDiagnostics",
            "recordVulkanSubmit",
            "recordVulkanCompletion"
        );
    }

    @Test
    void releaseSpirvHasNoDebugBindingsAndFewerConditionalBranches()
        throws Exception {
        SpirvInspection release = inspectSpirv(
            nativeArtifact("motion_vectors.comp.spv")
        );
        SpirvInspection diagnostics = inspectSpirv(
            nativeArtifact("motion_vectors.debug.comp.spv")
        );

        assertEquals(Set.of(0, 2, 3, 7, 8), release.bindings());
        assertEquals(
            Set.of(0, 2, 3, 4, 5, 7, 8, 9),
            diagnostics.bindings()
        );
        assertFalse(release.bindings().contains(4));
        assertFalse(release.bindings().contains(5));
        assertFalse(release.bindings().contains(9));
        assertTrue(
            diagnostics.conditionalBranches()
                > release.conditionalBranches(),
            "diagnostic-only shader branches must be compiled out"
        );
        assertTrue(
            Files.size(nativeArtifact("motion_vectors.comp.spv"))
                < Files.size(
                    nativeArtifact("motion_vectors.debug.comp.spv")
                )
        );
    }

    @Test
    void sourceSelectsReleaseShaderAndFiveBindingLayoutByDefault()
        throws Exception {
        String motion = source(
            "src/main/java/de/morau/nvidiadlss/MotionVectorGenerator.java"
        );
        String shader = source("native/shaders/motion_vectors.comp");
        String gradle = source("build.gradle");

        assertTrue(motion.contains(
            "static final int RELEASE_DESCRIPTOR_BINDING_COUNT = 5;"
        ));
        assertTrue(motion.contains(
            "private static final String RELEASE_SHADER ="
        ));
        assertTrue(motion.contains(
            "private final boolean developerDiagnostics;"
        ));
        assertTrue(motion.contains(
            "this.developerDiagnostics = DeveloperDiagnostics.ENABLED;"
        ));
        assertTrue(shader.contains(
            "#if BLOCKFRAME_DEVELOPER_DIAGNOSTICS"
        ));
        assertFalse(gradle.contains("devBiasHint"));
        assertFalse(gradle.contains("devInvalidDepthMotionHint"));
    }

    private static SpirvInspection inspectSpirv(Path path)
        throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        assertTrue(bytes.length >= 20 && (bytes.length & 3) == 0);
        ByteBuffer words = ByteBuffer.wrap(bytes)
            .order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(SPIRV_MAGIC, words.getInt(0));
        Set<Integer> bindings = new HashSet<>();
        int branches = 0;
        int word = 5;
        int wordCount = bytes.length / Integer.BYTES;
        while (word < wordCount) {
            int instruction = words.getInt(word * Integer.BYTES);
            int instructionWords = instruction >>> 16;
            int opcode = instruction & 0xffff;
            assertTrue(instructionWords > 0, "invalid SPIR-V instruction");
            assertTrue(word + instructionWords <= wordCount);
            if (
                opcode == OP_DECORATE
                    && instructionWords >= 4
                    && words.getInt((word + 2) * Integer.BYTES)
                        == DECORATION_BINDING
            ) {
                bindings.add(
                    words.getInt((word + 3) * Integer.BYTES)
                );
            }
            if (opcode == OP_BRANCH_CONDITIONAL) {
                branches++;
            }
            word += instructionWords;
        }
        assertEquals(wordCount, word);
        return new SpirvInspection(Set.copyOf(bindings), branches);
    }

    private static void assertBytecodeExcludes(
        String resource,
        String... forbidden
    ) throws Exception {
        String constants = bytecodeConstants(resource);
        for (String value : forbidden) {
            assertFalse(
                constants.contains(value),
                resource + " unexpectedly references " + value
            );
        }
    }

    private static void assertBytecodeContains(
        String resource,
        String... expected
    ) throws Exception {
        String constants = bytecodeConstants(resource);
        for (String value : expected) {
            assertTrue(
                constants.contains(value),
                resource + " is missing " + value
            );
        }
    }

    private static String bytecodeConstants(String resource)
        throws IOException {
        try (
            InputStream input = ReleaseDiagnosticsIsolationContractTest
                .class
                .getClassLoader()
                .getResourceAsStream(resource)
        ) {
            assertNotNull(input, "missing class resource " + resource);
            return new String(
                input.readAllBytes(),
                StandardCharsets.ISO_8859_1
            );
        }
    }

    private static Path nativeArtifact(String name) {
        return projectRoot().resolve(
            "src/main/resources/assets/nvidia_dlss/native/win-x64/"
                + name
        );
    }

    private static String source(String relativePath) throws IOException {
        return Files.readString(
            projectRoot().resolve(relativePath),
            StandardCharsets.UTF_8
        );
    }

    private static Path projectRoot() {
        return Path.of(System.getProperty("blockframe.projectDir"));
    }

    private record SpirvInspection(
        Set<Integer> bindings,
        int conditionalBranches
    ) {}
}

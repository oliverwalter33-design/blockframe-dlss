package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DlssRendererJitSafetySourceContractTest {
    @Test
    void worldJitterPreservesCallerIdentityAndUsesSlabOnTheNormalPath()
        throws Exception {
        String source = source("src/main/java/de/morau/nvidiadlss/DlssRenderer.java");
        String method = source.substring(
            source.indexOf("public static Matrix4f applyWorldJitter"),
            source.indexOf("private static void ensureResources")
        );
        String slabPath = method.substring(
            method.indexOf("if (scratch != null)"),
            method.indexOf("} catch (Throwable error)")
        );

        assertTrue(method.contains("if (!active) return projection;"));
        assertTrue(
            slabPath.contains(
                "scratch.captureUnjitteredProjection(projection);"
            )
        );
        assertFalse(slabPath.contains("new Matrix4f"));
        assertTrue(
            method.contains(
                "unjitteredProjection = new Matrix4f(projection);"
            )
        );
        assertTrue(method.contains("projection.m20(projection.m20()"));
        assertTrue(method.contains("projection.m21(projection.m21()"));
        assertTrue(method.contains("return projection;"));
        assertFalse(method.contains("Matrix4f jittered"));
        assertEquals(2, occurrences(method, "new Matrix4f(projection)"));
    }

    @Test
    void transformPreparationAllocatesOnlyInTheExplicitFallback()
        throws Exception {
        String source = source(
            "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
        );
        String finish = source.substring(
            source.indexOf(
                "public static RenderTarget finishWorldFrame"
            ),
            source.indexOf(
                "private static void prepareNativeOutlineDepthSafely()"
            )
        );
        String slabPath = finish.substring(
            finish.indexOf("if (frameScratch != null)"),
            finish.indexOf(
                "} catch (Throwable error)",
                finish.indexOf("if (frameScratch != null)")
            )
        );
        String fallbackPath = finish.substring(
            finish.indexOf("if (!transformScratchFrame)"),
            finish.indexOf(
                "lastTransformScratchPath = transformScratchFrame;"
            )
        );
        String slabFailure = finish.substring(
            finish.indexOf(
                "} catch (Throwable error)",
                finish.indexOf("if (frameScratch != null)")
            ),
            finish.indexOf("if (!transformScratchFrame)")
        );

        assertTrue(
            slabPath.contains(
                "frameScratch.prepareCurrentTransforms("
            )
        );
        assertFalse(slabPath.contains("new Matrix4f"));
        assertFalse(slabPath.contains("new Vector3f"));
        assertTrue(
            slabFailure.contains(
                "new Matrix4f(slabFallbackProjection)"
            )
        );
        assertTrue(
            slabFailure.contains(
                "disableTransformScratch(error);"
            )
        );
        assertTrue(slabFailure.contains("frameScratch = null;"));
        assertFalse(slabFailure.contains("throw error;"));
        assertTrue(fallbackPath.contains("new Matrix4f"));
        assertTrue(fallbackPath.contains("new Vector3f"));
    }

    @Test
    void previousViewProjectionIsPublishedOnlyAfterSuccessfulEvaluation()
        throws Exception {
        String renderer = source(
            "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
        );
        String scratch = source(
            "src/main/java/de/morau/nvidiadlss/"
                + "DlssTransformScratch.java"
        );
        String finish = renderer.substring(
            renderer.indexOf(
                "public static RenderTarget finishWorldFrame"
            ),
            renderer.indexOf(
                "private static void prepareNativeOutlineDepthSafely()"
            )
        );

        int evaluationGuard = finish.indexOf(
            "if (!lastEvaluationActive)"
        );
        int slabCommit = finish.indexOf(
            "frameScratch.commitPreviousViewProjection();"
        );
        int fallbackCommit = finish.indexOf(
            "commitFallbackTransforms("
        );
        assertTrue(evaluationGuard >= 0);
        assertTrue(slabCommit > evaluationGuard);
        assertTrue(fallbackCommit > slabCommit);
        String fallbackHistory = renderer.substring(
            renderer.indexOf(
                "private static void commitFallbackTransforms("
            ),
            renderer.indexOf(
                "private static void clearFallbackTransformHistory()"
            )
        );
        assertTrue(fallbackHistory.contains(
            "previousViewProjection.set(viewProjection);"
        ));
        assertTrue(fallbackHistory.contains(
            "previousFallbackProjection.set(projection);"
        ));
        assertTrue(fallbackHistory.contains(
            "previousFallbackViewRotation.set(viewRotation);"
        ));
        assertTrue(fallbackHistory.contains(
            "previousFallbackCameraX = cameraX;"
        ));
        assertTrue(fallbackHistory.contains(
            "previousFallbackCameraY = cameraY;"
        ));
        assertTrue(fallbackHistory.contains(
            "previousFallbackCameraZ = cameraZ;"
        ));
        assertTrue(
            fallbackHistory.indexOf(
                "previousFallbackTransformValid = true;"
            ) > fallbackHistory.indexOf(
                "previousFallbackCameraZ = cameraZ;"
            )
        );
        assertTrue(
            scratch.contains(
                "private boolean previousViewProjectionValid;"
            )
        );
        String prepare = scratch.substring(
            scratch.indexOf(
                "boolean prepareCurrentTransforms("
            ),
            scratch.indexOf(
                "Matrix4f projection()"
            )
        );
        assertFalse(
            prepare.contains(
                "this.previousViewProjection.set("
            )
        );
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static String source(String relativePath) throws Exception {
        Path root = Path.of(System.getProperty("blockframe.projectDir"));
        return Files.readString(root.resolve(relativePath), StandardCharsets.UTF_8);
    }
}

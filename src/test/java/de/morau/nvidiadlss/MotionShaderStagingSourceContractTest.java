package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MotionShaderStagingSourceContractTest {
    @Test
    void normalPathBorrowsOneFixedBlockAndReleasesUntransferredBorrows()
        throws Exception {
        String source = motionSource();
        String pooledLoad = section(
            source,
            "private static ShaderCodeOwner tryLoadShaderFromPool(",
            "static boolean readShaderIntoFixedBuffer("
        );

        assertTrue(
            source.contains(
                "private static final int SHADER_STAGING_BYTES = "
                    + "32 * 1024;"
            )
        );
        assertTrue(
            source.contains(
                "BlockframeRuntime.nativeStagingPoolOrNull()"
            )
        );
        assertTrue(
            count(pooledLoad, "pool.tryBorrow(") == 1
        );
        assertTrue(pooledLoad.contains("pool.buffer("));
        assertTrue(
            pooledLoad.contains(
                "readShaderIntoFixedBuffer(input, staging)"
            )
        );
        assertTrue(pooledLoad.contains("} finally {"));
        assertTrue(pooledLoad.contains("if (!borrowTransferred)"));
        assertTrue(pooledLoad.contains("pool.release(token);"));
        assertFalse(pooledLoad.contains("readAllBytes()"));
        assertFalse(pooledLoad.contains("byte[]"));
    }

    @Test
    void oversizeAndUnavailablePoolReopenTheExistingDirectFallback()
        throws Exception {
        String source = motionSource();
        String load = section(
            source,
            "private static ShaderCodeOwner loadShader()",
            "private static ShaderCodeOwner tryLoadShaderFromPool("
        );
        String pooledLoad = section(
            source,
            "private static ShaderCodeOwner tryLoadShaderFromPool(",
            "static boolean readShaderIntoFixedBuffer("
        );
        String directLoad = section(
            source,
            "private static ShaderCodeOwner loadShaderDirect(",
            "private abstract static class ShaderCodeOwner"
        );

        assertTrue(load.contains("if (pool != null)"));
        assertTrue(load.contains("if (pooled != null)"));
        assertTrue(load.contains("return loadShaderDirect(shaderResource);"));
        assertTrue(
            pooledLoad.contains(
                "if (!readShaderIntoFixedBuffer(input, staging))"
            )
        );
        assertTrue(
            pooledLoad.indexOf("return null;")
                < pooledLoad.indexOf("} finally {")
        );
        assertTrue(
            directLoad.contains(
                "getResourceAsStream(shaderResource)"
            )
        );
        assertTrue(directLoad.contains("input.readAllBytes()"));
        assertTrue(directLoad.contains("MemoryUtil.memAlloc("));
    }

    @Test
    void pooledOwnerNeverFreesItsBorrowedBuffer()
        throws Exception {
        String source = motionSource();
        String pooledOwner = section(
            source,
            "private static final class PooledShaderCodeOwner",
            "private static final class DirectShaderCodeOwner"
        );
        String directOwner = section(
            source,
            "private static final class DirectShaderCodeOwner",
            "private static void check("
        );
        String ownerBase = section(
            source,
            "private abstract static class ShaderCodeOwner",
            "private static final class PooledShaderCodeOwner"
        );

        assertTrue(pooledOwner.contains("this.pool.release(this.token);"));
        assertTrue(
            pooledOwner.contains("this.discardBorrowedView();")
        );
        assertFalse(pooledOwner.contains("MemoryUtil.memFree("));
        assertFalse(pooledOwner.contains("freeDirectAllocation()"));
        assertTrue(
            directOwner.contains("this.freeDirectAllocation();")
        );
        assertTrue(ownerBase.contains("MemoryUtil.memFree(this.code);"));
        assertFalse(ownerBase.contains("return this.code;"));
        assertFalse(source.contains("MemoryUtil.memFree(staging)"));
        assertFalse(source.contains("MemoryUtil.memFree(shaderCode)"));
    }

    @Test
    void shaderModuleCreationCompletesInsideTheScopedOwner()
        throws Exception {
        String source = motionSource();
        String constructor = section(
            source,
            "public MotionVectorGenerator(VulkanDevice backend)",
            "public void dispatch("
        );
        String ownerBase = section(
            source,
            "private abstract static class ShaderCodeOwner",
            "private static final class PooledShaderCodeOwner"
        );
        String pooledLoad = section(
            source,
            "private static ShaderCodeOwner tryLoadShaderFromPool(",
            "static boolean readShaderIntoFixedBuffer("
        );

        assertTrue(
            constructor.contains(
                "try (ShaderCodeOwner shaderCode = loadShader())"
            )
        );
        assertTrue(
            constructor.contains(
                "VulkanCreation shaderCreation ="
            )
        );
        assertTrue(
            constructor.contains(
                "this.shaderModule = shaderCreation.handle();"
            )
        );
        assertTrue(ownerBase.contains("shaderInfo.pCode(this.code);"));
        assertTrue(ownerBase.contains("VK12.vkCreateShaderModule("));
        assertTrue(
            ownerBase.contains("return new VulkanCreation(")
        );
        assertTrue(pooledLoad.contains("catch (Exception error)"));
        assertTrue(
            pooledLoad.indexOf("catch (Exception error)")
                < pooledLoad.indexOf("} finally {")
        );
        assertTrue(
            pooledLoad.indexOf("pool.release(token);")
                > pooledLoad.indexOf("} finally {")
        );
    }

    private static String motionSource() throws Exception {
        Path root = Path.of(System.getProperty("blockframe.projectDir"));
        return Files.readString(
            root.resolve(
                "src/main/java/de/morau/nvidiadlss/"
                    + "MotionVectorGenerator.java"
            ),
            StandardCharsets.UTF_8
        );
    }

    private static String section(
        String source,
        String startMarker,
        String endMarker
    ) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertTrue(start >= 0, "missing source marker: " + startMarker);
        assertTrue(end > start, "missing source marker: " + endMarker);
        return source.substring(start, end);
    }

    private static int count(String source, String needle) {
        int matches = 0;
        int offset = 0;
        while ((offset = source.indexOf(needle, offset)) >= 0) {
            matches++;
            offset += needle.length();
        }
        return matches;
    }
}

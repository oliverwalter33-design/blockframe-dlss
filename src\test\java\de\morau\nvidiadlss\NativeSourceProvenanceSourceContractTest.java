package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class NativeSourceProvenanceSourceContractTest {
    @Test
    void nativeBuildWritesSourceAndBinaryHashes() throws Exception {
        String source = source("native/build-native.ps1");
        assertTrue(source.contains("native-source-v1.properties"));
        assertTrue(source.contains("bridgeSourceSha256="));
        assertTrue(source.contains("motionShaderSourceSha256="));
        assertTrue(source.contains("bridgeBinarySha256="));
        assertTrue(source.contains("motionShaderBinarySha256="));
        assertTrue(source.contains("bridgeCompiler="));
        assertTrue(source.contains("motionShaderCompiler="));
        assertTrue(source.contains(
            "SkipShaderBuild requires -PrecompiledShaderCompiler provenance."
        ));
    }

    @Test
    void releaseBuildRejectsStaleNativeArtifacts() throws Exception {
        String source = source("build.gradle");
        assertTrue(source.contains("inputs.files(nativeSourceStamp, nativeBridgeSource, nativeMotionShaderSource)"));
        assertTrue(source.contains("Native source/binary provenance is stale"));
        assertTrue(source.contains(
            "(mismatches.keySet() + missingMetadata).join(', ')"
        ));
        assertTrue(source.contains("missingMetadata"));
        assertTrue(source.contains("Run native/build-native.ps1 before packaging"));
    }

    private static String source(String relativePath) throws Exception {
        Path root = Path.of(System.getProperty("blockframe.projectDir"));
        return Files.readString(root.resolve(relativePath), StandardCharsets.UTF_8);
    }
}

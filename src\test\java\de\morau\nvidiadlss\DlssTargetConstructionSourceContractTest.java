package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DlssTargetConstructionSourceContractTest {
    @Test
    void unreachablePartialMainTargetRetainsItsConservativeBudgetLease()
        throws IOException {
        Path root = Path.of(System.getProperty("blockframe.projectDir"));
        String source = Files.readString(
            root.resolve(
                "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
            ),
            StandardCharsets.UTF_8
        );

        int marker = source.indexOf("boolean targetConstructionStarted");
        int construction = source.indexOf(
            "lowTarget = new MainTarget(desiredWidth, desiredHeight)"
        );
        int conservativeCleanup = source.indexOf(
            "replacement.closeRetainingLease()"
        );
        assertTrue(marker >= 0);
        assertTrue(marker < construction);
        assertTrue(construction < conservativeCleanup);
        assertTrue(
            source.substring(marker, construction)
                .contains("targetConstructionStarted = true")
        );
        assertTrue(
            source.substring(construction, conservativeCleanup)
                .contains("!createdTarget")
        );
        assertTrue(
            source.substring(construction, conservativeCleanup)
                .contains("lowTarget == null")
        );
        assertTrue(
            source.substring(construction, conservativeCleanup)
                .contains("retainReplacementLease = true")
        );
    }
}

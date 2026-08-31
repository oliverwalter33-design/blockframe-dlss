package de.morau.blockframe.benchmark.phase2a0b;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CpuTopologyProbeTest {
    @Test
    void reliableValuesAreReportedWithoutNameBasedInference() {
        CpuTopology topology = CpuTopologyProbe.detect(
            () ->
                Map.of(
                    "model",
                    "Test CPU",
                    "physical",
                    "8",
                    "logical",
                    "16",
                    "numa",
                    "1",
                    "affinity",
                    "65535"
                )
        );
        assertEquals("Test CPU", topology.model());
        assertEquals(8, topology.physicalCores());
        assertEquals(16, topology.logicalProcessors());
        assertEquals(2.0D, topology.smtRatio(), 0.0D);
        assertEquals(16, topology.affinityLogicalProcessors());
        assertEquals(CpuTopology.NOT_AVAILABLE, topology.hybridCoreClasses());
        assertEquals(
            CpuTopology.NOT_AVAILABLE,
            topology.windowsProcessorGroups()
        );
    }

    @Test
    void partialAndFailedQueriesRemainExplicitlyUnavailable() {
        CpuTopology partial = CpuTopologyProbe.detect(
            () -> Map.of("model", "Unknown topology")
        );
        assertFalse(partial.physicalCoresAvailable());
        assertFalse(partial.logicalProcessorsAvailable());
        assertEquals(-1, partial.affinityLogicalProcessors());

        CpuTopology failed = CpuTopologyProbe.detect(
            () -> {
                throw new IllegalStateException("probe failed");
            }
        );
        assertTrue(failed.status().startsWith("PARTIAL:"));
        assertEquals(CpuTopology.NOT_AVAILABLE, failed.model());
    }
}

package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DlssResourceFootprintTest {
    @Test
    void onePixelImagesExposeExactRequestedAndAlignedCommittedBytes() {
        DlssResourceFootprint footprint =
            DlssResourceFootprint.forDimensions(1, 1, 1, 1, false);

        assertEquals(24L, footprint.requestedBytes());
        assertEquals(393_216L, footprint.committedBytes());
    }

    @Test
    void qualityModeFourKNormalFootprintUsesOnlyDescriptorDummiesForCapture() {
        DlssResourceFootprint footprint =
            DlssResourceFootprint.forDimensions(
                2560,
                1440,
                3840,
                2160,
                false
            );

        assertEquals(125_337_600L, footprint.requestedBytes());
        assertEquals(125_435_904L, footprint.committedBytes());
    }

    @Test
    void qualityModeFourKDeveloperFootprintIncludesCaptureImages() {
        DlssResourceFootprint footprint =
            DlssResourceFootprint.forDimensions(
                2560,
                1440,
                3840,
                2160,
                true
            );

        assertEquals(158_515_200L, footprint.requestedBytes());
        assertEquals(158_662_656L, footprint.committedBytes());
    }

    @Test
    void rejectsNonPositiveDimensionsAndArithmeticOverflow() {
        assertThrows(
            IllegalArgumentException.class,
            () -> DlssResourceFootprint.forDimensions(0, 1, 1, 1, false)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> DlssResourceFootprint.forDimensions(1, -1, 1, 1, false)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> DlssResourceFootprint.forDimensions(1, 1, 0, 1, false)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> DlssResourceFootprint.forDimensions(1, 1, 1, 0, false)
        );
        assertThrows(
            ArithmeticException.class,
            () -> DlssResourceFootprint.forDimensions(
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                1,
                1,
                false
            )
        );
        assertThrows(
            ArithmeticException.class,
            () -> DlssResourceFootprint.forDimensions(
                1,
                1,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                false
            )
        );
        assertThrows(
            ArithmeticException.class,
            () -> DlssResourceFootprint.forDimensions(
                1_000_000_000,
                1_000_000_000,
                1,
                1,
                false
            )
        );
        assertThrows(
            ArithmeticException.class,
            () -> DlssResourceFootprint.forDimensions(
                1,
                1,
                1_200_000_000,
                1_000_000_000,
                false
            )
        );
    }
}

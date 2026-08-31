package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DlssTextureSamplingPolicyTest {
    private static final float EPSILON = 0.0001F;

    @Test
    void fourKQualityUsesOfficialDlssMipBias() {
        float bias = DlssRenderer.lodBiasForDimensions(
            DlssMode.QUALITY,
            2560,
            1440,
            3840,
            2160
        );

        assertEquals(-1.5849625F, bias, EPSILON);
    }

    @Test
    void maximizedWindowUsesActualSdkExtentsInsteadOfHardcoded4K() {
        float maximized = DlssRenderer.lodBiasForDimensions(
            DlssMode.QUALITY,
            2560,
            1369,
            3840,
            2054
        );
        float fullscreen = DlssRenderer.lodBiasForDimensions(
            DlssMode.QUALITY,
            2560,
            1440,
            3840,
            2160
        );

        assertEquals(fullscreen, maximized, 0.0F);
    }

    @Test
    void arbitraryWindowUsesTheOfficialHorizontalRatio() {
        assertEquals(
            -1.5851722F,
            DlssRenderer.lodBiasForDimensions(
                DlssMode.QUALITY,
                2293,
                960,
                3440,
                1440
            ),
            EPSILON
        );
    }

    @Test
    void heightRoundingDoesNotChangeTheOfficialWidthBasedDelta() {
        float a = DlssRenderer.lodBiasForDimensions(
            DlssMode.QUALITY,
            2560,
            1369,
            3840,
            2054
        );
        float b = DlssRenderer.lodBiasForDimensions(
            DlssMode.QUALITY,
            2560,
            1370,
            3840,
            2055
        );

        assertEquals(a, b, 0.0F);
    }

    @Test
    void mandatoryWindowWidthsUseTheirActualRoundedRenderWidths() {
        assertEquals(
            -1.5846808F,
            DlssRenderer.lodBiasForDimensions(
                DlssMode.QUALITY,
                1707,
                960,
                2560,
                1440
            ),
            EPSILON
        );
        assertEquals(
            -1.5849625F,
            DlssRenderer.lodBiasForDimensions(
                DlssMode.QUALITY,
                1280,
                720,
                1920,
                1080
            ),
            EPSILON
        );
        assertEquals(
            -1.5853385F,
            DlssRenderer.lodBiasForDimensions(
                DlssMode.QUALITY,
                1279,
                671,
                1919,
                1007
            ),
            EPSILON
        );
    }

    @Test
    void dlaaUsesTheDocumentedOneToOneDlssDelta() {
        assertEquals(
            -1.0F,
            DlssRenderer.lodBiasForDimensions(
                DlssMode.DLAA,
                3840,
                2160,
                3840,
                2160
            ),
            EPSILON
        );
    }

    @Test
    void offAndInvalidDimensionsLeaveTheNativeSamplerUntouched() {
        assertEquals(
            0.0F,
            DlssRenderer.lodBiasForDimensions(
                DlssMode.OFF,
                3840,
                2160,
                3840,
                2160
            ),
            EPSILON
        );
        assertEquals(
            0.0F,
            DlssRenderer.lodBiasForDimensions(
                DlssMode.QUALITY,
                0,
                1440,
                3840,
                2160
            ),
            EPSILON
        );
    }

    @Test
    void deviceClampIsNotHardCodedIntoTheOfficialDelta() {
        assertEquals(
            -4.0F,
            DlssRenderer.lodBiasForDimensions(
                DlssMode.ULTRA_PERFORMANCE,
                480,
                270,
                3840,
                2160
            ),
            EPSILON
        );
    }

    @Test
    void materialAnisotropyPreservesTheOriginalSamplerChoice() {
        assertEquals(
            1,
            DlssSamplerPolicy.materialAnisotropy(1, 16)
        );
        assertEquals(
            4,
            DlssSamplerPolicy.materialAnisotropy(4, 16)
        );
        assertEquals(
            8,
            DlssSamplerPolicy.materialAnisotropy(16, 8)
        );
    }

    @Test
    void samplerBiasStartsFromTheCapturedNativeBias() {
        assertEquals(
            -1.3349625F,
            DlssSamplerPolicy.finalSamplerBias(
                0.25F,
                -1.5849625F,
                15.0F
            ),
            EPSILON
        );
    }

    @Test
    void samplerBiasClampsAgainstTheCapturedDeviceLimit() {
        assertEquals(
            -2.0F,
            DlssSamplerPolicy.finalSamplerBias(
                -0.75F,
                -4.0F,
                2.0F
            ),
            0.0F
        );
        assertEquals(
            2.0F,
            DlssSamplerPolicy.finalSamplerBias(
                1.75F,
                1.0F,
                2.0F
            ),
            0.0F
        );
    }

    @Test
    void invalidSamplerBiasInputsFailClosed() {
        assertTrue(
            Float.isNaN(
                DlssSamplerPolicy.finalSamplerBias(
                    Float.NaN,
                    -1.0F,
                    15.0F
                )
            )
        );
        assertTrue(
            Float.isNaN(
                DlssSamplerPolicy.finalSamplerBias(
                    0.0F,
                    -1.0F,
                    Float.NaN
                )
            )
        );
        assertTrue(
            Float.isNaN(
                DlssSamplerPolicy.finalSamplerBias(
                    0.0F,
                    -1.0F,
                    -1.0F
                )
            )
        );
    }
}

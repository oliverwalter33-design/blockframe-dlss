package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DlssMenuCompatibilitySourceContractTest {
    @Test
    void sodiumGetsDirectOptionsAndReesesRetainsFallback() throws Exception {
        String controller = source(
            "src/main/java/de/morau/nvidiadlss/"
                + "DlssOptionsScreenController.java"
        );
        String screen = source(
            "src/main/java/de/morau/nvidiadlss/"
                + "BlockFrameDlssSettingsScreen.java"
        );
        String sodiumMixin = source(
            "src/main/java/de/morau/nvidiadlss/mixin/sodium/"
                + "SodiumConfigBuilderMixin.java"
        );
        String mixinConfig = source(
            "src/main/resources/nvidia_dlss.mixins.json"
        );

        assertTrue(controller.contains("SodiumVideoOptionsScreen"));
        assertTrue(controller.contains("ScreenEvent.Init.Post"));
        assertTrue(controller.contains("event.addListener(settings)"));
        assertTrue(controller.contains("BlockFrameDlssSettingsScreen"));
        assertTrue(controller.contains("directSodiumIntegrationActive()"));
        assertTrue(screen.contains("extends OptionsSubScreen"));
        assertTrue(screen.contains("this.list.addBig(DlssOption.get())"));
        assertTrue(screen.contains("DlssOption.sharpening()"));
        assertTrue(screen.contains("DlssOption.sharpeningAmount()"));

        assertTrue(sodiumMixin.contains("SodiumConfigBuilder"));
        assertTrue(sodiumMixin.contains("sodium:general.vsync"));
        assertTrue(sodiumMixin.contains("createEnumOption(DLSS"));
        assertTrue(sodiumMixin.contains("createEnumOption(SHARPENING"));
        assertTrue(sodiumMixin.contains(
            "createIntegerOption(SHARPENING_AMOUNT)"
        ));
        assertTrue(sodiumMixin.contains("markDirectSodiumIntegration"));
        assertTrue(mixinConfig.contains("sodium.OptionBuilderAccessor"));
        assertTrue(mixinConfig.contains("sodium.SodiumConfigBuilderMixin"));
    }

    private static String source(String relativePath) throws Exception {
        Path root = Path.of(System.getProperty("blockframe.projectDir"));
        return Files.readString(root.resolve(relativePath), StandardCharsets.UTF_8);
    }
}

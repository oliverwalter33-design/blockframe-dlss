package de.morau.blockframe.faststart;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

/**
 * Optional, cached bridge to Sodium 0.9.1's public terrain-readiness API.
 *
 * <p>The exact target methods were verified from the installed 26.2 JAR.
 * Resolution happens once. Profiles without Sodium fail closed and continue
 * to use the vanilla path.</p>
 */
final class FastStartSodiumLandscapeBridge {
    private static final String RENDERER_CLASS =
        "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer";
    private static final Adapter ADAPTER = createAdapter();

    record Snapshot(
        boolean available,
        boolean terrainRenderComplete,
        int visibleChunks,
        boolean playerSectionReady,
        long signature,
        String reason
    ) {
        static Snapshot notObserved() {
            return unavailable("Vanilla-Sichtliste ist verfügbar");
        }

        static Snapshot unavailable(String reason) {
            return new Snapshot(
                false,
                false,
                0,
                false,
                0L,
                reason
            );
        }
    }

    private interface Adapter {
        Snapshot observe(BlockPos playerPosition);
    }

    private FastStartSodiumLandscapeBridge() {}

    static Snapshot observe(BlockPos playerPosition) {
        return ADAPTER.observe(playerPosition);
    }

    private static Adapter createAdapter() {
        try {
            Class<?> renderer = Class.forName(
                RENDERER_CLASS,
                false,
                FastStartSodiumLandscapeBridge.class.getClassLoader()
            );
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            MethodHandle instance = lookup
                .unreflect(renderer.getMethod("instanceNullable"))
                .asType(MethodType.methodType(Object.class));
            MethodHandle terrainComplete = lookup
                .unreflect(renderer.getMethod("isTerrainRenderComplete"))
                .asType(
                    MethodType.methodType(boolean.class, Object.class)
                );
            MethodHandle visibleChunks = lookup
                .unreflect(renderer.getMethod("getVisibleChunkCount"))
                .asType(MethodType.methodType(int.class, Object.class));
            MethodHandle sectionReady = lookup
                .unreflect(
                    renderer.getMethod(
                        "isSectionReady",
                        int.class,
                        int.class,
                        int.class
                    )
                )
                .asType(
                    MethodType.methodType(
                        boolean.class,
                        Object.class,
                        int.class,
                        int.class,
                        int.class
                    )
                );
            return playerPosition -> observe(
                instance,
                terrainComplete,
                visibleChunks,
                sectionReady,
                playerPosition
            );
        } catch (ClassNotFoundException absent) {
            return ignored -> Snapshot.unavailable(
                "keine Vanilla-Sichtliste und Sodium nicht geladen"
            );
        } catch (ReflectiveOperationException incompatible) {
            String reason =
                "Sodium-Public-API inkompatibel: "
                    + incompatible.getClass().getSimpleName();
            return ignored -> Snapshot.unavailable(reason);
        }
    }

    private static Snapshot observe(
        MethodHandle instanceHandle,
        MethodHandle terrainCompleteHandle,
        MethodHandle visibleChunksHandle,
        MethodHandle sectionReadyHandle,
        BlockPos playerPosition
    ) {
        try {
            Object renderer = (Object) instanceHandle.invokeExact();
            if (renderer == null) {
                return Snapshot.unavailable(
                    "Sodium-Renderer noch nicht initialisiert"
                );
            }
            boolean terrainComplete = (boolean)
                terrainCompleteHandle.invokeExact(renderer);
            int visibleChunks = (int)
                visibleChunksHandle.invokeExact(renderer);
            int sectionX = SectionPos.blockToSectionCoord(
                playerPosition.getX()
            );
            int sectionY = SectionPos.blockToSectionCoord(
                playerPosition.getY()
            );
            int sectionZ = SectionPos.blockToSectionCoord(
                playerPosition.getZ()
            );
            boolean playerReady = (boolean)
                sectionReadyHandle.invokeExact(
                    renderer,
                    sectionX,
                    sectionY,
                    sectionZ
                );
            long signature = 0xcbf29ce484222325L;
            signature ^= visibleChunks;
            signature *= 0x100000001b3L;
            signature ^= sectionX;
            signature *= 0x100000001b3L;
            signature ^= sectionY;
            signature *= 0x100000001b3L;
            signature ^= sectionZ;
            return new Snapshot(
                true,
                terrainComplete,
                visibleChunks,
                playerReady,
                signature,
                terrainComplete
                    ? "Sodium-Terrain bereit"
                    : "Sodium-Buildqueue noch nicht leer"
            );
        } catch (Throwable failure) {
            return Snapshot.unavailable(
                "Sodium-Abfrage fehlgeschlagen: "
                    + failure.getClass().getSimpleName()
            );
        }
    }
}

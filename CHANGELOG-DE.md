# BlockFrame DLSS 0.3.18 – Changelog (Deutsch)

## Entfernt

- Die erzwungene 16-fache anisotrope Filterung wurde entfernt. BlockFrame übernimmt jetzt die ursprüngliche beziehungsweise vom Nutzer gewählte Anisotropie innerhalb des Gerätelimits.
- Der frühere Ausschluss von Cutout-Terrain aus der DLSS-Mip-Korrektur wurde entfernt.
- Das globale Streamline-Tagging pro Frame und der flüchtige `eOnlyValidNow`-Klonpfad wurden entfernt.
- Die R8_UNORM-History-Maske wurde durch eine vollständig von Streamline 2.12 unterstützte RGBA8_UNORM-Maske ersetzt.
- Die doppelte Registrierung nativer BlockFrame-Terrainpipelines bei aktivem Sodium wurde entfernt.
- Diagnose-Bildbindungen und Diagnosezweige wurden aus dem normalen Release-Motion-Shader entfernt.
- Die rohe Vollgeometrie-Distanzverlängerung für Bricks wurde entfernt und durch einen effizienten Fernmesh-Pfad ersetzt.
- Veraltete Sampler- und Cache-Generationen werden bei Größen-, Modus- und Ressourcenwechseln vollständig verworfen.

## Behoben

- Der Terrain-Mip-Bias verwendet die tatsächlichen Render- und Ausgabemaße: ursprünglicher Sampler-Bias plus `log2(Renderbreite / Ausgabebreite) - 1`.
- Beliebige Fenstergrößen, Live-Resizing, Minimieren/Wiederherstellen sowie DLSS-/DLAA-Moduswechsel verwenden jetzt immer den korrekten Viewport, die richtige Rendergröße und den passenden Mip-Bias.
- Vulkan-Sampler werden vollständig geklont. Filter, Adressmodi, LOD-Grenzen, Vergleichsmodus, Border-Color und Anisotropie bleiben erhalten; nur der berechnete `mipLodBias` wird angepasst.
- Entfernte Solid- und alpha-getestete Cutout-Terraindetails erscheinen unter DLSS/DLAA korrekt für die unterstützten Minecraft-, Sodium-, Milkshade- und BlockFrame-Pipelines.
- Die History-Behandlung bei Verdeckungswechseln des lokalen Spielers wurde korrigiert. Eine eng begrenzte `BiasCurrentColorHint`-Maske reduziert Nachzieher an bewegten Körperteilen, insbesondere an Füßen und Schuhen.
- Streamline-Ressourcen werden lokal mit `eValidUntilEvaluate` übergeben. Dadurch ist der fehlerhafte volatile Eingabeklonpfad beseitigt.
- Die Vulkan-Promotion von Buffer Device Address sowie die Aushandlung von `shaderStorageImageWriteWithoutFormat` wurden korrigiert.
- Die Descriptor-Buchhaltung der Release- und Diagnose-Motion-Pfade sowie das GPU-sichere Aufräumen von Sampler- und Cache-Generationen wurden korrigiert.
- Entfernte Bricks-`composite_block`-Geometrie verschwindet nicht mehr am normalen 64-Blöcke-Limit des Block-Entity-Renderers. Ab dort übernimmt ein gecachtes Fernmesh bis zu Minecrafts effektiver Sichtweite.
- Dächer, Fassaden, Gitter und weitere aus Bricks-Mikroblöcken aufgebaute Strukturen bleiben auch über größere Entfernungen sichtbar.

## Neu hinzugekommen

- Eine exakte Blockatlas- und Pipeline-Allowlist mit fünf vollständigen Solid-Terrain-IDs und vier vollständigen Cutout-Terrain-IDs.
- Ein an Gerät und Generation gebundener Samplercache für Render-/Ausgabemaße, DLSS-Modus, Preset, Reload-Epoche und Device-Generation.
- Eine RGBA8_UNORM-`BiasCurrentColorHint`-Textur für lokale Spieler-Verdeckungswechsel inklusive Vulkan-/Streamline-Lebenszyklus und Resize-Unterstützung.
- Projizierte Rechtecke gegliederter Spielerteile aus dem vorherigen Frame und ein auf Weltpixel begrenztes History-Rejection-Gate.
- Ein exakt gepinntes Bricks-1.0.1-Kompatibilitätsmodul für den Composite-Block-Renderer.
- Gecachte Bricks-Fernmeshes mit komponentensicherem Greedy-Merging. Material, Silhouette, Richtung, Texturatlasbereich, UV-Grenzen, UV-Drehung und Transluzenz bleiben erhalten.
- Frameweites Bricks-Batching nach Textur und Transluzenz, kamerarelative Platzierung, unveränderliche NeoForge-Custom-Geometry-Einreichungen und Fern-nach-Nah-Sortierung transparenter Quads.
- Automatische Ungültigmachung des Fernmesh-Caches, sobald Bricks seine sichtbaren Flächen aktualisiert.
- Erweiterte Artefakt-, UV-, Lebenszyklus-, Resize-, Shader-, Vulkan- und Regressionstests.

## Kompatibilität

- Minecraft `26.2`
- NeoForge `26.2.0.57`
- Sodium `0.9.1+mc26.2`, einschließlich NEAREST- und RGSS-Pfad
- Milkshade Solid- und Cutout-Terrainpipelines
- Bricks `1.0.1` Composite-Block-Renderer
- NVIDIA DLSS `310.7.0` und Streamline `2.12.0`


# BlockFrame DLSS – Release-Prüfung

## 0.3.18 Bricks-Fern-LOD-Kandidat – UNVERÖFFENTLICHT / NICHT FREIGEGEBEN

- Gültig ausschließlich für das exakt gepinnte Bricks-`1.0.1`-Artefakt
  `B380F3678A0AB1E0BA3375994FD309D72638F5CA3503C931D40E93AEA79426B8`
  und die exakt geprüften Minecraft-26.2-/NeoForge-26.2.0.57-Klassen und
  Aufrufstellen. Jede Abweichung deaktiviert den Pfad fail-closed.
- Bis einschließlich 64 Blöcke bleiben Originalrenderer und Originalextraktion
  von Bricks unverändert. Jenseits davon läuft ausschließlich der gecachte,
  nach Komponente/Ebene/Richtung gegreedete und nach Textur/Transluzenz
  gebatchte Fernmesh-Pfad bis zu Minecrafts effektiver Sichtweite.
- `blockframe.compat.bricksCompositeViewDistanceBlocks=64` ist die
  Vanilla-Distanzkontrolle. Jeder akzeptierte Wert über 64 aktiviert den
  Fernmesh-Pfad; 96/128/160 sind keine rohen Vollgeometrie-Distanzen.
- Texturatlasbereich, relative UV-Grenzen, UV-Drehung, Silhouette sowie
  Material- und Transluzenzgrenzen sind durch Quell-/Bytecode-Verträge und
  gezielte Tests abgedeckt. Bei Identitätswechsel der Bricks-Flächenliste wird
  der lokale Mesh-Cache ungültig.
- Zwei getrennte vollständige Clean-Builds sind bytegleich. Jeder ist mit 997
  Tests (994 bestanden, 3 übersprungen, 0 Fehler), Produktions-JAR- und
  Dedicated-Server-Grenze, Phase 2A.0B sowie Native-Runtime-Prüfung bestanden.
  Das 34.003.950-Byte-JAR hat SHA-256
  `32FA02E499476CA25066EFAF2EF1485C743C2938BA8EFF0C7572B823618CC6F1`.
- Inhaltsvergleich: gegenüber E348 `+19/~2/-0`, gegenüber dem abgelösten
  82555-Distanzkandidaten `+13/~8/-0`. Alle neun nativen DLL/SPV/BIN-Einträge
  sind zu beiden bytegleich; gebündelt sind null Bricks-Klassen und null
  Bricks-JARs.
- Der Kandidat ist als ausdrücklich autorisierter Vergleichsstand im
  Hauptprofil installiert. Die erste Nutzer-Sichtprüfung meldet entfernte
  Dächer, Fassaden und Bricks-Strukturen als aktuell sehr gut. Längere FPS-/
  Frame-Zeit-Prüfung und transparente Blockgrenzen bleiben offen. Eine
  Veröffentlichung oder Release-Freigabe besteht nicht; der separat
  dokumentierte globale Vulkan-Shutdown-VUID bleibt Release-Blocker.

## 0.3.16 – Bewiesen

- Ausgangs-JAR dev.5:
  `5C7D06F99E26FF9747FA5672F332F31A5D52C7D0FAD9AA2AEF6D9C457E480D07`
- Finale Release-JAR mit kompakter Menübeschriftung:
  `70E53AF0F3C3F505122438D792EF0568808409F05EEE8CD26288AB2C2DB1B5A4`
- 855 normale Produkt-/Quelltests bestanden, 3 bewusst übersprungen.
- Produktions-JAR-Isolationsgate bestanden.
- JAR-Inhaltsvergleich: 650 Einträge in dev.5, 655 im Release;
  5 UI-Klassen hinzugefügt, 7 UI-/Metadaten-Einträge geändert,
  0 Einträge entfernt.
- 0 Inhaltsabweichungen in Renderer, Motion-Vektoren, Third-Person,
  Temporalcode, NVIDIA-DLLs und SPIR-V-Shadern.
- Gegenüber der bereits live geprüften stabilen Release-JAR unterscheiden
  sich ausschließlich `de_de.json` und `en_us.json`. Der Optionsname lautet
  kompakt `DLSS`; Moduswerte wurden zur Vermeidung von Überlagerungen gekürzt.
- Der Nutzer meldete für den sauberen dev.5-Live-Start: „läuft perfekt“.
- Die stabile Release-JAR startete im isolierten IQ-Profil erfolgreich:
  Vulkan-Backend und Streamline initialisiert, DLSS-Selbsttest bestanden,
  keine aktive Capture-/Replay-/Debug-Instrumentierung.
- Der DLSS-/DLAA-Moduswahlschalter wurde im echten Sodium-Grafikmenü geprüft.
- Anschließender Smoke-Test im normalen Profil `Vulkan 7 days` bestanden:
  Testwelt geladen, DLAA Preset K, Mip-Bias 0, Sharpening 0,
  3840×2130 Render- und Ausgabeauflösung im vorhandenen Fenstermodus sowie
  120 aufeinanderfolgende erfolgreiche Weltframes. Kein neuer
  BlockFrame-Fehler im Testlauf.
- Die finale kompakte Sprachressource ist direkt im normalen Profil
  installiert. Eine erneute visuelle Nutzerprüfung nach dem Neustart steht
  noch aus; sie ändert den bereits bestandenen Render-Smoke nicht.

## 0.3.16 – Nicht als bestanden gewertet

- Die getrennten Phase-2A.0B-Benchmark-Harness-Tests benötigen externe
  Fixture-Dateien aus den auf Nutzerwunsch gelöschten Testinstanzen. Das
  normale 855-Test-Produktgate ist davon unabhängig bestanden.
- Kein zweistündiger Dauertest; dieser wurde vom Nutzer ausdrücklich
  gestrichen.
- Kein CurseForge-Upload durchgeführt.

## 0.3.16 – Release-Differenzen gegenüber dev.5

- stabile Versionskennung `0.3.16-neoforge-26.2`
- direkte Sodium-0.9.1-Menüintegration
- Reese's-Sodium-Options-Fallbackbildschirm
- kompakte, überlappungsfreie Anzeige `DLSS` mit kurzen Moduswerten
- Tooltip mit ausgewähltem Modus, NVIDIA DLSS 310.7.0 und Streamline 2.12.0
- deutsche und englische Release-Dokumentation

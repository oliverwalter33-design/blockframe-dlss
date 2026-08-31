# BlockFrame DLSS 0.3.16 – Release-Prüfung

## Bewiesen

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

## Nicht als bestanden gewertet

- Die getrennten Phase-2A.0B-Benchmark-Harness-Tests benötigen externe
  Fixture-Dateien aus den auf Nutzerwunsch gelöschten Testinstanzen. Das
  normale 855-Test-Produktgate ist davon unabhängig bestanden.
- Kein zweistündiger Dauertest; dieser wurde vom Nutzer ausdrücklich
  gestrichen.
- Kein CurseForge-Upload durchgeführt.

## Release-Differenzen gegenüber dev.5

- stabile Versionskennung `0.3.16-neoforge-26.2`
- direkte Sodium-0.9.1-Menüintegration
- Reese's-Sodium-Options-Fallbackbildschirm
- kompakte, überlappungsfreie Anzeige `DLSS` mit kurzen Moduswerten
- Tooltip mit ausgewähltem Modus, NVIDIA DLSS 310.7.0 und Streamline 2.12.0
- deutsche und englische Release-Dokumentation

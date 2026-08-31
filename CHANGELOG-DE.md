# BlockFrame DLSS 0.3.16 – Changelog

## Behoben

- Lange Third-Person-Schlieren und alte Doppelkonturen am Spieler behoben.
- Nachzieheffekte an Armen, Beinen, gehaltenen Gegenständen, großen
  Itemmodellen und zusätzlichen Skin-Layern behoben.
- Weltverschmierung während Kamerabewegungen mit DLAA behoben.
- Fehlerhafte Bewegungsdaten beim Wechsel zwischen First Person, Third Person
  hinten und Third Person vorne korrigiert.
- Zeitliche Artefakte durch nicht zusammengehörende Current-/Previous-Daten
  beseitigt.

## Rendering

- Kamera-relative Reprojektion für statische Weltgeometrie implementiert.
- Current- und Previous-Kameramatrizen werden jetzt atomar pro Renderframe
  veröffentlicht.
- Bewegungsvektoren für Spieler, Entities und gehaltene Gegenstände
  überarbeitet.
- RG16F-Bewegungswerte werden jetzt richtungserhaltend auf den gültigen
  Wertebereich abgebildet.
- Temporale Ressourcenrotation und History-Übergabe an aufeinanderfolgende
  Renderframes angepasst.
- Release- und Diagnose-Motion-Shader in getrennte Varianten aufgeteilt.

## Grafikeinstellungen

- DLSS-/DLAA-Modusauswahl zum Vanilla-Grafikmenü hinzugefügt.
- Direkte DLSS-/DLAA-Auswahl in Sodium 0.9.1 hinzugefügt.
- Fallback-Einstellungsbildschirm für Reese's Sodium Options hinzugefügt.
- Modi Aus, Qualität, Ausgeglichen, Leistung, DLAA und Ultra (4K)
  hinzugefügt.
- Optionsname auf `DLSS` und die Moduswerte auf kurze Bezeichnungen gekürzt,
  damit sich die Texte im Grafikmenü nicht überlagern.
- Tooltip um den gewählten Modus sowie NVIDIA-DLSS- und Streamline-Versionen
  erweitert.

## Diagnose und Performance

- Capture-Sequenzen, GPU-Readbacks, PNG-Ausgabe, Replay-Harness, Tracy,
  GPU-Breadcrumbs und Debug-Mixins hinter einen Entwickler-Hauptschalter
  verschoben.
- Debug-Bildbindungen und Diagnosezweige aus dem normalen Motion-Shader
  entfernt.
- Veraltete Diagnose-Hinweise und zugehörige Laufzeitschalter entfernt.

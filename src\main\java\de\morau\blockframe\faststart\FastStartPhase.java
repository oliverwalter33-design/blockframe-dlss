package de.morau.blockframe.faststart;

/**
 * Canonical BlockFrame FastStart milestones.
 *
 * <p>T0, T1, T2, T3, T9, T10, T12 and T17 are canonical external-runner
 * observations. The remaining phases have supported in-process hooks. Keeping
 * all phase identifiers here lets the external runner merge both clocks
 * without inventing phase names.</p>
 */
public enum FastStartPhase {
    T0("launcher_play_click", "Klick auf Spielen", Owner.EXTERNAL),
    T1("java_process_created", "Java-Prozess erstellt", Owner.EXTERNAL),
    T2("mod_discovery_started", "NeoForge/Mod-Discovery begonnen", Owner.EXTERNAL),
    T3("mod_discovery_finished", "Discovery/Scan/Transformation beendet", Owner.EXTERNAL),
    T4("mod_lifecycle_finished", "Registrierung und Mod-Lifecycle beendet", Owner.INTERNAL),
    T5("client_construction_finished", "Clientkonstruktion beendet", Owner.INTERNAL),
    T6("resource_reload_started", "Client-Ressourcenreload begonnen", Owner.INTERNAL),
    T7("resource_reload_finished", "Client-Ressourcenreload beendet", Owner.INTERNAL),
    T8("interactive_title_frame", "Erster Hauptmenü-Frame gezeichnet", Owner.INTERNAL),
    T9("menu_input_verified", "Menüeingabe extern verifiziert", Owner.EXTERNAL),
    T10("world_entry_clicked", "Weltbeitritt angeklickt", Owner.EXTERNAL),
    T11("server_data_ready", "Server-Datapack/Registry bereit", Owner.INTERNAL),
    T12("spawn_city_poi_search_finished", "Spawn-/Stadt-/POI-Suche beendet", Owner.EXTERNAL),
    T13("start_chunks_ready", "Startchunks generiert und beleuchtet", Owner.INTERNAL),
    T14("player_login_finished", "Spielerlogin beendet", Owner.INTERNAL),
    T15("player_camera_input_ready", "Spieler und Kamera reagieren", Owner.EXTERNAL),
    T16("visible_landscape_ready", "Sichtbare Landschaft und Renderqueues bereit", Owner.INTERNAL),
    T17("camera_360_verified", "360-Grad-Sicht extern verifiziert", Owner.EXTERNAL);

    public enum Owner {
        INTERNAL,
        EXTERNAL
    }

    private final String id;
    private final String label;
    private final Owner owner;

    FastStartPhase(String id, String label, Owner owner) {
        this.id = id;
        this.label = label;
        this.owner = owner;
    }

    public String id() {
        return this.id;
    }

    public String label() {
        return this.label;
    }

    public Owner owner() {
        return this.owner;
    }
}

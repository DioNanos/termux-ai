package com.termux.app.terminal.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A model configuration: release stage and preference. Unknown values are
 * errors, never silently replaced by a default.
 */
public final class ModelSelection {

    public enum Stage {
        STABLE("stable"), PREVIEW("preview");

        public final String wire;

        Stage(String wire) { this.wire = wire; }
    }

    public enum Preference {
        FULL("full"), FAST("fast");

        public final String wire;

        Preference(String wire) { this.wire = wire; }
    }

    /** The SDK default: stable release, full preference. */
    public static final ModelSelection DEFAULT = new ModelSelection(Stage.STABLE, Preference.FULL);

    public final Stage stage;
    public final Preference preference;

    public ModelSelection(Stage stage, Preference preference) {
        this.stage = stage;
        this.preference = preference;
    }

    /** Null means "not given" and takes the default; any other unknown value throws. */
    public static ModelSelection parse(String stage, String preference) {
        Stage s = Stage.STABLE;
        if (stage != null) {
            s = null;
            for (Stage candidate : Stage.values()) if (candidate.wire.equals(stage)) s = candidate;
            if (s == null) throw new IllegalArgumentException("stage must be stable or preview (got: " + stage + ")");
        }
        Preference p = Preference.FULL;
        if (preference != null) {
            p = null;
            for (Preference candidate : Preference.values()) if (candidate.wire.equals(preference)) p = candidate;
            if (p == null) throw new IllegalArgumentException("preference must be full or fast (got: " + preference + ")");
        }
        return new ModelSelection(s, p);
    }

    /** The four combinations, stable first. */
    public static List<ModelSelection> all() {
        List<ModelSelection> out = new ArrayList<>();
        for (Stage s : Stage.values()) for (Preference p : Preference.values()) out.add(new ModelSelection(s, p));
        return Collections.unmodifiableList(out);
    }

    @Override public boolean equals(Object o) {
        return o instanceof ModelSelection && ((ModelSelection) o).stage == stage && ((ModelSelection) o).preference == preference;
    }

    @Override public int hashCode() { return stage.hashCode() * 31 + preference.hashCode(); }

    @Override public String toString() { return stage.wire + "/" + preference.wire; }
}

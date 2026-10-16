package org.darselx.endlessember;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import java.util.Locale;

public final class PlantParticleConfig {
    public enum Mode { FULL, REDUCED, OFF }

    public static final BuilderCodec<PlantParticleConfig> CODEC = BuilderCodec
            .builder(PlantParticleConfig.class, PlantParticleConfig::new)
            .append(new KeyedCodec<>("Mode", Codec.STRING),
                    (cfg, value) -> cfg.mode = value, cfg -> cfg.mode)
            .add().build();

    private String mode = "FULL";

    public Mode getMode() {
        return parse(mode);
    }

    public void setMode(Mode value) {
        mode = value.name();
    }

    String getValue() { return mode; }

    void restoreValue(String value) { mode = value; }

    public static Mode parse(String value) {
        if (value == null) throw new IllegalArgumentException("Use FULL, REDUCED or OFF.");
        try {
            return Mode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Use FULL, REDUCED or OFF.");
        }
    }
}

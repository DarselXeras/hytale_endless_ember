package org.darselx.endlessember;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import java.util.Locale;

/** Server-owned nuclear terrain policy. Each impact snapshots it into its clearing job. */
public final class NuclearRocketConfig {
    public enum Mode { NONE, VEGETATION, CRATER }
    public record Settings(Mode mode,int craterDepth){
        public Settings{if(mode==null)mode=Mode.NONE;craterDepth=Math.clamp(craterDepth,1,20);}
    }
    static volatile Settings active=new Settings(Mode.NONE,10);
    public static final BuilderCodec<NuclearRocketConfig> CODEC=BuilderCodec.builder(NuclearRocketConfig.class,NuclearRocketConfig::new)
        .append(new KeyedCodec<>("DestructionMode",Codec.STRING),(cfg,value)->cfg.mode=value,cfg->cfg.mode).add()
        .append(new KeyedCodec<>("CraterDepth",Codec.INTEGER),(cfg,value)->cfg.depth=value,cfg->cfg.depth).add().build();
    private String mode="NONE";
    private int depth=10;
    public Settings settings(){if(mode==null)throw new IllegalArgumentException("Missing DestructionMode");return new Settings(Mode.valueOf(mode.trim().toUpperCase(Locale.ROOT)),depth);}
}

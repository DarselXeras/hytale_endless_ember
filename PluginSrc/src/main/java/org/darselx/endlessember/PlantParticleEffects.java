package org.darselx.endlessember;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelParticle;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;

/** Applies the startup setting before block assets are sent to clients. */
final class PlantParticleEffects {
    private static final String[] CROPS = {
            "AurumBloom", "BlueStarFlower", "ConductiveBlossom", "GlitterThistle",
            "MirrorFlower", "MysticalMountainEye", "OnyxiumHeart", "RustyFlower",
            "SpectralBloom", "WorldCoreBloom"
    };
    private final PlantParticleConfig.Mode mode;
    private final Map<BlockType, ModelParticle[]> originals = new IdentityHashMap<>();
    private final Field particles;
    private final Field cachedPacket;

    PlantParticleEffects(PlantParticleConfig.Mode mode) throws ReflectiveOperationException {
        this.mode = mode;
        // BlockType exposes no particle setter or packet-cache invalidation API in 0.7.1.
        particles = BlockType.class.getDeclaredField("particles");
        particles.setAccessible(true);
        cachedPacket = BlockType.class.getDeclaredField("cachedPacket");
        cachedPacket.setAccessible(true);
    }

    PlantParticleConfig.Mode getMode() { return mode; }

    synchronized void apply(Collection<BlockType> assets) {
        for (BlockType block : assets) {
            if (!isModCrop(block.getId())) continue;
            ModelParticle[] current = block.getParticles();
            if (!originals.containsKey(block)) {
                if (current == null || current.length == 0) continue;
                originals.put(block, current);
            }
            ModelParticle[] selected = select(originals.get(block), mode);
            try {
                if (current != selected) {
                    particles.set(block, selected);
                    cachedPacket.set(block, null);
                }
            } catch (IllegalAccessException error) {
                throw new IllegalStateException("Cannot configure plant particles", error);
            }
        }
    }

    synchronized String summary() {
        int before = 0;
        int after = 0;
        for (var entry : originals.entrySet()) {
            before += entry.getValue().length;
            ModelParticle[] active = entry.getKey().getParticles();
            after += active == null ? 0 : active.length;
        }
        return "mode=" + mode + ", crop assets=" + originals.size()
                + ", emitters=" + after + "/" + before;
    }

    static boolean isModCrop(String id) {
        if (id == null) return false;
        for (String crop : CROPS) {
            // Generated state assets include their parent item ID in their key.
            if (id.contains("Plant_Crop_" + crop + "_Block")) return true;
        }
        return false;
    }

    static ModelParticle[] select(ModelParticle[] original, PlantParticleConfig.Mode mode) {
        return switch (mode) {
            case FULL -> original;
            case REDUCED -> original.length <= 1 ? original : Arrays.copyOf(original, 1);
            case OFF -> new ModelParticle[0];
        };
    }
}

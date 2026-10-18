package org.darselx.endlessember;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.modules.entity.damage.*;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** The native effect owns and serializes its multiplier; each application starts independently. */
public final class PollenPoisonProgression extends DamageEventSystem {
    static final String ID="Ember_Pollen_Poison";
    @Override public Query<EntityStore> getQuery(){return Query.any();}
    @Override public SystemGroup<EntityStore> getGroup(){return DamageModule.get().getGatherDamageGroup();}
    static void advance(Damage damage){
        ActiveEntityEffect active=damage.getSource() instanceof ActiveEntityEffect direct?direct:
            damage.getSource() instanceof Damage.EntityEffectSource owned?owned.getEffect():null;
        if(active==null)return;
        var effect=EntityEffect.getAssetMap().getAsset(active.getEntityEffectIndex());
        if(effect==null||!ID.equals(effect.getId())||!"Poison".equals(damage.getCause().getId()))return;
        // This tick has already been scaled by ActiveEntityEffect.tickDamage.
        // Update the native, persistent multiplier for the next tick only.
        active.setDamageMultiplier((float)Math.min(Float.MAX_VALUE,(double)active.getDamageMultiplier()*2));
    }
    @Override public void handle(int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> buffer,Damage damage){advance(damage);}
}

package org.darselx.endlessember;

import com.hypixel.hytale.codec.*;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.protocol.ItemArmorSlot;
import com.hypixel.hytale.server.core.inventory.*;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.modules.entity.damage.*;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** Medium ranged set: native reduced camera kick and a finite, non-stacking escape pulse. */
public final class CoilhunterArmor implements Component<EntityStore> {
    static final String PREFIX="Coilhunter_MK3_",EFFECT="Ember_Coilhunter_Pulse";
    static ComponentType<EntityStore,CoilhunterArmor> TYPE;
    static final double COOLDOWN=10;
    double remaining;
    public static final BuilderCodec<CoilhunterArmor> CODEC=BuilderCodec.builder(CoilhunterArmor.class,CoilhunterArmor::new)
        .append(new KeyedCodec<>("Cooldown",Codec.DOUBLE),(o,v)->o.remaining=Double.isFinite(v)?Math.clamp(v,0,COOLDOWN):0,o->o.remaining).add().build();
    public CoilhunterArmor(){}
    @Override public CoilhunterArmor clone(){var copy=new CoilhunterArmor();copy.remaining=remaining;return copy;}
    static void register(EndlessEmberPlugin plugin){
        TYPE=plugin.getEntityStoreRegistry().registerComponent(CoilhunterArmor.class,"EmberCoilhunterCooldown",CODEC);
        plugin.getEntityStoreRegistry().registerSystem(new Hit());plugin.getEntityStoreRegistry().registerSystem(new Tick());
    }
    static boolean fullSet(Ref<EntityStore> ref,ComponentAccessor<EntityStore> access){
        var component=access.getComponent(ref,InventoryComponent.Armor.getComponentType());if(component==null)return false;
        var armor=component.getInventory();
        for(var slot:ItemArmorSlot.VALUES){
            if(slot.getValue()>=armor.getCapacity())return false;
            var stack=armor.getItemStack((short)slot.getValue());
            if(ItemStack.isEmpty(stack)||stack.isBroken()||!stack.getItemId().equals(PREFIX+slot.name()))return false;
        }
        return true;
    }
    static boolean eligible(Damage damage,Ref<EntityStore> target){
        if(damage.isCancelled()||damage.getAmount()<=0||damage.getCause()==null)return false;
        String cause=damage.getCause().getId();
        if(!cause.equals("Physical")&&!cause.equals("Projectile"))return false;
        return damage.getSource() instanceof Damage.EntitySource source&&source.getRef()!=null&&source.getRef().isValid()&&!source.getRef().equals(target);
    }
    static boolean hit(Ref<EntityStore> ref,Damage damage,ComponentAccessor<EntityStore> access){
        if(!eligible(damage,ref)||!EmberAdvancedArsenal.living(ref,access)||!fullSet(ref,access))return false;
        var state=access.getComponent(ref,TYPE);if(state!=null&&state.remaining>0)return false;
        var controller=access.getComponent(ref,EffectControllerComponent.getComponentType());var effect=EntityEffect.getAssetMap().getAsset(EFFECT);
        if(controller==null||effect==null||!controller.addEffect(ref,effect,access))return false;
        if(state==null)state=new CoilhunterArmor();state.remaining=COOLDOWN;access.putComponent(ref,TYPE,state);return true;
    }
    static void advance(Ref<EntityStore> ref,CoilhunterArmor state,double dt,ComponentAccessor<EntityStore> access){
        if(Double.isFinite(dt)&&dt>0)state.remaining=Math.max(0,state.remaining-dt);
        var controller=access.getComponent(ref,EffectControllerComponent.getComponentType());int effect=EntityEffect.getAssetMap().getIndex(EFFECT);
        boolean active=controller!=null&&controller.getActiveEffects().containsKey(effect);
        if(active&&!fullSet(ref,access)){controller.removeEffect(ref,effect,access);active=false;}
        // Keep cooldown when armor is removed; swapping equipment cannot reset it.
        if(state.remaining<=0&&!active)access.removeComponent(ref,TYPE);
    }
    static final class Hit extends DamageEventSystem {
        @Override public Query<EntityStore> getQuery(){return InventoryComponent.Armor.getComponentType();}
        @Override public SystemGroup<EntityStore> getGroup(){return DamageModule.get().getInspectDamageGroup();}
        @Override public void handle(int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> buffer,Damage damage){hit(chunk.getReferenceTo(index),damage,buffer);}
    }
    static final class Tick extends EntityTickingSystem<EntityStore>{
        @Override public Query<EntityStore> getQuery(){return TYPE;}
        @Override public void tick(float dt,int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> buffer){advance(chunk.getReferenceTo(index),chunk.getComponent(index,TYPE),dt,buffer);}
    }
}

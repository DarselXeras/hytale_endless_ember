package org.darselx.endlessember;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.component.dependency.*;
import com.hypixel.hytale.protocol.ItemArmorSlot;
import com.hypixel.hytale.server.core.inventory.*;
import com.hypixel.hytale.server.core.modules.entity.condition.*;
import com.hypixel.hytale.server.core.modules.entity.damage.*;
import com.hypixel.hytale.server.core.modules.entitystats.*;
import com.hypixel.hytale.server.core.modules.entitystats.asset.*;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.time.Instant;
import java.util.*;

/** Full-set utility applies only to equipped, unbroken Emberrunner MK2 pieces. */
final class EmberrunnerArmor {
    static final String PREFIX="Emberrunner_MK2_";
    static final float MULTIPLIER=.8f;
    static final SprintingCondition SPRINTING=new SprintingCondition(false);
    static void register(EndlessEmberPlugin plugin){plugin.getEntityStoreRegistry().registerSystem(new InstallSprint());plugin.getEntityStoreRegistry().registerSystem(new FireProtection());}
    static boolean fullSet(Ref<EntityStore> ref,ComponentAccessor<EntityStore> access){
        var equipped=access.getComponent(ref,InventoryComponent.Armor.getComponentType());if(equipped==null)return false;
        var armor=equipped.getInventory();
        for(var slot:ItemArmorSlot.VALUES){
            if(slot.getValue()>=armor.getCapacity())return false;
            var item=armor.getItemStack((short)slot.getValue());
            if(ItemStack.isEmpty(item)||item.isBroken()||!item.getItemId().equals(PREFIX+slot.name()))return false;
        }
        return true;
    }
    static void install(EntityStatMap stats){
        var stamina=stats.get(DefaultEntityStatTypes.getStamina());if(stamina==null)return;
        var values=stamina.getRegeneratingValues();if(values==null)return;
        for(int i=0;i<values.length;i++){
            var value=values[i];if(value instanceof SprintDrain)continue;
            var rule=value.getRegenerating();var conditions=rule.getConditions();
            if(rule.getAmount()<0&&conditions!=null&&Arrays.stream(conditions).anyMatch(c->c instanceof SprintingCondition))values[i]=new SprintDrain(value);
        }
    }
    static final class SprintDrain extends RegeneratingValue {
        final RegeneratingValue nativeValue;
        SprintDrain(RegeneratingValue original){super(original.getRegenerating());nativeValue=original;}
        @Override public float regenerate(ComponentAccessor<EntityStore> access,Ref<EntityStore> ref,Instant time,float dt,EntityStatValue stat,float cumulativeDelta){
            // Preserve the native interval and conditions. Scale before native clamping,
            // so the last fraction of stamina reaches zero instead of allowing endless sprint.
            var rule=getRegenerating();
            if(!nativeValue.shouldRegenerate(access,ref,time,dt,rule))return 0;
            float delta=rule.getAmount();
            if(rule.getRegenType()==EntityStatType.Regenerating.RegenType.PERCENTAGE)delta*=stat.getMax()-stat.getMin();
            if(rule.getModifiers()!=null)for(var modifier:rule.getModifiers())delta*=modifier.getModifier(access,ref,time);
            if(delta<0&&SPRINTING.eval(access,ref,time)&&fullSet(ref,access))delta*=MULTIPLIER;
            return rule.clampAmount(delta,cumulativeDelta,stat);
        }
    }
    static final class InstallSprint extends EntityTickingSystem<EntityStore>{
        @Override public Query<EntityStore> getQuery(){return Query.and(EntityStatMap.getComponentType(),InventoryComponent.Armor.getComponentType());}
        @Override @SuppressWarnings({"unchecked","rawtypes"}) public Set<Dependency<EntityStore>> getDependencies(){return Set.of(new SystemDependency(Order.BEFORE,EntityStatsModule.PlayerRegenerateStatsSystem.class));}
        @Override public void tick(float dt,int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> buffer){install(chunk.getComponent(index,EntityStatMap.getComponentType()));}
    }
    static void fire(Damage damage,boolean equipped){
        if(equipped&&!damage.isCancelled()&&damage.getCause()!=null&&!damage.getCause().doesBypassResistances()&&damage.getCause().getId().equals("Fire"))damage.setAmount(damage.getAmount()*MULTIPLIER);
    }
    static final class FireProtection extends DamageEventSystem {
        @Override public Query<EntityStore> getQuery(){return InventoryComponent.Armor.getComponentType();}
        @Override public SystemGroup<EntityStore> getGroup(){return DamageModule.get().getFilterDamageGroup();}
        @Override public void handle(int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> buffer,Damage damage){fire(damage,fullSet(chunk.getReferenceTo(index),buffer));}
    }
}

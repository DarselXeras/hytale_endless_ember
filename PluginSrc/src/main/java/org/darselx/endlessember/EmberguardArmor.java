package org.darselx.endlessember;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.protocol.ItemArmorSlot;
import com.hypixel.hytale.server.core.inventory.*;
import com.hypixel.hytale.server.core.meta.MetaKey;
import com.hypixel.hytale.server.core.modules.entity.damage.*;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** Heavy MK4 set. Explicit blast metadata distinguishes explosions from bullets. */
final class EmberguardArmor {
    static final String PREFIX="Emberguard_MK4_";
    static final float MULTIPLIER=.7f;
    static final MetaKey<Boolean> BLAST=Damage.META_REGISTRY.registerMetaObject(d->false);

    static void register(EndlessEmberPlugin plugin){plugin.getEntityStoreRegistry().registerSystem(new Protection());}
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
    static void protect(Damage damage,boolean equipped){
        if(!equipped||damage.isCancelled()||damage.getAmount()<=0||damage.getCause()==null||damage.getCause().doesBypassResistances())return;
        if(damage.getCause().getId().equals("Fire")||Boolean.TRUE.equals(damage.getIfPresentMetaObject(BLAST)))damage.setAmount(damage.getAmount()*MULTIPLIER);
    }
    static final class Protection extends DamageEventSystem {
        @Override public Query<EntityStore> getQuery(){return InventoryComponent.Armor.getComponentType();}
        @Override public SystemGroup<EntityStore> getGroup(){return DamageModule.get().getFilterDamageGroup();}
        @Override public void handle(int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> buffer,Damage damage){protect(damage,fullSet(chunk.getReferenceTo(index),buffer));}
    }
}

package org.darselx.endlessember;

import com.hypixel.hytale.builtin.adventure.farming.interactions.HarvestCropInteraction;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.protocol.*;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.event.events.ecs.InteractivelyPickupItemEvent;
import com.hypixel.hytale.server.core.inventory.*;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3i;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** Only actual synchronous native crop rewards can receive the farming bonus. */
public final class EmberfarmerClothes extends HarvestCropInteraction {
    public static final BuilderCodec<EmberfarmerClothes> CODEC=BuilderCodec.builder(EmberfarmerClothes.class,EmberfarmerClothes::new,HarvestCropInteraction.CODEC).build();
    static final String PREFIX="Emberfarmer_MK2_";
    static final double CHANCE=.25;
    static final Set<String> CROPS=Set.of("ConductiveBlossom","RustyFlower","MirrorFlower","BlueStarFlower","GlitterThistle","AurumBloom","WorldCoreBloom","MysticalMountainEye","OnyxiumHeart","SpectralBloom");
    record Harvest(Ref<EntityStore> owner,boolean doubled){}
    static final ThreadLocal<Harvest> ACTIVE=new ThreadLocal<>();
    public EmberfarmerClothes(){}
    static void register(EndlessEmberPlugin plugin){
        plugin.getCodecRegistry(Interaction.CODEC).register("EE_FarmerHarvest",EmberfarmerClothes.class,CODEC);
        plugin.getEntityStoreRegistry().registerSystem(new Reward());
    }
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
    static boolean pollen(ItemStack stack){
        if(ItemStack.isEmpty(stack))return false;
        return CROPS.stream().anyMatch(c->stack.getItemId().equals("Ingredient_"+c+"_Pollen"));
    }
    static void duringHarvest(Ref<EntityStore> owner,boolean complete,double roll,Runnable nativeHarvest){
        var previous=ACTIVE.get();
        ACTIVE.set(new Harvest(owner,complete&&roll>=0&&roll<CHANCE));
        try{nativeHarvest.run();}finally{if(previous==null)ACTIVE.remove();else ACTIVE.set(previous);}
    }
    @Override protected void interactWithBlock(World world,CommandBuffer<EntityStore> buffer,InteractionType type,InteractionContext ctx,ItemStack held,Vector3i pos,CooldownHandler cooldown){
        harvest(world,buffer,type,ctx,held,pos,cooldown,ThreadLocalRandom.current().nextDouble());
    }
    void harvest(World world,CommandBuffer<EntityStore> buffer,InteractionType type,InteractionContext ctx,ItemStack held,Vector3i pos,CooldownHandler cooldown,double roll){
        var owner=ctx.getEntity();
        duringHarvest(owner,fullSet(owner,buffer),roll,()->super.interactWithBlock(world,buffer,type,ctx,held,pos,cooldown));
    }
    static void reward(Ref<EntityStore> owner,InteractivelyPickupItemEvent event){
        var harvest=ACTIVE.get();var stack=event.getItemStack();
        if(harvest==null||!harvest.doubled()||!harvest.owner().equals(owner)||event.isCancelled()||!pollen(stack)||stack.getQuantity()>Integer.MAX_VALUE/2)return;
        event.setItemStack(stack.withQuantity(stack.getQuantity()*2));
    }
    static final class Reward extends EntityEventSystem<EntityStore,InteractivelyPickupItemEvent>{
        Reward(){super(InteractivelyPickupItemEvent.class);}
        @Override public Query<EntityStore> getQuery(){return InventoryComponent.Armor.getComponentType();}
        @Override public void handle(int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> buffer,InteractivelyPickupItemEvent event){reward(chunk.getReferenceTo(index),event);}
    }
}

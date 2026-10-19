package org.darselx.endlessember;

import com.hypixel.hytale.codec.*;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.protocol.*;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.DespawnComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.*;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.time.TimeResource;
import com.hypixel.hytale.server.core.modules.projectile.ProjectileModule;
import com.hypixel.hytale.server.core.modules.projectile.config.ProjectileConfig;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import org.bson.BsonInt64;
import org.joml.Vector3d;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** MK3 automatic weapon: server-owned ammo, spread, cadence and impact effects. */
public final class EmberMachineGun extends SimpleInstantInteraction {
    public static final BuilderCodec<EmberMachineGun> CODEC=BuilderCodec.builder(EmberMachineGun.class,EmberMachineGun::new,SimpleInstantInteraction.CODEC)
        .append(new KeyedCodec<>("Action",Codec.STRING),(o,v)->o.action=v,o->o.action).add().build();
    static final String ID="Ember_Machine_Gun";
    static final String[] KINDS={"Normal","Tracer","Explosive","Poison"};
    static final float DAMAGE=12,SPLASH=DAMAGE*.6f;
    static final double RADIUS=5,SPREAD=Math.toRadians(.65),IGNITE_CHANCE=.30;
    String action="Fire";
    public EmberMachineGun(){}
    static void register(EndlessEmberPlugin plugin){
        plugin.getCodecRegistry(Interaction.CODEC).register("EE_MachineGun",EmberMachineGun.class,CODEC);
        MachineGunPoison.TYPE=plugin.getEntityStoreRegistry().registerComponent(MachineGunPoison.class,"EE_MachineGunPoison",MachineGunPoison.CODEC);
        plugin.getEntityStoreRegistry().registerSystem(new MachineGunPoison.Tick());
    }
    @Override public WaitForDataFrom getWaitForDataFrom(){return WaitForDataFrom.Server;}
    @Override public boolean needsRemoteSync(){return true;}
    static String ammo(int mode){return "Ember_Ammo_MachineGun_"+KINDS[mode];}
    static String config(int mode){return ID+"_"+KINDS[mode];}
    @Override protected void firstRun(InteractionType type,InteractionContext ctx,CooldownHandler cooldown){
        switch(action){
            case "Fire"->fire(ctx,System.currentTimeMillis(),ThreadLocalRandom.current().nextDouble(),ThreadLocalRandom.current().nextDouble());
            case "Tracer"->{if(ThreadLocalRandom.current().nextDouble()<IGNITE_CHANCE)effect(ctx.getTargetEntity(),ctx.getOwningEntity(),"Ember_MachineGun_Burn",ctx.getCommandBuffer());}
            case "Explosive"->explode(ctx);
            case "Poison"->poison(ctx);
            default->EmberWeaponGate.fail(ctx,null);
        }
    }
    static Vector3d direction(Vector3d aim,double radial,double azimuth){
        var forward=new Vector3d(aim).normalize();
        var right=new Vector3d(forward).cross(Math.abs(forward.y)>.99?new Vector3d(1,0,0):new Vector3d(0,1,0)).normalize();
        var up=new Vector3d(right).cross(forward).normalize();
        double radius=Math.tan(SPREAD)*Math.sqrt(Math.clamp(radial,0,1)),angle=2*Math.PI*azimuth;
        return forward.fma(radius*Math.cos(angle),right).fma(radius*Math.sin(angle),up).normalize();
    }
    static void fire(InteractionContext ctx,long now,double radial,double azimuth){
        var held=ctx.getHeldItem();
        if(ItemStack.isEmpty(held)||!ID.equals(held.getItemId())||ctx.getHeldItemContainer()==null){EmberWeaponGate.fail(ctx,null);return;}
        var state=EmberWeaponGate.state(held);boolean creative=EmberWeaponGate.creative(ctx);
        if((!creative&&held.isBroken())||now<EmberWeaponGate.number(state,"MachineGunNextShot",0)){EmberWeaponGate.fail(ctx,null);return;}
        int mode=EmberWeaponGate.selected(held,4);var inventory=EmberWeaponGate.inventory(ctx);var cartridge=new ItemStack(ammo(mode));
        if(!creative&&!inventory.canRemoveItemStack(cartridge,true,true)){EmberWeaponGate.fail(ctx,null);return;}
        var access=ctx.getCommandBuffer();var asset=ProjectileConfig.getAssetMap().getAsset(config(mode));
        if(asset==null){EmberWeaponGate.fail(ctx,null);return;}
        var look=TargetUtil.getLook(ctx.getOwningEntity(),access);var aim=new Vector3d(look.getDirection());
        if(!aim.isFinite()||aim.lengthSquared()<1e-9){EmberWeaponGate.fail(ctx,null);return;}
        var ref=ProjectileModule.get().spawnProjectile(ctx.getOwningEntity(),access,asset,new Vector3d(look.getPosition()),direction(aim,radial,azimuth));
        if(ref==null){EmberWeaponGate.fail(ctx,null);return;}
        if(!creative&&!inventory.removeItemStack(cartridge,true,true).succeeded()){access.removeEntity(ref,RemoveReason.REMOVE);EmberWeaponGate.fail(ctx,null);return;}
        access.putComponent(ref,DespawnComponent.getComponentType(),DespawnComponent.despawnInSeconds(access.getResource(TimeResource.getResourceType()),3));
        state.put("MachineGunNextShot",new BsonInt64(now+500));
        var updated=held.withMetadata(EmberWeaponGate.META,state);
        if(!creative&&!held.isUnbreakable())updated=updated.withDurability(Math.max(0,updated.getDurability()-.2));
        ctx.getHeldItemContainer().setItemStackForSlot(ctx.getHeldItemSlot(),updated);ctx.setHeldItem(updated);
    }
    static void effect(Ref<EntityStore> target,Ref<EntityStore> owner,String id,ComponentAccessor<EntityStore> access){
        if(!EmberAdvancedArsenal.living(target,access))return;
        var controller=access.getComponent(target,EffectControllerComponent.getComponentType());var effect=EntityEffect.getAssetMap().getAsset(id);
        if(controller!=null&&effect!=null)controller.addEffect(target,effect,access,owner);
    }
    static void poison(InteractionContext ctx){
        var target=ctx.getTargetEntity();var access=ctx.getCommandBuffer();
        if(!EmberAdvancedArsenal.living(target,access))return;
        var old=access.getComponent(target,MachineGunPoison.TYPE);var state=old==null?new MachineGunPoison():old.clone();
        if(!state.add())return;
        var owner=access.getComponent(ctx.getOwningEntity(),UUIDComponent.getComponentType());
        if(owner!=null)state.source=owner.getUuid().toString();
        access.putComponent(target,MachineGunPoison.TYPE,state);
        effect(target,ctx.getOwningEntity(),"Ember_MachineGun_Poison_Status",access);
    }
    static void explode(InteractionContext ctx){
        var access=ctx.getCommandBuffer();var direct=ctx.getTargetEntity();
        // Only enemy impacts explode; vegetation and ground do not trigger splash.
        if(direct==null||!direct.isValid())return;
        var impact=ctx.getMetaStore().getIfPresentMetaObject(Interaction.HIT_LOCATION);
        var point=impact==null?EmberAdvancedArsenal.centre(direct,access):new Vector3d(impact.x,impact.y,impact.z);
        if(point==null||!point.isFinite())return;
        var used=new HashSet<Ref<EntityStore>>();used.add(direct);used.add(ctx.getOwningEntity());
        for(var target:new ArrayList<>(TargetUtil.getAllEntitiesInSphere(point,RADIUS+4,access))){
            if(!used.add(target)||!EmberAdvancedArsenal.living(target,access))continue;
            var nearest=EmberAdvancedArsenal.closestPoint(target,point,access);
            if(nearest!=null&&nearest.distanceSquared(point)<=RADIUS*RADIUS&&ProjectileFoliage.clear(point,nearest,access))
                EmberAdvancedArsenal.hit(ctx.getOwningEntity(),target,SPLASH,access);
        }
        ParticleUtil.spawnParticleEffect("Explosion_Small",point,access);
    }
}

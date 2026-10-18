package org.darselx.endlessember;

import com.hypixel.hytale.codec.*;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.server.core.asset.type.model.config.*;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.knockback.KnockbackComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.*;
import com.hypixel.hytale.server.core.modules.entity.damage.*;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import org.joml.Vector3d;
import java.util.*;

/** Server-owned combat actions. Flying discs are visual props; damage uses the normal pipeline. */
public final class EmberAdvancedArsenal extends SimpleInstantInteraction {
    public static final BuilderCodec<EmberAdvancedArsenal> CODEC=BuilderCodec.builder(EmberAdvancedArsenal.class,EmberAdvancedArsenal::new,SimpleInstantInteraction.CODEC)
        .append(new KeyedCodec<>("Action",Codec.STRING),(o,v)->o.action=v,o->o.action).add().build();
    static ResourceType<EntityStore,Runtime> RUNTIME;
    static final double COIL_RADIUS=4;
    static final float COIL_DAMAGE=24;
    String action="Pull";
    static void register(EndlessEmberPlugin plugin){
        plugin.getCodecRegistry(com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction.CODEC)
            .register("EE_AdvancedWeapon",EmberAdvancedArsenal.class,CODEC);
        RUNTIME=plugin.getEntityStoreRegistry().registerResource(Runtime.class,Runtime::new);
        plugin.getEntityStoreRegistry().registerSystem(new Tick());
    }
    public EmberAdvancedArsenal(){}
    @Override public WaitForDataFrom getWaitForDataFrom(){return WaitForDataFrom.Server;}
    @Override public boolean needsRemoteSync(){return true;}
    @Override protected void firstRun(InteractionType type,InteractionContext ctx,CooldownHandler cooldown){
        switch(action){
            case "Pull"->pull(ctx);
            case "Chain", "CoilBurst"->coil(ctx);
            case "SlagBlast"->slag(ctx);
            case "Disc"->disc(ctx);
            default->EmberWeaponGate.fail(ctx,null);
        }
    }
    static boolean living(Ref<EntityStore> ref,ComponentAccessor<EntityStore> access){
        if(ref==null||!ref.isValid())return false;
        var stats=access.getComponent(ref,EntityStatMap.getComponentType());
        var health=stats==null?null:stats.get(DefaultEntityStatTypes.getHealth());
        return health!=null&&health.get()>0;
    }
    static Vector3d centre(Ref<EntityStore> ref,ComponentAccessor<EntityStore> access){
        var t=access.getComponent(ref,TransformComponent.getComponentType());
        if(t==null)return null;
        var p=new Vector3d(t.getPosition());var b=access.getComponent(ref,BoundingBox.getComponentType());
        return b==null?p.add(0,.8,0):p.add(0,(b.getBoundingBox().min.y+b.getBoundingBox().max.y)*.5,0);
    }
    static boolean clear(Vector3d a,Vector3d b,ComponentAccessor<EntityStore> access){
        double distance=a.distance(b);if(distance<.05)return true;
        var d=new Vector3d(b).sub(a).div(distance);
        return TargetUtil.getTargetLocation(access.getExternalData().getWorld(),id->id!=0,a.x,a.y,a.z,d.x,d.y,d.z,distance-.03)==null;
    }
    static Damage hit(Ref<EntityStore> owner,Ref<EntityStore> target,float amount,ComponentAccessor<EntityStore> access){
        var damage=new Damage(new Damage.EntitySource(owner),DamageCause.getAssetMap().getAsset("Projectile"),amount);
        DamageSystems.executeDamage(target,access,damage);return damage;
    }
    static boolean movable(BoundingBox box){
        if(box==null)return false;var b=box.getBoundingBox();
        return b.max.y-b.min.y<=2.4&&(b.max.x-b.min.x)*(b.max.z-b.min.z)<=1.8;
    }
    static void pull(InteractionContext ctx){
        var access=ctx.getCommandBuffer();var owner=ctx.getOwningEntity();var target=ctx.getTargetEntity();
        if(!living(target,access)||target.equals(owner)||!movable(access.getComponent(target,BoundingBox.getComponentType())))return;
        var a=centre(owner,access);var b=centre(target,access);if(a==null||b==null||a.distanceSquared(b)>625||!clear(b,a,access))return;
        var velocity=new Vector3d(a).sub(b);velocity.y=0;
        if(velocity.lengthSquared()<1)return;
        velocity.normalize(8).y=.8;
        var knockback=new KnockbackComponent();knockback.setVelocity(velocity);knockback.setVelocityType(ChangeVelocityType.Add);
        knockback.setVelocityConfig(new com.hypixel.hytale.server.core.modules.splitvelocity.VelocityConfig());
        // Zero duration is a single impulse; a positive duration repeats the force every tick.
        knockback.setDuration(0);
        access.putComponent(target,KnockbackComponent.getComponentType(),knockback);
    }
    static Vector3d closestPoint(Ref<EntityStore> target,Vector3d point,ComponentAccessor<EntityStore> access){
        var transform=access.getComponent(target,TransformComponent.getComponentType());if(transform==null)return null;
        var bounds=access.getComponent(target,BoundingBox.getComponentType());if(bounds==null)return centre(target,access);
        var p=transform.getPosition();var box=bounds.getBoundingBox();
        return new Vector3d(Math.clamp(point.x,p.x+box.min.x,p.x+box.max.x),
            Math.clamp(point.y,p.y+box.min.y,p.y+box.max.y),Math.clamp(point.z,p.z+box.min.z,p.z+box.max.z));
    }
    static void coil(InteractionContext ctx){
        var access=ctx.getCommandBuffer();var owner=ctx.getOwningEntity();var target=ctx.getTargetEntity();
        // Physics supplies the exact collision point before the projectile transform
        // necessarily catches up. Never centre the blast on its previous position.
        var impact=ctx.getMetaStore().getIfPresentMetaObject(com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction.HIT_LOCATION);
        var point=impact!=null&&Double.isFinite(impact.x)&&Double.isFinite(impact.y)&&Double.isFinite(impact.z)
            ?new Vector3d(impact.x,impact.y,impact.z)
            :living(target,access)?centre(target,access):centre(ctx.getEntity(),access);
        if(point==null)return;
        if(!living(target,access)){
            var from=centre(owner,access);
            if(from!=null&&point.distanceSquared(from)>.01)point.add(new Vector3d(from).sub(point).normalize(.08));
        }
        discharge(owner,point,access);
    }
    static void discharge(Ref<EntityStore> owner,Vector3d point,ComponentAccessor<EntityStore> access){
        var used=new HashSet<Ref<EntityStore>>();used.add(owner);
        // Include bodies intersecting the sphere even if their feet are outside it.
        for(var target:new ArrayList<>(TargetUtil.getAllEntitiesInSphere(point,COIL_RADIUS+4,access))){
            if(!used.add(target)||!living(target,access))continue;
            var nearest=closestPoint(target,point,access);
            if(nearest!=null&&nearest.distanceSquared(point)<=COIL_RADIUS*COIL_RADIUS&&clear(point,nearest,access))hit(owner,target,COIL_DAMAGE,access);
        }
        ParticleUtil.spawnParticleEffect("Ember_Coil_Arc",point,access);
        // Bounded bursts mark the actual impact area, not the full shooter-to-target path.
        for(int i=0;i<24;i++){
            double y=1-2*(i+.5)/24.0,radial=Math.sqrt(1-y*y),angle=i*2.399963229728653;
            var offset=new Vector3d(Math.cos(angle)*radial,y,Math.sin(angle)*radial).mul(i%3==0?1.7:3.5);
            var sample=new Vector3d(point).add(offset);
            if(clear(point,sample,access))ParticleUtil.spawnParticleEffect("Ember_Coil_Arc",sample,access);
        }
    }
    static void slag(InteractionContext ctx){
        var access=ctx.getCommandBuffer();var owner=ctx.getOwningEntity();
        var point=centre(ctx.getEntity(),access);if(point==null)return;
        ParticleUtil.spawnParticleEffect("Impact_Blade_01",point,access);
        for(var target:new ArrayList<>(TargetUtil.getAllEntitiesInSphere(point,1.8,access))){
            if(target.equals(owner)||!living(target,access))continue;
            var centre=centre(target,access);
            if(centre!=null&&clear(point,centre,access))hit(owner,target,24,access);
        }
    }
    static void wear(InteractionContext ctx,double amount){
        var held=ctx.getHeldItem();if(EmberWeaponGate.creative(ctx)||ItemStack.isEmpty(held)||ctx.getHeldItemContainer()==null)return;
        held=held.withDurability(Math.max(0,held.getDurability()-amount));ctx.getHeldItemContainer().setItemStackForSlot(ctx.getHeldItemSlot(),held);ctx.setHeldItem(held);
    }
    static void disc(InteractionContext ctx){
        var access=ctx.getCommandBuffer();var owner=ctx.getOwningEntity();var data=access.getResource(RUNTIME);
        if(data.discs.stream().anyMatch(d->d.owner.equals(owner))){EmberWeaponGate.fail(ctx,"DiscFlying");return;}
        var look=TargetUtil.getLook(owner,access);var start=new Vector3d(look.getPosition());var direction=look.getDirection();
        var asset=ModelAsset.getAssetMap().getAsset("Ember_Return_Chakram_Prop");if(asset==null){EmberWeaponGate.fail(ctx,null);return;}
        Holder<EntityStore> holder=EntityStore.REGISTRY.newHolder();holder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());
        holder.addComponent(PropComponent.getComponentType(),PropComponent.get());
        holder.addComponent(TransformComponent.getComponentType(),new TransformComponent(start,new Rotation3f()));
        holder.addComponent(ModelComponent.getComponentType(),new ModelComponent(Model.createStaticScaledModel(asset,1)));
        var ref=access.addEntity(holder,AddReason.SPAWN);data.discs.add(new Disc(owner,ref,start,direction));wear(ctx,.8);
    }
    static final class Disc {
        final Ref<EntityStore> owner,visual;final Vector3d position,velocity;
        final Set<Ref<EntityStore>> outward=new HashSet<>(),inward=new HashSet<>();
        float age,travel;boolean returning;
        Disc(Ref<EntityStore> owner,Ref<EntityStore> visual,Vector3d start,Vector3d direction){this.owner=owner;this.visual=visual;position=new Vector3d(start);velocity=new Vector3d(direction).mul(15);}
    }
    static final class Runtime implements Resource<EntityStore>{
        final List<Disc> discs=new ArrayList<>();boolean queued;
        @Override public Runtime clone(){return new Runtime();}
    }
    static final class Tick extends TickingSystem<EntityStore>{
        @Override public void tick(float dt,int index,Store<EntityStore> store){
            var data=store.getResource(RUNTIME);if(data.queued||data.discs.isEmpty())return;
            data.queued=true;store.getExternalData().getWorld().execute(()->{try{if(!store.isShutdown())advance(dt,store,data);}finally{data.queued=false;}});
        }
    }
    static double segmentDistanceSquared(Vector3d p,Vector3d a,Vector3d b){
        var d=new Vector3d(b).sub(a);double t=d.lengthSquared()<1e-9?0:Math.max(0,Math.min(1,new Vector3d(p).sub(a).dot(d)/d.lengthSquared()));
        return new Vector3d(a).fma(t,d).distanceSquared(p);
    }
    static void advance(float dt,Store<EntityStore> store,Runtime data){
        var it=data.discs.iterator();
        while(it.hasNext()){
            var d=it.next();d.age+=dt;var home=d.owner.isValid()?centre(d.owner,store):null;
            if(home==null||!d.visual.isValid()||d.age>5||d.position.distanceSquared(home)>1600){removeDisc(d,store);it.remove();continue;}
            // Substeps keep movement and hit detection stable during a slow server tick.
            int steps=Math.max(1,(int)Math.ceil(Math.min(dt,.5)/.025));
            boolean caught=false;
            for(int step=0;step<steps;step++){
                double delta=Math.min(dt,.5)/steps;
                if(!d.returning&&(d.travel>=12||d.age>=.85f))d.returning=true;
                if(d.returning){if(d.position.distanceSquared(home)<.6){caught=true;break;}d.velocity.set(home).sub(d.position).normalize(19);}
                var next=new Vector3d(d.position).fma(delta,d.velocity);
                // Returning discs take the clear path home without dealing damage through a wall.
                boolean visible=clear(d.position,next,store);
                if(!visible&&!d.returning){d.returning=true;continue;}
                if(visible){
                    var used=d.returning?d.inward:d.outward;
                    var middle=new Vector3d(d.position).lerp(next,.5);
                    for(var target:new ArrayList<>(TargetUtil.getAllEntitiesInSphere(middle,3,store))){
                        if(target.equals(d.owner)||used.contains(target)||!living(target,store))continue;
                        var p=centre(target,store);var box=store.getComponent(target,BoundingBox.getComponentType());
                        double radius=box==null?.6:Math.min(1.6,.4+box.getBoundingBox().getMaximumThickness()*.5);
                        if(p!=null&&segmentDistanceSquared(p,d.position,next)<=radius*radius&&clear(d.position,p,store)){
                            hit(d.owner,target,18,store);used.add(target);
                        }
                    }
                }
                d.travel+=d.position.distance(next);d.position.set(next);
            }
            if(caught){removeDisc(d,store);it.remove();continue;}
            var transform=store.getComponent(d.visual,TransformComponent.getComponentType());
            if(transform!=null){transform.setPosition(d.position);transform.setRotation(new Rotation3f(0,d.age*22,0));}
        }
    }
    static void removeDisc(Disc d,Store<EntityStore> store){if(d.visual.isValid())store.removeEntity(d.visual,RemoveReason.REMOVE);}
}

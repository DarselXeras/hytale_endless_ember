package org.darselx.endlessember;

import com.hypixel.hytale.codec.*;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.model.config.*;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.weather.config.Weather;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.*;
import com.hypixel.hytale.server.core.modules.entity.damage.*;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.hypixel.hytale.builtin.weather.resources.WeatherResource;
import com.hypixel.hytale.builtin.weather.components.WeatherTracker;
import org.joml.Vector3d;
import org.joml.Quaterniond;
import java.util.*;

/** Server-owned target locks, swept guided flight and projectile-attributed warheads. */
public final class EmberRocketLauncher extends SimpleInstantInteraction {
    public static final BuilderCodec<EmberRocketLauncher> CODEC=BuilderCodec.builder(EmberRocketLauncher.class,EmberRocketLauncher::new,SimpleInstantInteraction.CODEC)
        .append(new KeyedCodec<>("Action",Codec.STRING),(o,v)->o.action=v,o->o.action).add().build();
    static final String ID="Ember_Rocket_Launcher";
    static final String[] KINDS={"Homing","Poison","Napalm","Frost","Rainmaker","Nuclear"};
    static final double RADIUS=8,STRAIGHT=10,SPEED=18,LOCK_RANGE=96,MAX_LIFE=16;
    static final float DAMAGE=80;
    static ResourceType<EntityStore,Runtime> RUNTIME;
    String action="Primary";
    public EmberRocketLauncher(){}
    static void register(EndlessEmberPlugin plugin){
        plugin.getCodecRegistry(com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction.CODEC).register("EE_RocketLauncher",EmberRocketLauncher.class,CODEC);
        RUNTIME=plugin.getEntityStoreRegistry().registerResource(Runtime.class,Runtime::new);
        plugin.getEntityStoreRegistry().registerSystem(new Tick());
    }
    @Override public WaitForDataFrom getWaitForDataFrom(){return WaitForDataFrom.Server;}
    @Override public boolean needsRemoteSync(){return true;}
    static String ammo(int mode){return "Ember_Ammo_Rocket_"+KINDS[mode];}
    static void notice(InteractionContext ctx,String key){
        var p=ctx.getCommandBuffer().getComponent(ctx.getOwningEntity(),PlayerRef.getComponentType());
        if(p!=null)p.sendMessage(Message.translation("DarselXRockets.Messages."+key));
    }
    @Override protected void firstRun(InteractionType type,InteractionContext ctx,CooldownHandler cooldown){
        if(ItemStack.isEmpty(ctx.getHeldItem())||!ID.equals(ctx.getHeldItem().getItemId())||ctx.getHeldItemContainer()==null){EmberWeaponGate.fail(ctx,null);return;}
        var data=ctx.getCommandBuffer().getResource(RUNTIME);var owner=ctx.getOwningEntity();
        if("Clear".equals(action)){data.locks.remove(owner);notice(ctx,"Cleared");return;}
        primary(ctx,System.nanoTime());
    }
    record Lock(int mode,Ref<EntityStore> entity,Vector3d point,long expires){}
    static boolean usable(Lock lock,int mode,Ref<EntityStore> owner,ComponentAccessor<EntityStore> access,long now){
        if(lock==null||lock.mode!=mode||now>=lock.expires)return false;
        var p=lock.entity==null?lock.point:EmberAdvancedArsenal.living(lock.entity,access)?EmberAdvancedArsenal.centre(lock.entity,access):null;
        var t=access.getComponent(owner,TransformComponent.getComponentType());
        return p!=null&&t!=null&&p.distanceSquared(t.getPosition())<=128*128;
    }
    static void primary(InteractionContext ctx,long now){
        var access=ctx.getCommandBuffer();var data=access.getResource(RUNTIME);var owner=ctx.getOwningEntity();
        boolean creative=EmberWeaponGate.creative(ctx);var held=ctx.getHeldItem();int mode=EmberWeaponGate.selected(held,KINDS.length);
        if(!creative&&held.isBroken()){EmberWeaponGate.fail(ctx,null);return;}
        if(now<data.nextShot.getOrDefault(owner,0L)){EmberWeaponGate.fail(ctx,null);return;}
        var look=TargetUtil.getLook(owner,access);var eye=new Vector3d(look.getPosition());var direction=new Vector3d(look.getDirection());
        if(!direction.isFinite()||direction.lengthSquared()<1e-9){EmberWeaponGate.fail(ctx,null);return;}direction.normalize();
        Lock lock=data.locks.get(owner);
        if(mode!=4&&!usable(lock,mode,owner,access,now)){
            data.locks.remove(owner);
            var wall=terrain(eye,direction,LOCK_RANGE,access);
            if(mode==0){
                var target=firstLiving(eye,new Vector3d(eye).fma(LOCK_RANGE,direction),owner,access);
                if(target==null||(wall!=null&&target.distance>eye.distance(wall)+.1)){notice(ctx,"NoEnemy");return;}
                lock=new Lock(mode,target.ref,EmberAdvancedArsenal.centre(target.ref,access),now+60_000_000_000L);
            }else{
                if(wall==null){notice(ctx,"NoBlock");return;}
                lock=new Lock(mode,null,new Vector3d(wall).fma(-.06,direction),now+60_000_000_000L);
            }
            data.locks.put(owner,lock);notice(ctx,mode==0?"EnemyLocked":"BlockLocked");return;
        }
        var inventory=EmberWeaponGate.inventory(ctx);var round=new ItemStack(ammo(mode));
        if(!creative&&!inventory.canRemoveItemStack(round,true,true)){notice(ctx,"NoAmmo");EmberWeaponGate.fail(ctx,null);return;}
        if(mode==4)direction.set(0,1,0);
        var start=new Vector3d(eye).fma(.7,direction);
        if(!ProjectileFoliage.clear(eye,start,access)){EmberWeaponGate.fail(ctx,null);return;}
        var asset=ModelAsset.getAssetMap().getAsset(ammo(mode));if(asset==null){EmberWeaponGate.fail(ctx,null);return;}
        var holder=EntityStore.REGISTRY.newHolder();holder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());
        holder.addComponent(PropComponent.getComponentType(),PropComponent.get());
        holder.addComponent(TransformComponent.getComponentType(),new TransformComponent(start,rotation(direction)));
        holder.addComponent(ModelComponent.getComponentType(),new ModelComponent(Model.createStaticScaledModel(asset,.6f)));
        var visual=access.addEntity(holder,AddReason.SPAWN);if(visual==null){EmberWeaponGate.fail(ctx,null);return;}
        if(!creative&&!inventory.removeItemStack(round,true,true).succeeded()){access.removeEntity(visual,RemoveReason.REMOVE);EmberWeaponGate.fail(ctx,null);return;}
        data.flights.add(new Flight(owner,visual,start,direction,mode,lock,raining(owner,access)));
        sound("SFX_Gobliterator_Rocket_Barrage_Fire",start,access);
        if(mode==0)data.locks.put(owner,new Lock(mode,lock.entity,lock.point,Long.MAX_VALUE));else data.locks.remove(owner);
        data.nextShot.put(owner,now+2_000_000_000L);EmberAdvancedArsenal.wear(ctx,.5);notice(ctx,"Fired");
    }
    static Rotation3f rotation(Vector3d direction){return new Rotation3f().premul(new Quaterniond().rotationTo(new Vector3d(0,1,0),direction));}
    static Vector3d terrain(Vector3d a,Vector3d direction,double distance,ComponentAccessor<EntityStore> access){
        if(distance<.00001)return null;
        return TargetUtil.getTargetLocation(access.getExternalData().getWorld(),id->id!=0&&!ProjectileFoliage.transparent(BlockType.getAssetMap().getAsset(id)),a.x,a.y,a.z,direction.x,direction.y,direction.z,distance);
    }
    record Contact(Ref<EntityStore> ref,double distance){}
    // Ray versus the entire target body; bounded segment tests prevent tunnelling on long ticks.
    static double intersect(Vector3d a,Vector3d b,Vector3d min,Vector3d max){
        double low=0,high=1;
        for(int i=0;i<3;i++){
            double d=b.get(i)-a.get(i),v=a.get(i);
            if(Math.abs(d)<1e-9){if(v<min.get(i)||v>max.get(i))return -1;}
            else{double x=(min.get(i)-v)/d,y=(max.get(i)-v)/d;low=Math.max(low,Math.min(x,y));high=Math.min(high,Math.max(x,y));if(low>high)return -1;}
        }
        return low;
    }
    static Contact firstLiving(Vector3d a,Vector3d b,Ref<EntityStore> owner,ComponentAccessor<EntityStore> access){
        double distance=a.distance(b),best=Double.POSITIVE_INFINITY;Ref<EntityStore> result=null;
        for(var target:new ArrayList<>(TargetUtil.getAllEntitiesInSphere(new Vector3d(a).lerp(b,.5),distance*.5+4,access))){
            if(target.equals(owner)||!EmberAdvancedArsenal.living(target,access))continue;
            var t=access.getComponent(target,TransformComponent.getComponentType());if(t==null)continue;
            var bounds=access.getComponent(target,BoundingBox.getComponentType());
            var min=bounds==null?new Vector3d(-.4,0,-.4):new Vector3d(bounds.getBoundingBox().min);
            var max=bounds==null?new Vector3d(.4,1.8,.4):new Vector3d(bounds.getBoundingBox().max);
            double hit=intersect(a,b,min.add(t.getPosition()).sub(.12,.12,.12),max.add(t.getPosition()).add(.12,.12,.12));
            if(hit>=0&&hit*distance<best){best=hit*distance;result=target;}
        }
        return result==null?null:new Contact(result,best);
    }
    static final class Flight{
        final Ref<EntityStore> owner,visual;final int mode;final Lock lock;final boolean wet;
        final Vector3d position,direction;double travelled,age,trail;
        final List<Vector3d> whiteTrail=new ArrayList<>();double whiteRemainder;
        Flight(Ref<EntityStore> owner,Ref<EntityStore> visual,Vector3d start,Vector3d direction,int mode,Lock lock,boolean wet){this.owner=owner;this.visual=visual;this.position=new Vector3d(start);this.direction=new Vector3d(direction);this.mode=mode;this.lock=lock;this.wet=wet;}
    }
    static Vector3d destination(Flight f,ComponentAccessor<EntityStore> access){
        if(f.mode==4)return null;
        var p=f.lock.entity!=null&&EmberAdvancedArsenal.living(f.lock.entity,access)?EmberAdvancedArsenal.centre(f.lock.entity,access):new Vector3d(f.lock.point);
        if(f.mode==2)p.add(0,10,0);return p;
    }
    static void steer(Vector3d direction,Vector3d desired,double dt){
        if(desired.lengthSquared()<1e-10)return;desired.normalize();
        double angle=Math.acos(Math.clamp(direction.dot(desired),-1,1)),step=Math.min(1,dt*8/Math.max(angle,1e-8));
        if(angle>3.13){var axis=new Vector3d(direction).cross(0,1,0);if(axis.lengthSquared()<1e-8)axis.set(1,0,0);direction.rotateAxis(Math.min(angle,dt*8),axis.normalize().x,axis.y,axis.z);}
        else direction.lerp(desired,step).normalize();
    }
    static void recordTrail(Flight f,Vector3d from,Vector3d to){
        double distance=from.distance(to);if(distance<1e-9)return;
        final double spacing=.4;int count=(int)Math.floor((f.whiteRemainder+distance+1e-9)/spacing);
        for(int i=1;i<=count&&f.whiteTrail.size()<1024;i++){
            double part=Math.clamp((i*spacing-f.whiteRemainder)/distance,0,1);
            f.whiteTrail.add(new Vector3d(from).lerp(to,part).fma(-.22,f.direction));
        }
        f.whiteRemainder=Math.clamp(f.whiteRemainder+distance-count*spacing,0,spacing);
    }
    static boolean move(Flight f,double dt,ComponentAccessor<EntityStore> access){
        // Substeps both enforce exactly ten straight blocks and bound steering/collision work.
        for(double left=Math.min(dt,.5);left>1e-7;){
            // Snap the phase boundary before calculating a step: a rounded distance
            // just below ten must never produce a zero-length collision cast.
            if(f.travelled<STRAIGHT&&STRAIGHT-f.travelled<1e-6)f.travelled=STRAIGHT;
            double step=Math.min(left,1.0/60);if(f.travelled<STRAIGHT)step=Math.min(step,(STRAIGHT-f.travelled)/SPEED);
            var target=destination(f,access);
            if(f.travelled>=STRAIGHT-1e-6&&target!=null)steer(f.direction,new Vector3d(target).sub(f.position),step);
            var next=new Vector3d(f.position).fma(SPEED*step,f.direction);var wall=terrain(f.position,f.direction,SPEED*step,access);
            // Special payloads navigate to blocks; creatures never intercept them.
            var body=f.mode==0?firstLiving(f.position,next,f.owner,access):null;
            if(body!=null&&(wall==null||body.distance<f.position.distance(wall))){var hit=new Vector3d(f.position).fma(body.distance,f.direction);recordTrail(f,f.position,hit);f.position.set(hit);return true;}
            if(wall!=null){recordTrail(f,f.position,wall);f.position.set(wall);return true;}
            if(f.travelled>=STRAIGHT-1e-6&&target!=null&&EmberAdvancedArsenal.segmentDistanceSquared(target,f.position,next)<.7*.7){recordTrail(f,f.position,target);f.position.set(target);return true;}
            recordTrail(f,f.position,next);
            f.position.set(next);f.travelled+=SPEED*step;f.age+=step;left-=step;
            if(f.position.y>=com.hypixel.hytale.math.util.ChunkUtil.HEIGHT-.1||f.position.y<0||f.age>=MAX_LIFE||(f.mode==4&&f.travelled>=40))return true;
        }
        return false;
    }
    static final class Zone{
        final Flight rocket;final Vector3d point;double age,nextTick=1,visualTick;
        Zone(Flight f){rocket=f;point=new Vector3d(f.position);if(f.mode==2)point.sub(0,10,0);}
        double duration(){return rocket.mode==1?30:3;}
    }
    static final class Burn{
        final Ref<EntityStore> target;final Flight rocket;double age,nextTick=1,remaining=10;
        Burn(Ref<EntityStore> target,Flight rocket){this.target=target;this.rocket=rocket;}
    }
    static final class Runtime implements Resource<EntityStore>{
        final Map<Ref<EntityStore>,Lock> locks=new HashMap<>();final Map<Ref<EntityStore>,Long> nextShot=new HashMap<>();
        final List<Flight> flights=new ArrayList<>();final List<Zone> zones=new ArrayList<>();final List<Burn> burns=new ArrayList<>();
        final List<NuclearVegetation.Job> vegetationJobs=new ArrayList<>();
        final List<EmberNuclearRocket.Flash> nuclearFlashes=new ArrayList<>();
        final Map<Ref<EntityStore>,EmberNuclearRocket.Preview> nuclearPreviews=new HashMap<>();
        final Set<Ref<EntityStore>> sources=new HashSet<>();boolean queued;double weatherLeft,lockVisual,lightning;String previousWeather,ourWeather;
        @Override public Runtime clone(){return new Runtime();}
    }
    static List<Ref<EntityStore>> targets(Vector3d point,ComponentAccessor<EntityStore> access){
        var result=new ArrayList<Ref<EntityStore>>();
        for(var target:new ArrayList<>(TargetUtil.getAllEntitiesInSphere(point,RADIUS+4,access))){
            if(!EmberAdvancedArsenal.living(target,access))continue;var nearest=EmberAdvancedArsenal.closestPoint(target,point,access);
            if(nearest!=null&&nearest.distanceSquared(point)<=RADIUS*RADIUS&&ProjectileFoliage.clear(new Vector3d(point).add(0,.12,0),nearest,access))result.add(target);
        }
        return result;
    }
    static Damage damage(Flight rocket,Ref<EntityStore> target,String cause,float amount,ComponentAccessor<EntityStore> access){
        var hit=new Damage(new Damage.ProjectileSource(rocket.owner,rocket.visual),DamageCause.getAssetMap().getAsset(cause),amount);
        if(rocket.mode==0&&cause.equals("Projectile"))hit.putMetaObject(EmberguardArmor.BLAST,true);
        DamageSystems.executeDamage(target,access,hit);return hit;
    }
    static void burst(Flight f,ComponentAccessor<EntityStore> access,Runtime data){
        if(f.visual.isValid())access.removeComponent(f.visual,ModelComponent.getComponentType());data.sources.add(f.visual);
        sound("SFX_Gobliterator_Rocket_Barrage_Explosion",f.position,access);
        if(f.mode==4){weather(f.wet,access,data);particles("Ember_Rocket_Weather",f.position,access);return;}
        if(f.mode==5){EmberNuclearRocket.burst(f,access,data);return;}
        particles(f.mode==0?"Ember_Rocket_Blast":f.mode==3?"Ember_Rocket_Frost":"Ember_Rocket_Impact",f.position,access);
        if(f.mode==0)for(var target:targets(f.position,access))damage(f,target,"Projectile",DAMAGE,access);
        else if(f.mode==3){
            for(var target:targets(f.position,access))EmberMachineGun.effect(target,f.owner,"Ember_Rocket_Freeze",access);
            groundEffect("Ember_Rocket_FrostGround",f.position,access);
        }else{
            var zone=new Zone(f);data.zones.add(zone);
            if(f.mode==2)groundEffect("Ember_Rocket_NapalmGround",zone.point,access);
        }
    }
    static List<Vector3d> groundPoints(Vector3d centre,ComponentAccessor<EntityStore> access){
        var points=new ArrayList<Vector3d>();
        // Small overlapping patches follow actual terrain instead of one floating plane.
        for(int x=-6;x<=6;x+=2)for(int z=-6;z<=6;z+=2){
            if(x*x+z*z>6.8*6.8)continue;
            var hit=terrain(new Vector3d(centre).add(x,3,z),new Vector3d(0,-1,0),10,access);
            if(hit!=null)points.add(new Vector3d(hit).add(0,.035,0));
        }
        return points;
    }
    static void groundEffect(String id,Vector3d centre,ComponentAccessor<EntityStore> access){for(var point:groundPoints(centre,access))particles(id,point,access);}
    static void zone(Zone zone,double dt,ComponentAccessor<EntityStore> access,Runtime data){
        zone.age=Math.min(zone.duration(),zone.age+dt);zone.visualTick-=dt;
        if(zone.visualTick<=0){
            if(zone.rocket.mode==1){
                var ground=terrain(new Vector3d(zone.point).add(0,2,0),new Vector3d(0,-1,0),10,access);
                particles("Ember_Rocket_PoisonCloud",ground==null?zone.point:new Vector3d(ground).add(0,.08,0),access);
                // The finite 30-second particle system maintains its own dense cloud.
                zone.visualTick=Double.POSITIVE_INFINITY;
            }else{particles("Ember_Rocket_FireRain",zone.rocket.position,access);zone.visualTick=.75;}
        }
        while(zone.nextTick<=zone.age+1e-6){
            for(var target:targets(zone.point,access)){
                if(zone.rocket.mode==1)damage(zone.rocket,target,"Poison",5,access);
                else{
                    var existing=data.burns.stream().filter(b->b.target.equals(target)).findFirst().orElse(null);
                    if(existing==null)data.burns.add(new Burn(target,zone.rocket));else existing.remaining=10;
                    EmberMachineGun.effect(target,zone.rocket.owner,"Ember_Rocket_Burning",access);
                }
            }
            zone.nextTick++;
        }
    }
    static boolean rainy(String id){return id!=null&&id.toLowerCase(Locale.ROOT).contains("rain");}
    static boolean rainy(Weather asset){return asset!=null&&(rainy(asset.getId())||(asset.getParticle()!=null&&rainy(asset.getParticle().systemId)));}
    static boolean raining(Ref<EntityStore> owner,ComponentAccessor<EntityStore> access){
        var weather=access.getResource(WeatherResource.getResourceType());var asset=Weather.getAssetMap().getAsset(weather.getForcedWeatherIndex());
        if(weather.getForcedWeatherIndex()!=0)return rainy(asset);
        var tracker=access.getComponent(owner,WeatherTracker.getComponentType());asset=tracker==null?null:Weather.getAssetMap().getAsset(tracker.getWeatherIndex());
        return rainy(asset);
    }
    static void weather(boolean wet,ComponentAccessor<EntityStore> access,Runtime data){
        var weather=access.getResource(WeatherResource.getResourceType());
        if(data.weatherLeft<=0||weather.getForcedWeatherIndex()!=Weather.getAssetMap().getIndex(data.ourWeather)){var previous=Weather.getAssetMap().getAsset(weather.getForcedWeatherIndex());data.previousWeather=weather.getForcedWeatherIndex()==0||previous==null?null:previous.getId();}
        data.ourWeather=wet?"Zone1_Storm":"Zone1_Rain";weather.setForcedWeather(data.ourWeather);data.weatherLeft=300;
    }
    static void weatherTick(double dt,ComponentAccessor<EntityStore> access,Runtime data){
        if(data.weatherLeft<=0)return;var weather=access.getResource(WeatherResource.getResourceType());
        if(weather.getForcedWeatherIndex()!=Weather.getAssetMap().getIndex(data.ourWeather)){data.weatherLeft=0;return;}
        data.weatherLeft=Math.max(0,data.weatherLeft-dt);if(data.weatherLeft==0)weather.setForcedWeather(data.previousWeather);
        if(data.weatherLeft>0&&"Zone1_Storm".equals(data.ourWeather)){
            data.lightning-=dt;if(data.lightning<=0){
                var random=java.util.concurrent.ThreadLocalRandom.current();
                for(var player:access.getExternalData().getWorld().getPlayerRefs()){
                    var ref=player.getReference();if(ref==null||!ref.isValid())continue;
                    var transform=access.getComponent(ref,TransformComponent.getComponentType());if(transform==null)continue;
                    var point=new Vector3d(transform.getPosition()).add(random.nextDouble(-25,25),18,random.nextDouble(-25,25));
                    particles("Ember_Rocket_Lightning",point,access);
                }
                data.lightning=random.nextDouble(6,12);
            }
        }
    }
    static void sound(String id,Vector3d point,ComponentAccessor<EntityStore> access){int index=SoundEvent.getAssetMap().getIndex(id);if(index>0)SoundUtil.playSoundEvent3d(index,SoundCategory.SFX,point,access);}
    static void particles(String id,Vector3d point,ComponentAccessor<EntityStore> access){ParticleUtil.spawnParticleEffect(id,point,access);}
    static void advance(double dt,ComponentAccessor<EntityStore> access,Runtime data,long now){
        data.locks.entrySet().removeIf(e->!e.getKey().isValid()||!usable(e.getValue(),e.getValue().mode,e.getKey(),access,now));
        data.nuclearPreviews.entrySet().removeIf(e->data.locks.get(e.getKey())!=e.getValue().lock());
        data.nextShot.entrySet().removeIf(e->!e.getKey().isValid()||now>=e.getValue());
        data.lockVisual-=dt;if(data.lockVisual<=0){
            for(var entry:data.locks.entrySet()){
                var lock=entry.getValue();
                particles(lock.entity==null?"Ember_Rocket_BlockLock":"Ember_Rocket_Lock",lock.entity==null?new Vector3d(lock.point).add(0,.14,0):EmberAdvancedArsenal.centre(lock.entity,access),access);
                if(lock.entity!=null)EmberMachineGun.effect(lock.entity,entry.getKey(),"Ember_Rocket_TargetPulse",access);
                if(lock.mode==5)EmberNuclearRocket.preview(entry.getKey(),lock,access,data);
            }
            data.lockVisual=.5;
        }
        for(var it=data.flights.iterator();it.hasNext();){var f=it.next();
            if(!f.visual.isValid()||!f.owner.isValid()){if(f.visual.isValid())access.removeEntity(f.visual,EntityStore.REGISTRY.newHolder(),RemoveReason.REMOVE);it.remove();continue;}
            boolean impact=move(f,dt,access);f.trail-=dt;
            for(var point:f.whiteTrail)particles("Ember_Rocket_WhiteTrail",point,access);f.whiteTrail.clear();
            if(f.trail<=0){particles("Ember_Rocket_Trail",f.position,access);f.trail=.08;}
            if(impact){burst(f,access,data);it.remove();}else{
                // TransformComponent owns the native section reference. Replacing it
                // leaves stale entity references in chunks and crashes chunk saves.
                var transform=access.getComponent(f.visual,TransformComponent.getComponentType());
                if(transform!=null){transform.setPosition(f.position);transform.setRotation(rotation(f.direction));}
            }
        }
        for(var it=data.zones.iterator();it.hasNext();){var z=it.next();zone(z,dt,access,data);if(z.age>=z.duration())it.remove();}
        for(var it=data.burns.iterator();it.hasNext();){var b=it.next();double elapsed=Math.min(dt,b.remaining);b.age+=elapsed;b.remaining-=elapsed;
            if(!EmberAdvancedArsenal.living(b.target,access)){it.remove();continue;}
            while(b.nextTick<=b.age+1e-6){damage(b.rocket,b.target,"Fire",6,access);b.nextTick++;}
            if(b.remaining<=0)it.remove();
        }
        EmberNuclearRocket.advance(dt,access,data);
        NuclearVegetation.advance(access,data);
        for(var it=data.sources.iterator();it.hasNext();){var source=it.next();if(data.zones.stream().noneMatch(z->z.rocket.visual.equals(source))&&data.burns.stream().noneMatch(b->b.rocket.visual.equals(source))&&data.nuclearFlashes.stream().noneMatch(f->f.visual.equals(source))&&data.vegetationJobs.stream().noneMatch(j->j.rocket.visual.equals(source))){if(source.isValid())access.removeEntity(source,EntityStore.REGISTRY.newHolder(),RemoveReason.REMOVE);it.remove();}}
        weatherTick(dt,access,data);
    }
    static final class Tick extends TickingSystem<EntityStore>{
        @Override public void tick(float dt,int index,Store<EntityStore> store){
            var data=store.getResource(RUNTIME);if(data.queued||(data.flights.isEmpty()&&data.zones.isEmpty()&&data.burns.isEmpty()&&data.nuclearFlashes.isEmpty()&&data.nuclearPreviews.isEmpty()&&data.vegetationJobs.isEmpty()&&data.locks.isEmpty()&&data.nextShot.isEmpty()&&data.weatherLeft<=0))return;
            data.queued=true;store.getExternalData().getWorld().execute(()->{try{if(!store.isShutdown())advance(dt,store,data,System.nanoTime());}finally{data.queued=false;}});
        }
    }
}

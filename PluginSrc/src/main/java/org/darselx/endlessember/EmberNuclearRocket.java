package org.darselx.endlessember;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.protocol.ColorLight;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.*;
import com.hypixel.hytale.server.core.modules.entity.damage.*;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import org.joml.Vector3d;
import java.util.*;

/** Fictional area warhead: one native kill pass and finite world-space mushroom effects. */
final class EmberNuclearRocket {
    static final double RADIUS=30,HEIGHT=30;
    record Preview(EmberRocketLauncher.Lock lock,List<Vector3d> edge){}
    static Vector3d perimeter(Vector3d centre,int index){
        double angle=index*Math.PI*2/96;
        double x=Math.cos(angle),z=Math.sin(angle);
        if(Math.abs(x)<1e-12)x=0;if(Math.abs(z)<1e-12)z=0;
        return new Vector3d(centre).add(x*RADIUS,0,z*RADIUS);
    }
    static List<Vector3d> previewPoints(Vector3d centre,ComponentAccessor<EntityStore> access){
        var points=new ArrayList<Vector3d>(96);
        for(int i=0;i<96;i++){
            var point=perimeter(centre,i);
            var hit=EmberRocketLauncher.terrain(new Vector3d(point).add(0,30,0),new Vector3d(0,-1,0),64,access);
            if(hit!=null)points.add(new Vector3d(hit).add(0,.12,0));
        }
        return List.copyOf(points);
    }
    static void preview(Ref<EntityStore> owner,EmberRocketLauncher.Lock lock,ComponentAccessor<EntityStore> access,EmberRocketLauncher.Runtime data){
        var cached=data.nuclearPreviews.get(owner);
        if(cached==null||cached.lock()!=lock){
            cached=new Preview(lock,previewPoints(base(lock.point(),access),access));
            data.nuclearPreviews.put(owner,cached);
        }
        for(var point:cached.edge())EmberRocketLauncher.particles("Ember_Rocket_Nuclear_Boundary",point,access);
    }
    static boolean intersects(Vector3d base,Vector3d min,Vector3d max){
        if(max.y<base.y||min.y>base.y+HEIGHT)return false;
        double x=Math.clamp(base.x,min.x,max.x)-base.x,z=Math.clamp(base.z,min.z,max.z)-base.z;
        return x*x+z*z<=RADIUS*RADIUS;
    }
    static List<Ref<EntityStore>> targets(Vector3d base,ComponentAccessor<EntityStore> access){
        var result=new ArrayList<Ref<EntityStore>>();
        for(var target:new HashSet<>(TargetUtil.getAllEntitiesInSphere(new Vector3d(base).add(0,HEIGHT*.5,0),Math.hypot(RADIUS,HEIGHT*.5)+12,access))){
            if(!EmberAdvancedArsenal.living(target,access))continue;
            var t=access.getComponent(target,TransformComponent.getComponentType());if(t==null)continue;
            var box=access.getComponent(target,BoundingBox.getComponentType());var p=t.getPosition();
            var min=new Vector3d(p);var max=new Vector3d(p);
            if(box==null){min.add(-.4,0,-.4);max.add(.4,1.8,.4);}else{min.add(box.getBoundingBox().min);max.add(box.getBoundingBox().max);}
            if(intersects(base,min,max))result.add(target);
        }
        return result;
    }
    @SuppressWarnings("unchecked")
    static void eliminate(EmberRocketLauncher.Flight rocket,Ref<EntityStore> target,ComponentAccessor<EntityStore> access){
        if(access.getComponent(target,DeathComponent.getComponentType())!=null)return;
        var cause=new Damage(new Damage.ProjectileSource(rocket.owner,rocket.visual),DamageCause.getAssetMap().getAsset("Projectile"),Integer.MAX_VALUE);
        // Use the same native death entry point as /kill; armor cannot reduce this
        // warhead to a survivable ordinary hit. Drops and death processing stay native.
        if(access instanceof Store<?> store)DeathComponent.tryAddComponent((Store<EntityStore>)store,target,cause);
        else if(access instanceof CommandBuffer<?> buffer)DeathComponent.tryAddComponent((CommandBuffer<EntityStore>)buffer,target,cause);
        else throw new IllegalArgumentException("Unsupported nuclear death accessor");
    }
    static final class Flash {
        final Ref<EntityStore> visual;double age;
        Flash(Ref<EntityStore> visual){this.visual=visual;}
    }
    static ColorLight light(double age){double fade=Math.clamp(1-age/4,0,1);return new ColorLight((byte)Math.round(30*Math.sqrt(fade)),(byte)Math.round(15*fade),(byte)Math.round(13*fade),(byte)Math.round(10*fade));}
    static Vector3d base(Vector3d impact,ComponentAccessor<EntityStore> access){
        var hit=EmberRocketLauncher.terrain(new Vector3d(impact).add(0,.5,0),new Vector3d(0,-1,0),48,access);
        return hit==null?new Vector3d(impact):new Vector3d(hit);
    }
    static void burst(EmberRocketLauncher.Flight rocket,ComponentAccessor<EntityStore> access,EmberRocketLauncher.Runtime data){
        var point=base(rocket.position,access);
        for(var target:targets(point,access))eliminate(rocket,target,access);
        NuclearVegetation.enqueue(rocket,point,data,NuclearRocketConfig.active);
        EmberRocketLauncher.particles("Ember_Rocket_Nuclear",new Vector3d(point).add(0,.08,0),access);
        EmberRocketLauncher.sound("SFX_Gobliterator_Self_Destruct_Explosion",point,access);
        if(rocket.visual.isValid()){
            if(access.getComponent(rocket.visual,UUIDComponent.getComponentType())==null)access.putComponent(rocket.visual,UUIDComponent.getComponentType(),UUIDComponent.randomUUID());
            if(access.getComponent(rocket.visual,NetworkId.getComponentType())==null)access.putComponent(rocket.visual,NetworkId.getComponentType(),new NetworkId(access.getExternalData().takeNextNetworkId()));
            if(access.getComponent(rocket.visual,BoundingBox.getComponentType())==null)access.putComponent(rocket.visual,BoundingBox.getComponentType(),new BoundingBox(new com.hypixel.hytale.math.shape.Box(new Vector3d(-.05),new Vector3d(.05))));
            access.putComponent(rocket.visual,DynamicLight.getComponentType(),new DynamicLight(light(0)));data.nuclearFlashes.add(new Flash(rocket.visual));
        }
    }
    static void advance(double dt,ComponentAccessor<EntityStore> access,EmberRocketLauncher.Runtime data){
        for(var it=data.nuclearFlashes.iterator();it.hasNext();){var flash=it.next();flash.age+=dt;
            if(!flash.visual.isValid()){it.remove();continue;}
            if(flash.age>=4){access.removeComponent(flash.visual,DynamicLight.getComponentType());it.remove();continue;}
            var light=access.getComponent(flash.visual,DynamicLight.getComponentType());if(light!=null)light.setColorLight(light(flash.age));
        }
    }
}

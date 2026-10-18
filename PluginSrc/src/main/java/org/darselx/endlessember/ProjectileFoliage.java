package org.darselx.endlessember;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.ProjectileComponent;
import com.hypixel.hytale.server.core.modules.collision.BlockCollisionProvider;
import com.hypixel.hytale.server.core.modules.interaction.Interactions;
import com.hypixel.hytale.server.core.modules.physics.SimplePhysicsProvider;
import com.hypixel.hytale.server.core.modules.projectile.component.Projectile;
import com.hypixel.hytale.server.core.modules.projectile.config.StandardPhysicsProvider;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import java.lang.reflect.Field;
import java.util.Locale;
import org.joml.Vector3d;

/** Per-projectile foliage filtering. Entity contacts and native impacts remain intact. */
final class ProjectileFoliage extends RefSystem<EntityStore> {
    // Both native providers expose their collision provider for reading but have
    // no setter. Replace only this per-instance block filter before its first tick;
    // keep all velocity, ownership, fluid and entity-collision state unchanged.
    static final Field LEGACY=field(SimplePhysicsProvider.class),MODERN=field(StandardPhysicsProvider.class);
    static Field field(Class<?> type){
        try{var f=type.getDeclaredField("blockCollisionProvider");f.setAccessible(true);return f;}
        catch(ReflectiveOperationException e){throw new IllegalStateException("Projectile collision API changed: "+type,e);}
    }
    static boolean transparent(BlockType block){
        if(block==null||block.isUnknown())return false;
        String id=block.getId().toLowerCase(Locale.ROOT);
        // Wood, leaves, saplings and trees are deliberately absent. Large bushes
        // need not be weapon-breakable: bullets pass without damaging them.
        return id.startsWith("plant_bush")||id.startsWith("plant_flower_bushy_")||id.startsWith("plant_grass_");
    }
    static boolean clear(Vector3d a,Vector3d b,ComponentAccessor<EntityStore> access){
        double distance=a.distance(b);if(distance<.05)return true;
        var d=new Vector3d(b).sub(a).div(distance);
        return TargetUtil.getTargetLocation(access.getExternalData().getWorld(),id->id!=0&&!transparent(BlockType.getAssetMap().getAsset(id)),
            a.x,a.y,a.z,d.x,d.y,d.z,distance-.03)==null;
    }
    static boolean enabled(String id){
        if(id==null)return false;
        int first=0;while(first<id.length()&&id.charAt(first)=='*')first++;
        String name=id.substring(first);
        return name.startsWith("Ember_")&&!name.contains("Signal")&&!name.contains("Flare");
    }
    static final class Filter extends BlockCollisionProvider {
        Filter(){setRequestedCollisionMaterials(6);setReportOverlaps(true);}
        @Override protected boolean canCollide(){return !transparent(blockData.getBlockType())&&super.canCollide();}
        // Fluid collision calls canCollide(int) directly and retains native behavior.
    }
    static void install(Object physics){
        Field f=physics instanceof StandardPhysicsProvider?MODERN:LEGACY;
        try{if(!(f.get(physics) instanceof Filter))f.set(physics,new Filter());}
        catch(IllegalAccessException e){throw new IllegalStateException("Cannot configure projectile foliage collision",e);}
    }
    @Override public Query<EntityStore> getQuery(){
        return Query.or(ProjectileComponent.getComponentType(),Query.and(Projectile.getComponentType(),StandardPhysicsProvider.getComponentType(),Interactions.getComponentType()));
    }
    @Override public void onEntityAdded(Ref<EntityStore> ref,AddReason reason,Store<EntityStore> store,CommandBuffer<EntityStore> buffer){
        var old=buffer.getComponent(ref,ProjectileComponent.getComponentType());
        if(old!=null){if(enabled(old.getProjectileAssetName()))install(old.getSimplePhysicsProvider());return;}
        var interactions=buffer.getComponent(ref,Interactions.getComponentType());
        if(interactions==null||!enabled(interactions.getInteractionId(InteractionType.ProjectileHit)))return;
        var physics=buffer.getComponent(ref,StandardPhysicsProvider.getComponentType());if(physics!=null)install(physics);
    }
    @Override public void onEntityRemove(Ref<EntityStore> ref,RemoveReason reason,Store<EntityStore> store,CommandBuffer<EntityStore> buffer){}
}

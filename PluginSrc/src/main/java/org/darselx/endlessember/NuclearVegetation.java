package org.darselx.endlessember;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import org.joml.Vector3i;
import java.util.*;

/** Incremental, loaded-section-only vegetation or spherical-cap terrain destruction. */
final class NuclearVegetation {
    static final int SCAN_BUDGET=4096,BREAK_BUDGET=32;
    static boolean vegetation(BlockType block){
        if(block==null||block.isUnknown())return false;
        String id=block.getId();while(id.startsWith("*"))id=id.substring(1);
        if(id.contains("_Stairs")||id.contains("_Half")||id.contains("_Planks")||id.contains("_Beam"))return false;
        if(id.startsWith("Plant_")||id.contains("_Plant_"))return true;
        if((id.startsWith("Wood_")||id.startsWith("Prototype_Wood_"))&&(id.contains("_Trunk")||id.contains("_Branch_")||id.contains("_Roots")))return true;
        var item=block.getItem();var data=item==null?null:item.getData();var tags=data==null?null:data.getRawTags();
        var types=tags==null?null:tags.get("Type");
        return types!=null&&Arrays.stream(types).anyMatch(t->t.equals("Plant")||t.equals("Tree"));
    }
    static boolean contains(Vector3d base,int x,int y,int z){
        double dx=x+.5-base.x,dz=z+.5-base.z;
        return y+.5>=base.y&&y+.5<base.y+EmberNuclearRocket.HEIGHT&&dx*dx+dz*dz<=EmberNuclearRocket.RADIUS*EmberNuclearRocket.RADIUS;
    }
    static void enqueue(EmberRocketLauncher.Flight rocket,Vector3d point,EmberRocketLauncher.Runtime data,NuclearRocketConfig.Settings settings){
        if(settings.mode()!=NuclearRocketConfig.Mode.NONE)data.vegetationJobs.add(new Job(rocket,point,settings));
    }
    static boolean crater(Vector3d base,int depth,int x,int y,int z){
        double dy=y+.5-base.y,dx=x+.5-base.x,dz=z+.5-base.z;
        if(dy>=0||dy<-depth)return false;
        // A sphere cap with a 30-block opening radius and configured central depth.
        double radius=(EmberNuclearRocket.RADIUS*EmberNuclearRocket.RADIUS+depth*depth)/(2.0*depth);
        double centre=radius-depth;
        return dx*dx+dz*dz+(dy-centre)*(dy-centre)<=radius*radius;
    }
    static final class Job {
        final EmberRocketLauncher.Flight rocket;final Vector3d base;final NuclearRocketConfig.Settings settings;
        final int minX,minZ,minY,width,depth,height,total;
        final Map<Integer,Boolean> types=new HashMap<>();int cursor,broken;
        Job(EmberRocketLauncher.Flight rocket,Vector3d point){
            this(rocket,point,NuclearRocketConfig.active);
        }
        Job(EmberRocketLauncher.Flight rocket,Vector3d point,NuclearRocketConfig.Settings settings){
            this.rocket=rocket;base=new Vector3d(point);this.settings=settings;
            minX=(int)Math.floor(point.x-EmberNuclearRocket.RADIUS);minZ=(int)Math.floor(point.z-EmberNuclearRocket.RADIUS);
            minY=Math.max(0,(int)Math.ceil(point.y-(settings.mode()==NuclearRocketConfig.Mode.CRATER?settings.craterDepth():0)-.5));
            width=(int)Math.floor(point.x+EmberNuclearRocket.RADIUS)-minX+1;depth=(int)Math.floor(point.z+EmberNuclearRocket.RADIUS)-minZ+1;
            height=Math.max(0,Math.min(com.hypixel.hytale.math.util.ChunkUtil.HEIGHT,(int)Math.ceil(point.y+EmberNuclearRocket.HEIGHT-.5))-minY);total=width*depth*height;
        }
        Vector3i next(){int index=cursor++,layer=index/(width*depth),column=index%(width*depth);return new Vector3i(minX+column%width,minY+height-1-layer,minZ+column/width);}
        boolean includes(Vector3i p){
            if(settings.mode()==NuclearRocketConfig.Mode.NONE)return false;
            return contains(base,p.x,p.y,p.z)||(settings.mode()==NuclearRocketConfig.Mode.CRATER&&crater(base,settings.craterDepth(),p.x,p.y,p.z));
        }
        boolean done(){return cursor>=total;}
    }
    static boolean harvest(Job job,Vector3i p,ComponentAccessor<EntityStore> access){
        if(!job.includes(p))return false;
        var world=access.getExternalData().getWorld();var chunks=world.getChunkStore();var blocks=chunks.getStore();
        var section=chunks.getChunkSectionReferenceAtBlock(p.x,p.y,p.z);if(section==null||!section.isValid())return false;
        var data=blocks.getComponent(section,BlockSection.getComponentType());if(data==null)return false;
        int id=data.get(p.x,p.y,p.z);if(id==0)return false;
        var block=BlockType.getAssetMap().getAsset(id);
        if(block==null||!job.types.computeIfAbsent(id,k->!block.isUnknown()&&(job.settings.mode()==NuclearRocketConfig.Mode.CRATER||vegetation(block)))||!world.getGameplayConfig().getWorldConfig().isBlockBreakingAllowed(block))return false;
        // Only the origin may harvest a multiblock plant, avoiding duplicate drops.
        if(data.getFiller(p.x,p.y,p.z)!=0)return false;
        var gathering=block.getGathering();int quantity=1;String item=null,list=null;
        if(gathering!=null){
            if(gathering.getBreaking()!=null){var drop=gathering.getBreaking();quantity=drop.getQuantity();item=drop.getItemId();list=drop.getDropListId();}
            else if(gathering.getSoft()!=null){var drop=gathering.getSoft();item=drop.getItemId();list=drop.getDropListId();}
            else if(gathering.getHarvest()!=null){var drop=gathering.getHarvest();item=drop.getItemId();list=drop.getDropListId();}
        }
        var owner=job.rocket.owner!=null&&job.rocket.owner.isValid()?job.rocket.owner:null;
        var impact=job.rocket.visual!=null&&job.rocket.visual.isValid()?job.rocket.visual:null;
        int flags=SetBlockSettings.NO_SEND_PARTICLES|SetBlockSettings.NO_SEND_AUDIO|SetBlockSettings.NO_FIRE_ON_BREAK;
        BlockHarvestUtils.performBlockBreak(new Vector3i(p),block,new ItemStack(EmberRocketLauncher.ID),quantity,item,list,flags,owner,impact,section,access,blocks);
        if(data.get(p.x,p.y,p.z)==0)job.broken++;
        return true; // Count cancelled native break attempts against the tick budget as well.
    }
    static void advance(ComponentAccessor<EntityStore> access,EmberRocketLauncher.Runtime data){
        int scanned=0,attempts=0;
        while(!data.vegetationJobs.isEmpty()&&scanned<SCAN_BUDGET&&attempts<BREAK_BUDGET){
            var job=data.vegetationJobs.getFirst();
            if(job.done()){data.vegetationJobs.removeFirst();continue;}
            var p=job.next();scanned++;
            if(harvest(job,p,access))attempts++;
        }
        data.vegetationJobs.removeIf(Job::done);
    }
}

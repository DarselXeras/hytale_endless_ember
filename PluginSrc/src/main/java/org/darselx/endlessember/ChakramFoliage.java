package org.darselx.endlessember;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import org.joml.Vector3d;
import org.joml.Vector3i;
import java.util.*;

/** A swept blade through sword-breakable vegetation, using native gathering events/drops. */
final class ChakramFoliage {
    static final double RADIUS=.4,HALF_HEIGHT=.1;
    record Contact(Vector3i block,double entry) {}
    static boolean cuttable(BlockType block){
        if(block==null||block.isUnknown()||block.getGathering()==null)return false;
        var soft=block.getGathering().getSoft();
        if(soft==null||!soft.isWeaponBreakable())return false;
        if(block.getId().startsWith("Plant_"))return true;
        var item=block.getItem();var categories=item==null?null:item.getCategories();
        return categories!=null&&Arrays.stream(categories).anyMatch(c->c.equals("Blocks.Plants")||c.equals("Blocks.Farming")||c.equals("Items.Plants"));
    }
    static double entry(Vector3d a,Vector3d b,int x,int y,int z){
        double near=0,far=1;
        for(int axis=0;axis<3;axis++){
            double start=a.get(axis),delta=b.get(axis)-start,pad=axis==1?HALF_HEIGHT:RADIUS;
            double min=(axis==0?x:axis==1?y:z)-pad,max=min+1+2*pad;
            if(Math.abs(delta)<1e-10){if(start<min||start>max)return Double.POSITIVE_INFINITY;continue;}
            double first=(min-start)/delta,last=(max-start)/delta;
            if(first>last){double swap=first;first=last;last=swap;}
            near=Math.max(near,first);far=Math.min(far,last);
            if(near>far)return Double.POSITIVE_INFINITY;
        }
        return near;
    }
    static boolean sweep(Ref<EntityStore> owner,Ref<EntityStore> visual,ItemStack blade,Vector3d a,Vector3d b,Store<EntityStore> store){
        var world=store.getExternalData().getWorld();var chunks=world.getChunkStore();var blocks=chunks.getStore();
        double distance=a.distance(b),limit=1;
        if(distance<1e-8)return true;
        var direction=new Vector3d(b).sub(a).div(distance);
        // Clip before collecting plants: nothing on the other side of a solid wall is cut.
        var obstacle=TargetUtil.getTargetLocation(world,id->id!=0&&!cuttable(BlockType.getAssetMap().getAsset(id)),
            a.x,a.y,a.z,direction.x,direction.y,direction.z,distance);
        if(obstacle!=null)limit=Math.max(0,(a.distance(obstacle)-.001)/distance);
        var contacts=new ArrayList<Contact>();
        for(int x=(int)Math.floor(Math.min(a.x,b.x)-RADIUS);x<=Math.floor(Math.max(a.x,b.x)+RADIUS);x++)
        for(int y=Math.max(0,(int)Math.floor(Math.min(a.y,b.y)-HALF_HEIGHT));y<=Math.floor(Math.max(a.y,b.y)+HALF_HEIGHT);y++)
        for(int z=(int)Math.floor(Math.min(a.z,b.z)-RADIUS);z<=Math.floor(Math.max(a.z,b.z)+RADIUS);z++){
            double entry=entry(a,b,x,y,z);if(!Double.isFinite(entry)||entry>limit)continue;
            var section=chunks.getChunkSectionReferenceAtBlock(x,y,z);if(section==null||!section.isValid())continue;
            var data=blocks.getComponent(section,BlockSection.getComponentType());if(data==null)continue;
            if(cuttable(BlockType.getAssetMap().getAsset(data.get(x,y,z))))contacts.add(new Contact(new Vector3i(x,y,z),entry));
        }
        contacts.sort(Comparator.comparingDouble(Contact::entry));
        for(var contact:contacts){
            var p=contact.block();var section=chunks.getChunkSectionReferenceAtBlock(p.x,p.y,p.z);
            if(section==null||!section.isValid())return false;
            var data=blocks.getComponent(section,BlockSection.getComponentType());if(data==null)return false;
            int id=data.get(p.x,p.y,p.z);if(id==0)continue;
            var plant=BlockType.getAssetMap().getAsset(id);
            if(!cuttable(plant))return false;
            if(!world.getGameplayConfig().getWorldConfig().isBlockBreakingAllowed(plant))return false;
            // The detached blade stack cannot overwrite or wear down a different
            // item if the player changes hotbar slot while the ring is in flight.
            BlockHarvestUtils.performBlockDamage(owner,visual,new Vector3i(p),blade,null,null,false,1f,0,false,section,store,blocks);
            if(data.get(p.x,p.y,p.z)!=0)return false; // Includes cancelled protection events.
        }
        return obstacle==null&&EmberAdvancedArsenal.clear(a,b,store);
    }
}

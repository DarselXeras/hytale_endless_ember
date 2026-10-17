package org.darselx.endlessember;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.entity.component.*;
import com.hypixel.hytale.server.core.universe.world.storage.*;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import org.joml.Vector3d;
import org.joml.Vector3i;
import java.util.*;

/** Read-only, nonpersistent props. The block inventory is the sole source of truth. */
final class CastingDisplays {
    final List<Ref<EntityStore>> entities=new ArrayList<>(Collections.nCopies(10,null));
    final String[] shown=new String[10];
    int rotation=-1;
    boolean queued;

    static String assetId(ItemStack cell){
        if(!EmberCell.isCell(cell)||EmberCell.units(cell)==0)return null;
        return "EE_CasterCell_"+EmberCell.metal(cell.getItemId())+"_Q"+EmberCell.units(cell);
    }
    // Dock centers include the station mesh's authored Z offset of -19 units.
    // Apply the complete local offset before rotating around the block center.
    static Vector3d offset(int slot){return new Vector3d((-38.7+slot*8.6)/32,45.5/32,-33.0/32);}
    static Vector3d position(Vector3i block,int slot,int rotation){
        Vector3d offset=offset(slot);RotationTuple.get(rotation).applyRotationTo(offset);
        return offset.add(block.x+.5,block.y,block.z+.5);
    }
    void request(Ref<ChunkStore> owner,CastingMachine state,BlockModule.BlockStateInfo info,ItemStack[] contents,int desiredRotation){
        if(queued)return;
        String[] desired=new String[10];boolean changed=rotation!=desiredRotation;
        for(int i=0;i<10;i++){
            desired[i]=assetId(contents[i]);Ref<EntityStore> ref=entities.get(i);
            changed|=!Objects.equals(shown[i],desired[i])||(desired[i]!=null&&(ref==null||!ref.isValid()));
        }
        if(!changed)return;
        Vector3i block=new Vector3i();if(!info.fillWorldPos(block))return;
        queued=true;
        var world=owner.getStore().getExternalData().getWorld();
        world.execute(()->{
            try{
                var store=world.getEntityStore().getStore();
                if(store.isShutdown())return;
                if(!owner.isValid()||owner.getStore().getComponent(owner,CastingMachine.TYPE)!=state){clear(store);return;}
                sync(store,block,desired,desiredRotation);
            }finally{queued=false;}
        });
    }
    void sync(Store<EntityStore> store,Vector3i block,String[] desired,int desiredRotation){
        Rotation3f facing=new Rotation3f();RotationTuple.get(desiredRotation).applyRotationTo(facing);
        for(int i=0;i<10;i++){
            Ref<EntityStore> ref=entities.get(i);String id=desired[i];
            if(id==null){
                if(ref!=null&&ref.isValid())store.removeEntity(ref,RemoveReason.REMOVE);
                entities.set(i,null);shown[i]=null;continue;
            }
            ModelAsset asset=ModelAsset.getAssetMap().getAsset(id);if(asset==null)continue;
            if(ref==null||!ref.isValid()){
                Holder<EntityStore> holder=EntityStore.REGISTRY.newHolder();
                holder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());
                holder.addComponent(PropComponent.getComponentType(),PropComponent.get());
                holder.addComponent(TransformComponent.getComponentType(),new TransformComponent(position(block,i,desiredRotation),facing));
                holder.addComponent(ModelComponent.getComponentType(),new ModelComponent(Model.createStaticScaledModel(asset,1)));
                ref=store.addEntity(holder,AddReason.SPAWN);entities.set(i,ref);
            }else{
                if(!Objects.equals(shown[i],id))store.replaceComponent(ref,ModelComponent.getComponentType(),new ModelComponent(Model.createStaticScaledModel(asset,1)));
                if(rotation!=desiredRotation){
                    var transform=store.getComponent(ref,TransformComponent.getComponentType());
                    transform.teleportPosition(position(block,i,desiredRotation));transform.teleportRotation(facing);
                }
            }
            shown[i]=id;
        }
        rotation=desiredRotation;
    }
    void clear(Store<EntityStore> store){
        for(int i=0;i<10;i++){
            Ref<EntityStore> ref=entities.get(i);
            if(ref!=null&&ref.isValid()&&!store.isShutdown())store.removeEntity(ref,RemoveReason.REMOVE);
            entities.set(i,null);shown[i]=null;
        }
        rotation=-1;
    }
}

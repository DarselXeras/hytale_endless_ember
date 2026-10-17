package org.darselx.endlessember;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.OpenCustomUIInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import org.joml.Vector3i;

final class EmberCasting {
    static void register(EndlessEmberPlugin plugin){
        CastingMachine.TYPE=plugin.getChunkStoreRegistry().registerComponent(CastingMachine.class,"EE_CastingMachine",CastingMachine.CODEC);
        OpenCustomUIInteraction.registerBlockEntityCustomPage(plugin,CastingPage.class,"EE_CastingStation",CastingPage::new);
        plugin.getChunkStoreRegistry().registerSystem(new Tick());
    }
    private static final class Tick extends EntityTickingSystem<ChunkStore>{
        @Override public Query<ChunkStore> getQuery(){return Query.and(CastingMachine.TYPE,ItemContainerBlock.getComponentType());}
        @Override public void tick(float dt,int index,ArchetypeChunk<ChunkStore> chunk,Store<ChunkStore> store,CommandBuffer<ChunkStore> buffer){
            CastingMachine state=chunk.getComponent(index,CastingMachine.TYPE);
            ItemContainerBlock container=chunk.getComponent(index,ItemContainerBlock.getComponentType());
            boolean update=state.elapsed+dt>=.25f;
            boolean wasRunning=state.running;
            state.tick(dt,container.getItemContainer());
            if(update){
                var info=chunk.getComponent(index,BlockModule.BlockStateInfo.getComponentType());
                if(info!=null){
                    if(state.running||wasRunning)info.markNeedsSaving(buffer);
                    boolean active=state.running&&state.working;
                    if(active!=state.lit){
                        Vector3i position=new Vector3i();
                        if(info.fillWorldPos(buffer,position)){
                            Ref<ChunkStore> machineRef=chunk.getReferenceTo(index);
                            store.getExternalData().getWorld().execute(()->{
                                if(machineRef.isValid()&&info.getSectionRef().isValid()&&store.getComponent(machineRef,CastingMachine.TYPE)==state)
                                    BlockOperations.setBlockInteractionState(store.getExternalData(),info.getSectionRef(),position.x,position.y,position.z,
                                    BlockType.getAssetMap().getAsset(CastingMachine.ID),active?"Working":"default",true);
                            });
                            state.lit=active;
                        }
                    }
                }
                for(CastingPage page:java.util.List.copyOf(state.viewers))page.update();
            }
        }
    }
}

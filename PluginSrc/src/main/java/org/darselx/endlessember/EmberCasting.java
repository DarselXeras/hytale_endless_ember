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
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import org.joml.Vector3i;

final class EmberCasting {
    /** Append storage without moving the original slots or replacing live views. */
    static boolean expandInventory(ItemContainerBlock block){
        var previous=block.getItemContainer();
        if(previous.getCapacity()>=CastingMachine.CAPACITY)return false;
        var expanded=new com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer((short)CastingMachine.CAPACITY);
        for(short slot=0;slot<previous.getCapacity();slot++)
            expanded.setItemStackForSlot(slot,previous.getItemStack(slot),false);
        ItemContainerBlock.CODEC.decode(new org.bson.BsonDocument("Capacity",new org.bson.BsonInt32(CastingMachine.CAPACITY)),block,com.hypixel.hytale.codec.EmptyExtraInfo.EMPTY);
        block.setItemContainer(expanded);
        return true;
    }
    static void register(EndlessEmberPlugin plugin){
        CastingMachine.TYPE=plugin.getChunkStoreRegistry().registerComponent(CastingMachine.class,"EE_CastingMachine",CastingMachine.CODEC);
        OpenCustomUIInteraction.registerCustomPageSupplier(plugin,CastingPage.class,"EE_CastingStation",
            (OpenCustomUIInteraction.CustomPageSupplier)(ref,access,player,context)->{
                var chunks=access.getExternalData().getWorld().getChunkStore();
                var target=context.getTargetBlock();if(target==null)target=context.getTargetBlockRaw();
                Ref<ChunkStore> station=resolveStation(chunks,target);
                return station==null?null:new CastingPage(player,station);
            });
        plugin.getChunkStoreRegistry().registerSystem(new Recover());
        plugin.getChunkStoreRegistry().registerSystem(new DisplayCleanup());
        plugin.getChunkStoreRegistry().registerSystem(new DisplayComponentCleanup());
        plugin.getChunkStoreRegistry().registerSystem(new Tick());
    }
    /** Resolve clicks on the complete multiblock and recover legacy component layouts before opening. */
    static Ref<ChunkStore> resolveStation(com.hypixel.hytale.server.core.universe.world.storage.ChunkStore chunks,com.hypixel.hytale.protocol.BlockPosition target){
        if(target==null)return null;
        var origin=BlockOperations.resolveBaseBlockPosition(chunks,target);if(origin==null)return null;
        var section=chunks.getChunkSectionReferenceAtBlock(origin.x,origin.y,origin.z);
        if(section==null||!section.isValid())return null;
        var store=chunks.getStore();var blocks=store.getComponent(section,BlockSection.getComponentType());if(blocks==null)return null;
        var type=BlockType.getAssetMap().getAsset(blocks.get(origin.x,origin.y,origin.z));
        if(type==null||!(CastingMachine.ID.equals(type.getId())||CastingMachine.ID.equals(type.getDefaultStateKey())))return null;
        var ref=BlockModule.getBlockEntity(chunks.getWorld(),origin.x,origin.y,origin.z);
        boolean changed=false;
        if(ref==null||!ref.isValid()){
            Holder<ChunkStore> holder=com.hypixel.hytale.server.core.universe.world.storage.ChunkStore.REGISTRY.newHolder();
            holder.putComponent(BlockModule.BlockStateInfo.getComponentType(),new BlockModule.BlockStateInfo(com.hypixel.hytale.math.util.ChunkUtil.indexBlock(origin.x,origin.y,origin.z),section));
            holder.putComponent(ItemContainerBlock.getComponentType(),newContainer());
            holder.putComponent(CastingMachine.TYPE,new CastingMachine());ref=store.addEntity(holder,AddReason.SPAWN);changed=true;
        }else{
            var container=store.getComponent(ref,ItemContainerBlock.getComponentType());
            if(container==null){store.putComponent(ref,ItemContainerBlock.getComponentType(),newContainer());changed=true;}
            else changed=expandInventory(container);
            if(store.getComponent(ref,CastingMachine.TYPE)==null){store.putComponent(ref,CastingMachine.TYPE,new CastingMachine());changed=true;}
        }
        if(changed){var info=store.getComponent(ref,BlockModule.BlockStateInfo.getComponentType());if(info!=null)info.markNeedsSaving(store);}
        return ref;
    }
    static ItemContainerBlock newContainer(){
        return ItemContainerBlock.CODEC.decode(new org.bson.BsonDocument("Capacity",new org.bson.BsonInt32(CastingMachine.CAPACITY)),com.hypixel.hytale.codec.EmptyExtraInfo.EMPTY);
    }
    private static final class DisplayCleanup extends com.hypixel.hytale.component.system.RefSystem<ChunkStore>{
        @Override public Query<ChunkStore> getQuery(){return CastingMachine.TYPE;}
        @Override public void onEntityAdded(Ref<ChunkStore> ref,AddReason reason,Store<ChunkStore> store,CommandBuffer<ChunkStore> buffer){}
        @Override public void onEntityRemove(Ref<ChunkStore> ref,RemoveReason reason,Store<ChunkStore> store,CommandBuffer<ChunkStore> buffer){
            CastingMachine state=store.getComponent(ref,CastingMachine.TYPE);
            var world=store.getExternalData().getWorld();
            world.execute(()->state.displays.clear(world.getEntityStore().getStore()));
        }
    }
    /** Component replacement/removal does not invoke the entity removal hook. */
    private static final class DisplayComponentCleanup extends com.hypixel.hytale.component.system.RefChangeSystem<ChunkStore,CastingMachine>{
        @Override public Query<ChunkStore> getQuery(){return ItemContainerBlock.getComponentType();}
        @Override public ComponentType<ChunkStore,CastingMachine> componentType(){return CastingMachine.TYPE;}
        @Override public void onComponentAdded(Ref<ChunkStore> ref,CastingMachine state,Store<ChunkStore> store,CommandBuffer<ChunkStore> buffer){}
        @Override public void onComponentSet(Ref<ChunkStore> ref,CastingMachine old,CastingMachine state,Store<ChunkStore> store,CommandBuffer<ChunkStore> buffer){
            if(old!=state)clear(old,store);
        }
        @Override public void onComponentRemoved(Ref<ChunkStore> ref,CastingMachine state,Store<ChunkStore> store,CommandBuffer<ChunkStore> buffer){clear(state,store);}
        private void clear(CastingMachine state,Store<ChunkStore> store){
            var world=store.getExternalData().getWorld();
            world.execute(()->state.displays.clear(world.getEntityStore().getStore()));
        }
    }
    /** Recover stations placed while only the unpacked assets were loaded. */
    private static final class Recover extends EntityTickingSystem<ChunkStore>{
        @Override public Query<ChunkStore> getQuery(){return Query.and(ItemContainerBlock.getComponentType(),BlockModule.BlockStateInfo.getComponentType());}
        @Override public void tick(float dt,int index,ArchetypeChunk<ChunkStore> chunk,Store<ChunkStore> store,CommandBuffer<ChunkStore> buffer){
            if(chunk.getComponent(index,CastingMachine.TYPE)!=null)return;
            var info=chunk.getComponent(index,BlockModule.BlockStateInfo.getComponentType());
            if(info==null||!info.getSectionRef().isValid())return;
            BlockSection blocks=buffer.getComponent(info.getSectionRef(),BlockSection.getComponentType());
            if(blocks==null)return;
            BlockType block=BlockType.getAssetMap().getAsset(blocks.get(info.getIndex()));
            var container=chunk.getComponent(index,ItemContainerBlock.getComponentType());
            if(block!=null&&(CastingMachine.ID.equals(block.getId())||CastingMachine.ID.equals(block.getDefaultStateKey()))){
                expandInventory(container);
                buffer.putComponent(chunk.getReferenceTo(index),CastingMachine.TYPE,new CastingMachine());
                info.markNeedsSaving(buffer);
            }
        }
    }
    private static final class Tick extends EntityTickingSystem<ChunkStore>{
        @Override public Query<ChunkStore> getQuery(){return Query.and(CastingMachine.TYPE,ItemContainerBlock.getComponentType());}
        @Override public void tick(float dt,int index,ArchetypeChunk<ChunkStore> chunk,Store<ChunkStore> store,CommandBuffer<ChunkStore> buffer){
            CastingMachine state=chunk.getComponent(index,CastingMachine.TYPE);
            ItemContainerBlock container=chunk.getComponent(index,ItemContainerBlock.getComponentType());
            boolean expanded=expandInventory(container);
            if(expanded){
                var info=chunk.getComponent(index,BlockModule.BlockStateInfo.getComponentType());
                if(info!=null)info.markNeedsSaving(buffer);
            }
            boolean update=state.elapsed+dt>=.25f;
            boolean wasRunning=state.running;
            state.tick(dt,container.getItemContainer());
            if(update){
                var info=chunk.getComponent(index,BlockModule.BlockStateInfo.getComponentType());
                if(info!=null){
                    if(info.getSectionRef().isValid()){
                        BlockSection blocks=buffer.getComponent(info.getSectionRef(),BlockSection.getComponentType());
                        if(blocks!=null)state.displays.request(chunk.getReferenceTo(index),state,info,CastingMachine.snapshot(container.getItemContainer()),blocks.getRotationIndex(info.getIndex()));
                    }
                    if(expanded||state.inventoryChanged||state.running||wasRunning||state.working)info.markNeedsSaving(buffer);
                    boolean active=state.working;
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

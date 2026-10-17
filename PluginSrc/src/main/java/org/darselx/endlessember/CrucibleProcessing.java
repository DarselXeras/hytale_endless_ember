package org.darselx.endlessember;

import com.hypixel.hytale.builtin.crafting.component.*;
import com.hypixel.hytale.builtin.crafting.system.BenchSystems;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.dependency.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.protocol.BenchRequirement;
import com.hypixel.hytale.protocol.BenchType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.bench.ProcessingBench;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.inventory.*;
import com.hypixel.hytale.server.core.inventory.container.*;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.storage.*;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import java.lang.reflect.*;
import java.util.*;
import org.joml.Vector3i;

/** Per-crucible controller; all inventories, fuel and progress stay in the native component. */
final class CrucibleProcessing implements Component<ChunkStore> {
    static final String ID="Ember_Crucible";
    static ComponentType<ChunkStore,CrucibleProcessing> TYPE;
    static final BuilderCodec<CrucibleProcessing> CODEC=BuilderCodec.builder(CrucibleProcessing.class,CrucibleProcessing::new).build();
    // Native processing exposes no recipe setter or per-bench tick override.
    // Temporarily hide ONLY this crucible's config during the core processing
    // system, then restore it before doing our batch. No native type is replaced.
    private static final Field RECIPE=field(ProcessingBenchBlock.class,"recipe");
    private static final Field RECIPE_ID=field(ProcessingBenchBlock.class,"recipeId");
    private static final Field CONFIG=field(ProcessingBenchBlock.class,"processingBench");
    private static final Field ASSET_ID=field(CraftingRecipe.class,"id");
    transient ProcessingBenchBlock nativeBench;
    private transient ProcessingBench maskedConfig;
    private transient String batchId;
    private transient float batchProgress;
    private transient boolean captured,lit;
    private transient ItemContainer filtered;
    private static Field field(Class<?> type,String name){
        try{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
        catch(ReflectiveOperationException e){throw new ExceptionInInitializerError(e);}
    }
    static CrucibleProcessing wrap(ProcessingBenchBlock original){
        CrucibleProcessing result=new CrucibleProcessing();result.capture(original);result.applyFilters();return result;
    }
    void capture(ProcessingBenchBlock original){
        nativeBench=original;batchProgress=original.getInputProgress();
        try{batchId=(String)RECIPE_ID.get(original);}catch(IllegalAccessException e){throw new IllegalStateException(e);}
        captured=true;
    }
    // The native component's codec persists all operational state. Never attach
    // listeners to snapshot clones; they share native container references.
    @Override public CrucibleProcessing clone(){return new CrucibleProcessing();}
    ItemContainer getInputContainer(){return nativeBench.getInputContainer();}
    ItemContainer getOutputContainer(){return nativeBench.getOutputContainer();}
    ItemContainer getFuelContainer(){return nativeBench.getFuelContainer();}
    float getInputProgress(){return nativeBench.getInputProgress();}
    float getFuelTime(){return nativeBench.getFuelTime();}
    boolean isActive(){return nativeBench.isActive();}
    void setInputProgress(float value){batchProgress=value;nativeBench.setInputProgress(value);}
    static String barMetal(ItemStack stack){
        if(!ItemStack.isEmpty(stack))for(String metal:EmberCell.METALS)
            if(stack.getItemId().equals("Ingredient_Bar_"+metal))return metal;
        return null;
    }
    void applyFilters(){
        ItemContainer input=getInputContainer();if(input==null)return;
        for(short slot=0;slot<input.getCapacity();slot++)input.setSlotFilter(FilterActionType.ADD,slot,
            (action,container,index,stack)->ItemStack.isEmpty(stack)||EmberCell.isCell(stack)||barMetal(stack)!=null);
        if(filtered!=input){
            input.registerChangeEvent(EventPriority.LAST,event->getRecipe());
            if(getOutputContainer()!=null)getOutputContainer().registerChangeEvent(EventPriority.LAST,event->getRecipe());
            filtered=input;
        }
    }
    private static MaterialQuantity material(String id,int amount){return new MaterialQuantity(id,null,null,amount,null);}
    private CraftingRecipe batch(){
        ItemContainer input=getInputContainer();if(input==null||input.getCapacity()!=2)return null;
        ItemStack first=input.getItemStack((short)0),second=input.getItemStack((short)1),cell,other;
        if(EmberCell.isCell(first)){cell=first;other=second;}
        else if(EmberCell.isCell(second)){cell=second;other=first;}
        else return null;
        int units=EmberCell.units(cell);String metal=EmberCell.metal(cell.getItemId());
        MaterialQuantity[] ingredients,outputs;String operation;int amount;
        if(!ItemStack.isEmpty(other)){
            String bars=barMetal(other);
            if(bars==null||units==100||(units>0&&!bars.equals(metal)))return null;
            metal=bars;amount=Math.min(100-units,other.getQuantity());operation="Fill";
            ingredients=new MaterialQuantity[]{material(cell.getItemId(),1),material(other.getItemId(),amount)};
            outputs=new MaterialQuantity[]{material(EmberCell.create(metal,units+amount).getItemId(),1)};
        }else{
            if(units<=0)return null;
            amount=units;operation="Cast";
            ingredients=new MaterialQuantity[]{material(cell.getItemId(),1)};
            outputs=new MaterialQuantity[]{material("Ingredient_Bar_"+metal,amount),material(EmberCell.EMPTY,1)};
        }
        CraftingRecipe original=CraftingRecipe.getAssetMap().getAsset("EmberCrucible_"+operation+"_"+metal);
        float fullTime=original==null?(operation.equals("Fill")?56:16):original.getTimeSeconds();
        BenchRequirement requirement=new BenchRequirement();requirement.type=BenchType.Processing;requirement.id=ID;
        CraftingRecipe recipe=new CraftingRecipe(ingredients,outputs[0],outputs,outputs[0].getQuantity(),
            new BenchRequirement[]{requirement},Math.max(.5f,fullTime*amount/100f),false,1);
        try{ASSET_ID.set(recipe,"EE_DynamicCrucible_"+operation+"_"+metal+"_"+units+"_"+amount);}
        catch(IllegalAccessException e){throw new IllegalStateException(e);}
        return recipe;
    }
    CraftingRecipe getRecipe(){
        CraftingRecipe current=batch();
        try{
            String id=current==null?null:current.getId();
            if(!Objects.equals(batchId,id))setInputProgress(0);
            batchId=id;nativeBench.setInputProgress(batchProgress);
            RECIPE_ID.set(nativeBench,id);RECIPE.set(nativeBench,current);
        }catch(IllegalAccessException e){throw new IllegalStateException(e);}
        return current;
    }
    int advanceProcessing(float dt,Store<EntityStore> entities,BenchBlock bench,BlockModule.BlockStateInfo info,
            int x,int y,int z,BlockType block,int rotation){
        CraftingRecipe recipe=getRecipe();
        if(recipe==null||!isActive()||!Float.isFinite(dt)||dt<=0)return 0;
        List<MaterialQuantity> materials=List.of(recipe.getInput());
        List<ItemStack> outputs=Arrays.stream(recipe.getOutputs()).map(MaterialQuantity::toItemStack).toList();
        ItemContainer input=getInputContainer(),output=getOutputContainer();
        if(!output.canAddItemStacks(outputs,false,false)||input.getSlotMaterialsToRemove(materials,true,true).isEmpty())return 0;
        float seconds=nativeBench.getRecipeTimeSeconds(bench.getTierLevel()),progress=getInputProgress();
        if(!Float.isFinite(progress)||progress<0||progress>seconds)progress=0;
        float available=Math.max(0,getFuelTime());
        for(short i=0;i<getFuelContainer().getCapacity();i++){
            ItemStack fuel=getFuelContainer().getItemStack(i);
            if(CastingMachine.isFuel(fuel))available+=fuel.getQuantity()*(float)fuel.getItem().getFuelQuality();
        }
        float step=Math.max(0,Math.min(dt,Math.min(seconds-progress,available)));
        if(step<=0)return 0;
        nativeBench.consumeFuelForDuration(step,entities,x,y,z,block,rotation);
        progress+=step;setInputProgress(progress);
        if(progress+.0001f<seconds)return 0;
        if(!input.removeMaterials(materials,true,true,true).succeeded()){setInputProgress(0);return 0;}
        output.addItemStacks(outputs,false,false,false);setInputProgress(0);getRecipe();return 1;
    }
    static void register(EndlessEmberPlugin plugin){
        TYPE=plugin.getChunkStoreRegistry().registerComponent(CrucibleProcessing.class,"EE_DynamicCrucible",CODEC);
        plugin.getChunkStoreRegistry().registerSystem(new Load());
        plugin.getChunkStoreRegistry().registerSystem(new Prepare());
        plugin.getChunkStoreRegistry().registerSystem(new Process());
    }
    static final class Load extends RefSystem<ChunkStore>{
        @Override public Query<ChunkStore> getQuery(){return Query.and(ProcessingBenchBlock.getComponentType(),BlockModule.BlockStateInfo.getComponentType());}
        @Override public Set<Dependency<ChunkStore>> getDependencies(){return Set.of(new SystemDependency<>(Order.BEFORE,BenchSystems.ProcessingBenchLifecycle.class));}
        @Override public void onEntityAdded(Ref<ChunkStore> ref,AddReason reason,Store<ChunkStore> store,CommandBuffer<ChunkStore> buffer){
            var info=buffer.getComponent(ref,BlockModule.BlockStateInfo.getComponentType());
            if(info==null||!info.getSectionRef().isValid())return;
            var blocks=buffer.getComponent(info.getSectionRef(),BlockSection.getComponentType());if(blocks==null)return;
            var block=BlockType.getAssetMap().getAsset(blocks.get(info.getIndex()));
            if(block==null||!(ID.equals(block.getId())||ID.equals(block.getDefaultStateKey())))return;
            var state=buffer.getComponent(ref,TYPE);
            if(state==null){state=new CrucibleProcessing();buffer.putComponent(ref,TYPE,state);}
            state.capture(buffer.getComponent(ref,ProcessingBenchBlock.getComponentType()));
        }
        @Override public void onEntityRemove(Ref<ChunkStore> ref,RemoveReason reason,Store<ChunkStore> store,CommandBuffer<ChunkStore> buffer){}
    }
    static final class Prepare extends EntityTickingSystem<ChunkStore>{
        @Override public Query<ChunkStore> getQuery(){return ProcessingBenchBlock.getComponentType();}
        @Override public Set<Dependency<ChunkStore>> getDependencies(){return Set.of(new SystemDependency<>(Order.BEFORE,BenchSystems.ProcessingBenchTick.class));}
        @Override public void tick(float dt,int index,ArchetypeChunk<ChunkStore> chunk,Store<ChunkStore> store,CommandBuffer<ChunkStore> buffer){
            var nativeBench=chunk.getComponent(index,ProcessingBenchBlock.getComponentType());
            if(nativeBench.getBench()==null||!ID.equals(nativeBench.getBench().getId()))return;
            var state=chunk.getComponent(index,TYPE);
            if(state==null){buffer.putComponent(chunk.getReferenceTo(index),TYPE,wrap(nativeBench));return;}
            if(!state.captured||state.nativeBench!=nativeBench)state.capture(nativeBench);
            state.applyFilters();state.getRecipe();state.maskedConfig=nativeBench.getProcessingBench();
            try{CONFIG.set(nativeBench,null);}catch(IllegalAccessException e){throw new IllegalStateException(e);}
        }
    }
    static final class Process extends EntityTickingSystem<ChunkStore>{
        @Override public Query<ChunkStore> getQuery(){return Query.and(TYPE,ProcessingBenchBlock.getComponentType(),BenchBlock.getComponentType(),BlockModule.BlockStateInfo.getComponentType());}
        @Override public Set<Dependency<ChunkStore>> getDependencies(){return Set.of(new SystemDependency<>(Order.AFTER,BenchSystems.ProcessingBenchTick.class),new SystemDependency<>(Order.AFTER,Prepare.class));}
        @Override public void tick(float dt,int index,ArchetypeChunk<ChunkStore> chunk,Store<ChunkStore> store,CommandBuffer<ChunkStore> buffer){
            var state=chunk.getComponent(index,TYPE);var nativeBench=chunk.getComponent(index,ProcessingBenchBlock.getComponentType());
            if(state.maskedConfig==null)return;
            try{CONFIG.set(nativeBench,state.maskedConfig);}catch(IllegalAccessException e){throw new IllegalStateException(e);}
            state.maskedConfig=null;
            var info=chunk.getComponent(index,BlockModule.BlockStateInfo.getComponentType());
            Vector3i pos=new Vector3i();if(!info.fillWorldPos(buffer,pos))return;
            var bench=chunk.getComponent(index,BenchBlock.getComponentType());var world=store.getExternalData().getWorld();
            var block=BlockType.getAssetMap().getAsset(ID);float before=state.getInputProgress(),fuel=state.getFuelTime();
            int completed=state.advanceProcessing(dt,world.getEntityStore().getStore(),bench,info,pos.x,pos.y,pos.z,block,0);
            boolean working=state.isActive()&&(completed>0||state.getInputProgress()>before||state.getFuelTime()<fuel);
            if(working)info.markNeedsSaving(buffer);
            var recipe=state.getRecipe();float seconds=recipe==null?0:nativeBench.getRecipeTimeSeconds(bench.getTierLevel());
            nativeBench.sendProgress(seconds>0?state.getInputProgress()/seconds:0,bench.getWindows());
            nativeBench.getProcessingSlots().clear();nativeBench.getProcessingFuelSlots().clear();
            if(working){for(short n=0;n<state.getInputContainer().getCapacity();n++)if(!ItemStack.isEmpty(state.getInputContainer().getItemStack(n)))nativeBench.getProcessingSlots().add(n);nativeBench.getProcessingFuelSlots().add((short)0);}
            nativeBench.sendProcessingSlots(bench.getWindows());nativeBench.sendProcessingFuelSlots(bench.getWindows());nativeBench.updateFuelValues(bench.getWindows());
            if(state.lit!=working){
                Ref<ChunkStore> ref=chunk.getReferenceTo(index);
                world.execute(()->{if(ref.isValid()&&info.getSectionRef().isValid()&&store.getComponent(ref,TYPE)==state)
                    BlockOperations.setBlockInteractionState(store.getExternalData(),info.getSectionRef(),pos.x,pos.y,pos.z,block,working?"Processing":"default",true);});
                state.lit=working;
            }
        }
    }
}

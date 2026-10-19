package org.darselx.endlessember;

import com.hypixel.hytale.builtin.crafting.CraftingPlugin;
import com.hypixel.hytale.builtin.crafting.component.*;
import com.hypixel.hytale.builtin.crafting.window.SimpleCraftingWindow;
import com.hypixel.hytale.codec.*;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.protocol.packets.interface_.*;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.bench.CraftingBench;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.inventory.*;
import com.hypixel.hytale.server.core.inventory.container.*;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.OpenCustomUIInteraction;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.builder.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.storage.*;
import org.joml.Vector3i;
import java.util.*;

/** A paginated view over the native crafting queue, not a second crafting implementation. */
public final class AmmoBenchPage extends InteractiveCustomUIPage<AmmoBenchPage.Data> {
    static final String ID="Ember_Ammunitionbench";
    static final int PAGE_SIZE=6,RECIPE_PAGE_SIZE=6;
    static ResourceType<EntityStore,Viewers> VIEWERS;
    final Ref<ChunkStore> block;
    final Vector3i position;
    final boolean german;
    final String language;
    Session session;
    volatile boolean active;
    boolean upgradeDetails;
    int page,category,recipePage,selected;
    String feedback="";
    List<CraftingRecipe> recipes=List.of();
    AmmoBenchPage(PlayerRef player,Ref<ChunkStore> block,Vector3i position){
        super(player,CustomPageLifetime.CanDismissOrCloseThroughInteraction,Data.CODEC);
        this.block=block;this.position=new Vector3i(position);language=player.getLanguage()==null?"en-US":player.getLanguage();german=language.startsWith("de");
    }
    static void register(EndlessEmberPlugin plugin){
        VIEWERS=plugin.getEntityStoreRegistry().registerResource(Viewers.class,Viewers::new);
        OpenCustomUIInteraction.registerCustomPageSupplier(plugin,AmmoBenchPage.class,"EE_AmmoBench",
            (OpenCustomUIInteraction.CustomPageSupplier)(ref,access,player,ctx)->{
                var target=ctx.getTargetBlock();if(target==null)target=ctx.getTargetBlockRaw();if(target==null)return null;
                var world=access.getExternalData().getWorld();var origin=BlockOperations.resolveBaseBlockPosition(world.getChunkStore(),target);if(origin==null)return null;
                var station=BlockModule.getBlockEntity(world,origin.x,origin.y,origin.z);if(station==null||!station.isValid())return null;
                var section=world.getChunkStore().getChunkSectionReferenceAtBlock(origin.x,origin.y,origin.z);if(section==null)return null;
                var blocks=world.getChunkStore().getStore().getComponent(section,com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection.getComponentType());
                var type=blocks==null?null:BlockType.getAssetMap().getAsset(blocks.get(origin.x,origin.y,origin.z));
                if(type==null||!(type.getBench() instanceof CraftingBench bench)||!ID.equals(bench.getId()))return null;
                return station.getStore().getComponent(station,BenchBlock.getComponentType())==null?null:new AmmoBenchPage(player,station,new Vector3i(origin.x,origin.y,origin.z));
            });
        plugin.getEntityStoreRegistry().registerSystem(new Tick());
    }
    static String weaponIcon(String category){return switch(category.substring((ID+"_").length())){
        case "Crossbow"->"Ember_Workshop_Crossbow";case "Pistol"->"Ember_Pistol";case "Signals"->"Ember_Signal_Pistol";
        case "Repeater"->"Ember_Repeater";case "MachineGun"->"Ember_Machine_Gun";case "Shotgun"->"Ember_Shotgun";
        case "Harpoon"->"Ember_Harpoon_Launcher";case "Slag"->"Ember_Slag_Launcher";case "Pollen"->"Ember_Pollen_Launcher";case "Rockets"->"Ember_Rocket_Launcher";
        case "Coil"->"Ember_Coil_Thrower";case "Induction"->"Ember_Induction_Rifle";case "Precision"->"Ember_Precision_Rifle";
        default->ID;};}
    String tr(String en,String de){return german?de:en;}
    String translated(String key){String value=I18nModule.get().getMessage(language,key);if(value==null)value=I18nModule.get().getMessage("en-US",key);return value==null?key:value;}
    String name(String id){return translated(new ItemStack(id).getItem().getTranslationKey());}
    CraftingBench.BenchCategory[] categories(){return ((CraftingBench)session.type.getBench()).getCategories();}
    static int pages(int count,int size){return Math.max(1,(count+size-1)/size);}
    static int clampPage(int value,int count,int size){return Math.clamp(value,0,pages(count,size)-1);}
    boolean valid(Ref<EntityStore> ref,ComponentAccessor<EntityStore> store){
        if(ref==null||!ref.isValid()||ref.getStore().getExternalData()!=store.getExternalData()||!block.isValid()||store.getExternalData().getWorld()!=block.getStore().getExternalData().getWorld())return false;
        var t=store.getComponent(ref,TransformComponent.getComponentType());var info=block.getStore().getComponent(block,BlockModule.BlockStateInfo.getComponentType());
        if(t==null||info==null||!info.getSectionRef().isValid()||t.getPosition().distanceSquared(position.x+.5,position.y+.5,position.z+.5)>100)return false;
        var blocks=block.getStore().getComponent(info.getSectionRef(),com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection.getComponentType());
        var type=blocks==null?null:BlockType.getAssetMap().getAsset(blocks.get(info.getIndex()));
        return type!=null&&type.getBench()!=null&&ID.equals(type.getBench().getId())&&block.getStore().getComponent(block,BenchBlock.getComponentType())!=null;
    }
    int tier(){return Math.max(1,block.getStore().getComponent(block,BenchBlock.getComponentType()).getTierLevel());}
    void chooseCategory(int index){
        category=Math.clamp(index,0,categories().length-1);page=category/PAGE_SIZE;selected=recipePage=0;upgradeDetails=false;
        var ids=CraftingPlugin.getAvailableRecipesForCategory(ID,categories()[category].getId());var list=new ArrayList<CraftingRecipe>();
        if(ids!=null)for(String id:ids){var recipe=CraftingRecipe.getAssetMap().getAsset(id);if(recipe!=null)list.add(recipe);}
        list.sort(Comparator.comparing(r->r.getPrimaryOutput().getItemId()));recipes=List.copyOf(list);
    }
    @Override public void build(Ref<EntityStore> ref,UICommandBuilder ui,UIEventBuilder events,Store<EntityStore> store){
        ui.append("Pages/EndlessEmber/AmmoBench.ui");
        if(!valid(ref,store))return;
        if(!active){
            var manager=store.getComponent(ref,CraftingManager.getComponentType());if(manager==null)return;
            if(manager.hasBenchSet())manager.clearBench(ref,store);
            var type=BlockType.getAssetMap().getAsset(ID);var state=block.getStore().getComponent(block,BenchBlock.getComponentType());
            session=new Session(position,type,state,this);var player=store.getComponent(ref,Player.getComponentType());
            session.init(playerRef,player.getWindowManager());
            if(!session.begin(ref,store))return;
            active=true;store.getResource(VIEWERS).pages.add(this);chooseCategory(0);
        }
        for(String action:new String[]{"Prev","Next","RecipePrev","RecipeNext","Craft1","Craft10","CraftAll","Cancel","Upgrade","StartUpgrade","Close"})
            events.addEventBinding(CustomUIEventBindingType.Activating,"#"+action,EventData.of("Action",action),false);
        for(int i=0;i<PAGE_SIZE;i++)events.addEventBinding(CustomUIEventBindingType.Activating,"#Category"+i,EventData.of("Action","Category"+i),false);
        for(int i=0;i<RECIPE_PAGE_SIZE;i++)events.addEventBinding(CustomUIEventBindingType.Activating,"#Recipe"+i,EventData.of("Action","Recipe"+i),false);
        refresh(ui,ref,store);
    }
    ItemContainer materials(Ref<EntityStore> ref,ComponentAccessor<EntityStore> access){return new CombinedItemContainer(InventoryComponent.getCombined(access,ref,InventoryComponent.BACKPACK_STORAGE_HOTBAR),session.getExtraResourcesSection().getItemContainer());}
    CraftingRecipe recipe(){return selected>=0&&selected<recipes.size()?recipes.get(selected):null;}
    static int requiredTier(CraftingRecipe recipe){return Arrays.stream(recipe.getBenchRequirement()).filter(r->ID.equals(r.id)).mapToInt(r->r.requiredTierLevel).min().orElse(99);}
    static int materialLimit(CraftingRecipe recipe,ItemContainer inventory){
        int limit=999;for(var input:recipe.getInput())if(input.getQuantity()>0)limit=Math.min(limit,inventory.countRemovableMaterial(input)/input.getQuantity());return Math.max(0,limit);
    }
    void refresh(UICommandBuilder ui,Ref<EntityStore> ref,ComponentAccessor<EntityStore> store){
        var manager=store.getComponent(ref,CraftingManager.getComponentType());var materials=materials(ref,store);var cats=categories();int level=tier();
        ui.set("#Title.Text",tr("EMBER AMMUNITION BENCH","GLUTMUNITIONSWERKBANK")+"  MK"+level);
        ui.set("#Page.Text",tr("Page ","Seite ")+(page+1)+" / "+pages(cats.length,PAGE_SIZE));
        ui.set("#Prev.Text",tr("< Previous","< Zurück"));ui.set("#Next.Text",tr("Next >","Weiter >"));ui.set("#Prev.Disabled",page==0);ui.set("#Next.Disabled",page+1>=pages(cats.length,PAGE_SIZE));
        for(int slot=0;slot<PAGE_SIZE;slot++){
            int index=page*PAGE_SIZE+slot;ui.set("#CategoryRow"+slot+".Visible",index<cats.length);if(index>=cats.length)continue;
            ui.set("#Category"+slot+".Text",(index==category?"> ":"")+translated(cats[index].getName()));
            ui.set("#CategoryIcon"+slot+".Slots",new ItemGridSlot[]{new ItemGridSlot(new ItemStack(weaponIcon(cats[index].getId())))});
        }
        ui.set("#Weapon.Text",translated(cats[category].getName()));
        for(int i=0;i<RECIPE_PAGE_SIZE;i++){
            int index=recipePage*RECIPE_PAGE_SIZE+i;ui.set("#RecipeRow"+i+".Visible",index<recipes.size());if(index>=recipes.size())continue;
            var r=recipes.get(index);String id=r.getPrimaryOutput().getItemId();int need=requiredTier(r);
            ui.set("#RecipeIcon"+i+".Slots",new ItemGridSlot[]{new ItemGridSlot(new ItemStack(id))});
            ui.set("#Recipe"+i+".Text",(selected==index&&!upgradeDetails?"> ":"")+name(id)+(need>level?" [MK"+need+"]":""));
        }
        ui.set("#RecipePage.Text",tr("Recipes ","Rezepte ")+(recipePage+1)+" / "+pages(recipes.size(),RECIPE_PAGE_SIZE));
        ui.set("#RecipePrev.Text","<");ui.set("#RecipeNext.Text",">");ui.set("#RecipePrev.Disabled",recipePage==0);ui.set("#RecipeNext.Disabled",recipePage+1>=pages(recipes.size(),RECIPE_PAGE_SIZE));
        var r=recipe();var upgrade=session.type.getBench().getUpgradeRequirement(level);
        if(upgrade==null)upgradeDetails=false;
        MaterialQuantity[] inputs=upgradeDetails?upgrade.getInput():r==null?new MaterialQuantity[0]:r.getInput();
        ui.set("#DetailsTitle.Text",upgradeDetails?tr("UPGRADE TO MK","AUFRÜSTEN AUF MK")+(level+1):r==null?"":name(r.getPrimaryOutput().getItemId()));
        String output=upgradeDetails?ID:r==null?ID:r.getPrimaryOutput().getItemId();
        ui.set("#Output.Slots",new ItemGridSlot[]{new ItemGridSlot(new ItemStack(output))});
        ui.set("#OutputInfo.Text",upgradeDetails?tr("Upgrade time: ","Aufrüstzeit: ")+upgrade.getTimeSeconds()+" s":r==null?"":tr("Per batch: ","Je Herstellung: ")+r.getPrimaryOutput().getQuantity()+" | MK"+requiredTier(r)+" | "+r.getTimeSeconds()+" s"+"\n"+tr("In inventory: ","Im Inventar: ")+EmberAmmo.count(InventoryComponent.getCombined(store,ref,InventoryComponent.HOTBAR_STORAGE_BACKPACK),r.getPrimaryOutput().getItemId()));
        for(int i=0;i<6;i++){
            ui.set("#InputRow"+i+".Visible",i<inputs.length);if(i>=inputs.length)continue;
            var input=inputs[i];String id=input.getItemId();String label;
            if(id==null){String resource=input.getResourceTypeId();id="Rock".equals(resource)?"Rock_Stone":"Rubble".equals(resource)?"Rubble_Stone":"Wood_Beech_Trunk";label="Rock".equals(resource)?tr("Any stone","Beliebiger Stein"):"Rubble".equals(resource)?tr("Any rubble","Beliebiges Geröll"):"Wood_Trunk".equals(resource)?tr("Any wood","Beliebiges Holz"):resource;}else label=name(id);
            ui.set("#InputIcon"+i+".Slots",new ItemGridSlot[]{new ItemGridSlot(new ItemStack(id))});
            ui.set("#Input"+i+".Text",label+"  "+materials.countRemovableMaterial(input)+" / "+input.getQuantity());
        }
        boolean busy=manager.getRemainingQueueSize()>0||session.upgrading;
        int remaining=manager.getRemainingQueueSize();float progress=Math.clamp(session.progress,0f,1f);
        ui.set("#CraftProgress.Value",busy?progress:0f);
        ui.set("#CraftProgressLabel.Text",busy?(session.upgrading?tr("Upgrade: ","Aufrüsten: "):tr("Current batch: ","Aktuelle Herstellung: "))+Math.round(progress*100)+"%":tr("Ready","Bereit"));
        boolean batch=remaining>0&&session.initialQueue>1&&!session.upgrading;
        ui.set("#QueueProgressGroup.Visible",batch);
        ui.set("#QueueProgress.Value",batch?Math.clamp((session.initialQueue-remaining+progress)/session.initialQueue,0f,1f):0f);
        ui.set("#QueueProgressLabel.Text",tr("Total order: ","Gesamter Auftrag: ")+Math.max(0,session.initialQueue-remaining)+" / "+session.initialQueue);
        boolean canCraft=!upgradeDetails&&r!=null&&requiredTier(r)<=level&&!busy&&(creative(ref,store)||materialLimit(r,materials)>0);
        for(String id:new String[]{"Craft1","Craft10","CraftAll"})ui.set("#"+id+".Disabled",!canCraft);
        ui.set("#Craft1.Text",tr("Craft x1","Herstellen x1"));ui.set("#Craft10.Text",tr("Craft x10","Herstellen x10"));ui.set("#CraftAll.Text",tr("Craft all","Alle herstellen"));
        ui.set("#Upgrade.Text",upgrade==null?"MK4 (Max)":tr("Upgrade MK","Aufrüsten MK")+(level+1));ui.set("#Upgrade.Disabled",upgrade==null||busy);
        ui.set("#StartUpgrade.Visible",upgradeDetails);ui.set("#StartUpgrade.Text",tr("Start upgrade","Aufrüstung starten"));ui.set("#StartUpgrade.Disabled",busy||(!creative(ref,store)&&!materials.canRemoveMaterials(Arrays.asList(inputs))));
        ui.set("#Cancel.Text",tr("Cancel","Abbrechen"));ui.set("#Cancel.Disabled",!busy);ui.set("#Close.Text",tr("Close","Schließen"));
        ui.set("#Status.Text",busy?(session.upgrading?tr("Upgrading: ","Aufrüsten: "):tr("Crafting: ","Herstellung: "))+Math.round(session.progress*100)+"% | "+tr("Queue: ","Warteschlange: ")+manager.getRemainingQueueSize():feedback.isEmpty()?tr("Choose a weapon and ammunition. Nearby storage is included.","Waffe und Munition wählen. Nahe Lager werden berücksichtigt."):feedback);
    }
    static boolean creative(Ref<EntityStore> ref,ComponentAccessor<EntityStore> access){var player=access.getComponent(ref,Player.getComponentType());return player!=null&&player.getGameMode()==com.hypixel.hytale.protocol.GameMode.Creative;}
    boolean craft(Ref<EntityStore> ref,ComponentAccessor<EntityStore> store,int requested){
        if(!active||!valid(ref,store)||upgradeDetails)return false;
        var r=recipe();var manager=store.getComponent(ref,CraftingManager.getComponentType());
        if(r==null||requiredTier(r)>tier()||manager.getRemainingQueueSize()>0||session.upgrading)return false;
        var inventory=materials(ref,store);int count=Math.min(requested,creative(ref,store)?requested:materialLimit(r,inventory));if(count<1)return false;
        boolean queued=manager.queueCraft(ref,store,session,0,r,count,inventory,CraftingManager.InputRemovalType.NORMAL);
        if(queued){session.initialQueue=manager.getRemainingQueueSize();session.progress=0;session.setBlockInteractionState(r.getTimeSeconds()>0?"CraftCompleted":"CraftCompletedInstant",store.getExternalData().getWorld());}
        return queued;
    }
    @Override public void handleDataEvent(Ref<EntityStore> ref,Store<EntityStore> store,Data data){
        if(!active||!valid(ref,store)||"Close".equals(data.action)){close();return;}
        String action=data.action==null?"":data.action;feedback="";
        if(action.equals("Prev")||action.equals("Next")){page=clampPage(page+(action.equals("Next")?1:-1),categories().length,PAGE_SIZE);chooseCategory(page*PAGE_SIZE);}
        else if(action.startsWith("Category")){try{int index=Integer.parseInt(action.substring(8));if(index>=0&&index<PAGE_SIZE&&page*PAGE_SIZE+index<categories().length)chooseCategory(page*PAGE_SIZE+index);}catch(NumberFormatException ignored){}}
        else if(action.equals("RecipePrev")||action.equals("RecipeNext")){recipePage=clampPage(recipePage+(action.equals("RecipeNext")?1:-1),recipes.size(),RECIPE_PAGE_SIZE);}
        else if(action.startsWith("Recipe")){try{int index=Integer.parseInt(action.substring(6));if(index>=0&&index<RECIPE_PAGE_SIZE&&recipePage*RECIPE_PAGE_SIZE+index<recipes.size()){selected=recipePage*RECIPE_PAGE_SIZE+index;upgradeDetails=false;}}catch(NumberFormatException ignored){}}
        else if(action.startsWith("Craft")){if(!craft(ref,store,action.equals("CraftAll")?999:action.equals("Craft10")?10:1))feedback=tr("Cannot craft: check materials, tier or current job.","Herstellung nicht möglich: Zutaten, MK-Stufe oder laufenden Auftrag prüfen.");}
        else if(action.equals("Upgrade"))upgradeDetails=!upgradeDetails;
        else if(action.equals("StartUpgrade")&&upgradeDetails){var manager=store.getComponent(ref,CraftingManager.getComponentType());if(manager.getRemainingQueueSize()==0&&!session.upgrading){session.upgrading=manager.startTierUpgrade(ref,store,session);if(session.upgrading)session.setBlockInteractionState("BenchUpgrading",store.getExternalData().getWorld());}}
        else if(action.equals("Cancel")){var manager=store.getComponent(ref,CraftingManager.getComponentType());manager.clearBench(ref,store);session.upgrading=false;session.progress=0;session.setBlockInteractionState(block.getStore().getComponent(block,BenchBlock.getComponentType()).getTierStateName(),store.getExternalData().getWorld());manager.setBench(position.x,position.y,position.z,session.type);}
        update(ref,store);
    }
    void update(Ref<EntityStore> ref,Store<EntityStore> store){
        if(!active)return;if(!valid(ref,store)){close();return;}UICommandBuilder ui=new UICommandBuilder();refresh(ui,ref,store);sendUpdate(ui,false);
    }
    @Override public void onDismiss(Ref<EntityStore> ref,Store<EntityStore> store){
        if(!active)return;active=false;store.getResource(VIEWERS).pages.remove(this);
        if(store.getExternalData().getWorld()==block.getStore().getExternalData().getWorld())session.onClose0(ref,store);
        else{var manager=store.getComponent(ref,CraftingManager.getComponentType());if(manager!=null&&manager.hasBenchSet())manager.clearBench(ref,store);}
    }
    /** Server-only native window adapter. It is never opened as a client window. */
    static int rotation(Ref<ChunkStore> block,Vector3i position){
        var info=block.getStore().getComponent(block,BlockModule.BlockStateInfo.getComponentType());
        var section=block.getStore().getComponent(info.getSectionRef(),com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection.getComponentType());
        return section.getRotationIndex(position.x,position.y,position.z);
    }
    static final class Session extends SimpleCraftingWindow {
        final BlockType type;final AmmoBenchPage page;
        float progress;boolean upgrading;int initialQueue;
        Session(Vector3i pos,BlockType type,BenchBlock block,AmmoBenchPage page){super(pos.x,pos.y,pos.z,rotation(page.block,pos),type,block);this.type=type;this.page=page;}
        boolean begin(Ref<EntityStore> ref,Store<EntityStore> store){return super.onOpen0(ref,store);}
        @Override public void updateQueueSize(int count){if(count==0){progress=0;initialQueue=0;}}
        @Override public void updateCraftingJob(float percent){progress=percent;}
        @Override public void updateBenchUpgradeJob(float percent){progress=percent;}
        @Override public void updateBenchTierLevel(int level){upgrading=false;progress=0;invalidateExtraResources();}
        @Override public void invalidateExtraResources(){getExtraResourcesSection().setValid(false);}
    }
    static final class Viewers implements Resource<EntityStore>{final Set<AmmoBenchPage> pages=new HashSet<>();float elapsed;@Override public Viewers clone(){return new Viewers();}}
    static final class Tick extends TickingSystem<EntityStore>{
        @Override public void tick(float dt,int index,Store<EntityStore> store){var viewers=store.getResource(VIEWERS);viewers.elapsed+=dt;if(viewers.elapsed<.2)return;viewers.elapsed=0;
            for(var page:new ArrayList<>(viewers.pages)){var ref=page.playerRef.getReference();
                if(!page.active||ref==null||!ref.isValid()){viewers.pages.remove(page);continue;}
                if(ref.getStore()!=store){viewers.pages.remove(page);ref.getStore().getExternalData().getWorld().execute(page::close);continue;}
                page.update(ref,store);}
        }
    }
    public static final class Data {public static final BuilderCodec<Data> CODEC=BuilderCodec.builder(Data.class,Data::new).append(new KeyedCodec<>("Action",Codec.STRING),(o,v)->o.action=v,o->o.action).add().build();String action;}
}

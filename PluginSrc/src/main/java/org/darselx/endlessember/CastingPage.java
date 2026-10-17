package org.darselx.endlessember;

import com.hypixel.hytale.codec.*;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.protocol.packets.interface_.*;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.inventory.*;
import com.hypixel.hytale.server.core.inventory.container.*;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.builder.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.*;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.Message;
import java.util.*;
import org.joml.Vector3i;
import org.bson.*;

/** Custom-page drop events backed by live, filtered views of the saved machine. */
public final class CastingPage extends InteractiveCustomUIPage<CastingPage.Data> {
    final Ref<ChunkStore> block;
    final boolean german;
    boolean reserve,backpack;
    volatile boolean active;
    int playerSection,reservePage;
    CastingMachine machine;
    final CastingInventoryView[] views=new CastingInventoryView[6];
    final CastingInventoryView[] reserveViews=new CastingInventoryView[CastingMachine.RESERVE_PAGES];
    // Page-local section identifiers describe drag sources; no native container
    // windows are opened behind the custom page.
    static final int SECTION_BASE=1001;
    static final int RESERVE_SECTION_BASE=SECTION_BASE+6;
    static int reserveSection(int page){CastingMachine.reserveFirst(page);return RESERVE_SECTION_BASE+page;}
    final Map<String,ItemStack[]> rendered=new HashMap<>();
    String feedback="";
    long feedbackUntil;
    static final String[] GRIDS={"Mounted","ReserveGrid","Inputs","Products","Empties","Fuel"};
    static final int[] FIRST={0,10,34,40,42,43},SIZE={10,24,6,2,1,1};
    CastingPage(PlayerRef player,Ref<ChunkStore> block){
        super(player,CustomPageLifetime.CanDismissOrCloseThroughInteraction,Data.CODEC);this.block=block;
        german=player.getLanguage()!=null&&player.getLanguage().startsWith("de");
    }
    String tr(String en,String de){return german?de:en;}
    @Override public void build(Ref<EntityStore> ref,UICommandBuilder ui,UIEventBuilder events,Store<EntityStore> store){
        if(active)onDismiss(ref,store);
        rendered.clear();playerSection=0;
        ui.append("Pages/EndlessEmber/CastingStation.ui");
        if(!valid(ref,store))return;
        machine=block.getStore().getComponent(block,CastingMachine.TYPE);machine.viewers.add(this);active=true;
        var component=block.getStore().getComponent(block,ItemContainerBlock.getComponentType());
        if(EmberCasting.expandInventory(component))markDirty();
        var inventory=component.getItemContainer();
        for(int i=0;i<GRIDS.length;i++){
            views[i]=new CastingInventoryView(inventory,FIRST[i],SIZE[i],this::accessible,slot->{
                if(slot<CastingMachine.OUTPUT||CastingMachine.isReserve(slot))machine.progress=0;
                markDirty();
            });
            ui.set("#"+GRIDS[i]+".InventorySectionId",SECTION_BASE+i);
        }
        for(int page=0;page<CastingMachine.RESERVE_PAGES;page++){
            reserveViews[page]=new CastingInventoryView(inventory,CastingMachine.reserveFirst(page),CastingMachine.PAGE_SIZE,this::accessible,slot->{machine.progress=0;markDirty();});
            String action="Reserve"+page;
            events.addEventBinding(CustomUIEventBindingType.Activating,"#"+action,EventData.of("Action",action),false);
        }
        ui.set("#Hotbar.InventorySectionId",-1);
        for(String grid:new String[]{"Mounted","ReserveGrid","Inputs","Products","Empties","Fuel","PlayerGrid","Hotbar"})
            events.addEventBinding(CustomUIEventBindingType.Dropped,"#"+grid,
                EventData.of("Action","Drop").append("Grid",grid),false);
        for(String action:new String[]{"Tab","Metal","Start","Stop","Cast","Fill","Drain","One","Ten","All","Prev","Next","Close"})
            events.addEventBinding(CustomUIEventBindingType.Activating,"#"+action,EventData.of("Action",action),false);
        refresh(ui,ref,store);
    }
    boolean accessible(){
        Ref<EntityStore> ref=playerRef.getReference();return active&&ref!=null&&ref.isValid()&&valid(ref,ref.getStore());
    }
    boolean valid(Ref<EntityStore> ref,Store<EntityStore> store){
        if(!ref.isValid()||!block.isValid()||store.getExternalData().getWorld()!=block.getStore().getExternalData().getWorld())return false;
        var info=block.getStore().getComponent(block,BlockModule.BlockStateInfo.getComponentType());
        var transform=store.getComponent(ref,TransformComponent.getComponentType());Vector3i position=new Vector3i();
        if(info==null||transform==null||!info.fillWorldPos(position)||transform.getPosition().distanceSquared(position.x+.5,position.y+.5,position.z+.5)>100)return false;
        return block.getStore().getComponent(block,CastingMachine.TYPE)!=null&&block.getStore().getComponent(block,ItemContainerBlock.getComponentType())!=null;
    }
    void update(){
        Ref<EntityStore> ref=playerRef.getReference();
        if(ref==null||!ref.isValid()||!accessible()){
            if(machine!=null)machine.viewers.remove(this);if(ref!=null&&ref.isValid())close();return;
        }
        UICommandBuilder ui=new UICommandBuilder();refresh(ui,ref,ref.getStore());sendUpdate(ui,false);
    }
    void refresh(UICommandBuilder ui,Ref<EntityStore> ref,Store<EntityStore> store){
        ItemContainer c=block.getStore().getComponent(block,ItemContainerBlock.getComponentType()).getItemContainer();
        ui.set("#ReserveGrid.InventorySectionId",reserveSection(reservePage));
        for(int i=0;i<GRIDS.length;i++)grid(ui,"#"+GRIDS[i],c,i==1?CastingMachine.reserveFirst(reservePage):FIRST[i],SIZE[i],true);
        for(int i=0;i<10;i++){
            ItemStack cell=c.getItemStack((short)i);
            ui.set("#H"+i+".Text",metalName(i)+"\n"+(EmberCell.isCell(cell)?EmberCell.units(cell):0)+"/100\n"+tr("Reserve: ","Vorrat: ")+CastingMachine.reserveCount(c,EmberCell.METALS[i]));
        }
        var storage=store.getComponent(ref,InventoryComponent.Storage.getComponentType());
        var hotbar=store.getComponent(ref,InventoryComponent.Hotbar.getComponentType());
        var pack=store.getComponent(ref,InventoryComponent.Backpack.getComponentType());
        boolean hasPack=pack!=null&&pack.getInventory().getCapacity()>0;if(!hasPack)backpack=false;
        int section=backpack?-9:-2;
        if(section!=playerSection){ui.set("#PlayerGrid.InventorySectionId",section);playerSection=section;rendered.remove("#PlayerGrid");}
        ItemContainer playerInventory=backpack?pack.getInventory():storage==null?null:storage.getInventory();
        grid(ui,"#PlayerGrid",playerInventory,0,playerInventory==null?0:playerInventory.getCapacity(),false);
        grid(ui,"#Hotbar",hotbar==null?null:hotbar.getInventory(),0,hotbar==null?0:hotbar.getInventory().getCapacity(),false);
        ui.set("#Title.Text",tr("EMBER CASTING STATION","GLUTGIESSSTATION"));
        ui.set("#Tab.Text",reserve?tr("Workshop","Werkstatt"):tr("Reserve inventory","Vorratsinventar"));
        ui.set("#Workshop.Visible",!reserve);ui.set("#Reserve.Visible",reserve);
        ui.set("#ReserveTabs.Visible",reserve);
        for(int page=0;page<CastingMachine.RESERVE_PAGES;page++)ui.set("#Reserve"+page+".Text",page==reservePage?"["+(page+1)+"]":Integer.toString(page+1));
        ui.set("#MountedArea.Visible",!reserve);
        ui.set("#MountedLabel.Text",tr("MOUNTED CELLS - FIRST PRIORITY","ZELLHALTER - ERSTE PRIORITÄT"));
        ui.set("#ReserveLabel.Text",tr("RESERVE ","VORRAT ")+(reservePage+1)+" / "+CastingMachine.RESERVE_PAGES+tr(" - 24 SLOTS"," - 24 PL\u00c4TZE"));
        ui.set("#InputsLabel.Text",tr("INPUTS","EINGABEN"));
        for(int i=0;i<6;i++)ui.set("#I"+i+".Text",(i+1)+" "+(german?new String[]{"Form","Zelle","Barren","Zutat","Zutat","Zutat"}:new String[]{"Mold","Cell","Ingots","Extra","Extra","Extra"})[i]);
        ui.set("#OutputLabel.Text",tr("PRODUCTS","PRODUKTE"));ui.set("#EmptyLabel.Text",tr("EMPTY CELLS","LEERE ZELLEN"));ui.set("#FuelLabel.Text",tr("FUEL","BRENNSTOFF"));
        ui.set("#Empties.TooltipText",tr("Empty-cell supply and return. Automatic filling takes fresh cells from here.","Vorrat und R\u00fcckgabe leerer Zellen. Die automatische Bef\u00fcllung entnimmt hier neue Zellen."));
        ui.set("#InventoryLabel.Text",backpack?tr("YOUR BACKPACK","DEIN RUCKSACK"):tr("YOUR INVENTORY","DEIN INVENTAR"));
        ui.set("#Metal.Text",tr("Casting metal: ","Gussmetall: ")+metalName(Math.floorMod(machine.metal,10)));
        ui.set("#Metal.Visible",machine.mode==0);
        ui.set("#Metal.TooltipText",tr("Choose which mounted/reserve metal to use for plates, rods or gears.","Metall aus Zellhalter oder Vorrat für Platten, Stangen oder Zahnräder auswählen."));
        ui.set("#Status.Text","Status: "+status()+" | "+tr("Fuel ","Brennstoff ")+Math.round(machine.fuelSeconds)+" s");
        ui.set("#Selection.TextSpans",Message.raw(tr("Ingots automatically fill matching cells.\nEmpty cells: shared supply / return slot.","Barren f\u00fcllen passende Zellen automatisch.\nLeere Zellen: gemeinsames Vorrats-/R\u00fcckgabefach.")));
        ui.set("#Cast.Text",tr("Cast","Gießen"));ui.set("#Fill.Text",tr("Fill cell","Zelle füllen"));ui.set("#Drain.Text",tr("Drain cell","Zelle leeren"));
        ui.set("#Start.Text",tr("Start","Start"));ui.set("#Stop.Text",tr("Stop","Stopp"));ui.set("#All.Text",tr("All","Alles"));
        ui.set("#Close.Text",tr("Close","Schließen"));ui.set("#Prev.Text",tr("Inventory","Inventar"));ui.set("#Next.Text",tr("Backpack","Rucksack"));ui.set("#Next.Visible",hasPack);
        ui.set("#Mode.Text",tr("Mode: ","Modus: ")+(machine.mode==0?tr("Casting","Gießen"):machine.mode==1?tr("Fill","Befüllen"):tr("Drain","Entleeren"))
            +(machine.mode==2?" / "+tr("Amount: ","Menge: ")+(machine.batch==100?tr("All","Alles"):machine.batch)
            :" / "+tr("Auto fill: ","Auto-Bef\u00fcllung: ")+(machine.autoFill?tr("On","An"):tr("Paused","Pausiert"))));
        for(String button:new String[]{"One","Ten","All"})ui.set("#"+button+".Visible",machine.mode==2);
    }
    void grid(UICommandBuilder ui,String id,ItemContainer inventory,int first,int count,boolean cells){
        ItemStack[] contents=new ItemStack[count];for(int i=0;i<count;i++)contents[i]=inventory.getItemStack((short)(first+i));
        if(Arrays.equals(rendered.get(id),contents))return;
        rendered.put(id,contents);
        ItemGridSlot[] slots=new ItemGridSlot[count];
        for(int i=0;i<count;i++){
            ItemStack stack=contents[i];slots[i]=ItemStack.isEmpty(stack)?new ItemGridSlot():new ItemGridSlot(stack);
            if(cells&&EmberCell.isCell(stack))slots[i].setDescription(EmberCell.units(stack)+" / 100 "+tr("ingots per cell","Barren je Zelle"));
        }
        ui.set(id+".Slots",slots);
        // The server's ItemGridSlot codec omits this client property. Without
        // it drag events have no inventory slot to report (default -1).
        CustomUICommand[] commands=ui.getCommands();
        CustomUICommand command=commands[commands.length-1];
        command.data=indexSlots(command.data);
    }
    static String indexSlots(String data){
        BsonDocument payload=BsonDocument.parse(data);
        BsonArray slots=payload.getArray("0");
        for(int i=0;i<slots.size();i++){
            BsonDocument slot=slots.get(i).asDocument();
            slot.put("InventorySlotIndex",new BsonInt32(i));
            // ItemGridSlot's server codec emits IsActivatable=false by default.
            // The client rejects mouse-down on such a slot before it can drag.
            // Enable empty slots too, so every displayed destination is usable.
            slot.put("IsActivatable",BsonBoolean.TRUE);
        }
        return payload.toJson();
    }
    void markDirty(){
        if(block.isValid()){
            var info=block.getStore().getComponent(block,BlockModule.BlockStateInfo.getComponentType());
            if(info!=null)info.markNeedsSaving();
        }
    }
    int section(String grid){
        if("ReserveGrid".equals(grid))return reserveSection(reservePage);
        if("PlayerGrid".equals(grid))return playerSection;
        if("Hotbar".equals(grid))return -1;
        for(int i=0;i<GRIDS.length;i++)if(GRIDS[i].equals(grid))return SECTION_BASE+i;
        return 0;
    }
    ItemContainer container(int section,Ref<EntityStore> ref,Store<EntityStore> store){
        if(section>=RESERVE_SECTION_BASE&&section<RESERVE_SECTION_BASE+reserveViews.length)return reserveViews[section-RESERVE_SECTION_BASE];
        if(section>=SECTION_BASE&&section<SECTION_BASE+views.length)return views[section-SECTION_BASE];
        if(section==-1){var c=store.getComponent(ref,InventoryComponent.Hotbar.getComponentType());return c==null?null:c.getInventory();}
        if(section==-2){var c=store.getComponent(ref,InventoryComponent.Storage.getComponentType());return c==null?null:c.getInventory();}
        if(section==-9){var c=store.getComponent(ref,InventoryComponent.Backpack.getComponentType());return c==null?null:c.getInventory();}
        return null;
    }
    static boolean move(ItemContainer source,int from,ItemContainer target,int to,int amount,String expectedId){
        if(source==null||target==null||from<0||from>=source.getCapacity()||to<0||to>=target.getCapacity()
            ||amount<=0||(source==target&&from==to))return false;
        ItemStack stack=source.getItemStack((short)from);
        if(ItemStack.isEmpty(stack)||expectedId==null||!expectedId.equals(stack.getItemId()))return false;
        int quantity=Math.min(amount,stack.getQuantity());
        if(target instanceof CastingInventoryView view&&(view.first==0||(view.first==34&&to==1)))quantity=1;
        return source.moveItemStackFromSlotToSlot((short)from,quantity,target,(short)to,true).succeeded();
    }
    @Override public void handleDataEvent(Ref<EntityStore> ref,Store<EntityStore> store,Data data){
        if(!accessible()){close();return;}
        String action=data.action==null?"":data.action;
        for(int page=0;page<CastingMachine.RESERVE_PAGES;page++)if(action.equals("Reserve"+page)){
            reservePage=page;reserve=true;rendered.remove("#ReserveGrid");update();return;
        }
        if("Drop".equals(action)){
            boolean changed=move(container(data.sourceSection,ref,store),data.sourceSlot,
                container(section(data.grid),ref,store),data.slotIndex,data.quantity,data.itemId);
            if(!changed)feedback("Item cannot be placed here","Gegenstand passt nicht auf diesen Platz");
            for(CastingPage page:List.copyOf(machine.viewers))page.update();
            return;
        }
        switch(action){
            case "Close"->{close();return;}
            case "Tab"->reserve=!reserve;
            case "Prev"->backpack=false;
            case "Next"->backpack=true;
            case "Metal"->{machine.metal=(machine.metal+1)%10;machine.progress=0;}
            case "Cast"->{machine.mode=0;machine.running=false;machine.progress=0;}
            case "Fill"->{machine.mode=1;machine.running=false;machine.progress=0;}
            case "Drain"->{machine.mode=2;machine.running=false;machine.progress=0;}
            case "One"->{machine.batch=1;machine.progress=0;}
            case "Ten"->{machine.batch=10;machine.progress=0;}
            case "All"->{machine.batch=100;machine.progress=0;}
            case "Start"->{machine.running=true;machine.autoFill=true;}
            case "Stop"->{machine.running=false;machine.autoFill=false;machine.progress=0;machine.operationKey="";}
            default->{return;}
        }
        var info=block.getStore().getComponent(block,BlockModule.BlockStateInfo.getComponentType());if(info!=null)info.markNeedsSaving();
        for(CastingPage page:List.copyOf(machine.viewers))page.update();
    }
    @Override public void onDismiss(Ref<EntityStore> ref,Store<EntityStore> store){
        active=false;if(machine!=null)machine.viewers.remove(this);
        Arrays.fill(views,null);
        Arrays.fill(reserveViews,null);
    }
    String metalName(int i){return german?new String[]{"Kupfer","Eisen","Silber","Gold","Kobalt","Thorium","Adamantit","Mithril","Onyxium","Prisma"}[i]:EmberCell.METALS[i];}
    void feedback(String en,String de){feedback=tr(en,de);feedbackUntil=System.nanoTime()+3_000_000_000L;}
    String status(){
        if(System.nanoTime()<feedbackUntil)return feedback;
        String s=machine.status;if(!german)return s;
        s=switch(s){
            case "Ready"->"Bereit";case "Stopped"->"Gestoppt";case "Fuel required"->"Brennstoff fehlt";
            case "Waiting for ingots"->"Warte auf Barren";
            case "Reserve full"->"Vorrat voll";
            case "Matching cell holder blocked"->"Passender Zellhalter blockiert";
            case "Matching partial cell or empty cells required"->"Passende Teilzelle oder leere Zellen erforderlich";
            case "Cell needs one slot"->"Zelle ben\u00f6tigt einen eigenen Platz";
            case "Insert a mold in input 1"->"Gussform in Eingabe 1 einsetzen";
            case "Input 1 requires a casting mold"->"Eingabe 1 benötigt eine Gussform";
            case "Insert a cell in input 2"->"Zelle in Eingabe 2 einsetzen";
            case "Input 2 needs exactly one cell"->"Eingabe 2 benötigt genau eine Zelle";
            case "Insert ingots in input 3"->"Barren in Eingabe 3 einlegen";
            case "Cannot mix metals"->"Metalle können nicht gemischt werden";
            case "Cell full"->"Zelle voll";case "Cell empty"->"Zelle leer";
            case "Product outputs full"->"Produktausgänge voll";case "Empty-cell output full"->"Leergutausgabe voll";
            default->s.replace("Not enough ","Nicht genügend ").replace(" / empty-cell output blocked"," / Leergutausgabe blockiert")
                .replace("Fill ","Befüllen ").replace("Drain -","Entleeren -").replace(" Plate "," Platte ").replace(" Rod "," Stange ").replace(" Gear "," Zahnrad ");
        };
        for(int i=0;i<10;i++)s=s.replace(EmberCell.METALS[i],metalName(i));return s;
    }
    public static final class Data{
        public static final BuilderCodec<Data> CODEC=BuilderCodec.builder(Data.class,Data::new)
            .append(new KeyedCodec<>("Action",Codec.STRING),(o,v)->o.action=v,o->o.action).add()
            .append(new KeyedCodec<>("Grid",Codec.STRING),(o,v)->o.grid=v,o->o.grid).add()
            .append(new KeyedCodec<>("SlotIndex",Codec.INTEGER),(o,v)->o.slotIndex=v,o->o.slotIndex).add()
            .append(new KeyedCodec<>("SourceInventorySectionId",Codec.INTEGER),(o,v)->o.sourceSection=v,o->o.sourceSection).add()
            .append(new KeyedCodec<>("SourceSlotId",Codec.INTEGER),(o,v)->o.sourceSlot=v,o->o.sourceSlot).add()
            .append(new KeyedCodec<>("ItemStackQuantity",Codec.INTEGER),(o,v)->o.quantity=v,o->o.quantity).add()
            .append(new KeyedCodec<>("ItemStackId",Codec.STRING),(o,v)->o.itemId=v,o->o.itemId).add().build();
        String action,grid,itemId;
        int slotIndex=-1,sourceSection,sourceSlot=-1,quantity;
    }
}

package org.darselx.endlessember;

import com.hypixel.hytale.codec.*;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.protocol.packets.interface_.*;
import com.hypixel.hytale.server.core.entity.entities.Player;
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
import org.joml.Vector3i;

/** Click a player item, then an inventory slot to insert; click occupied slots to retrieve. */
public final class CastingPage extends InteractiveCustomUIPage<CastingPage.Data> {
    final Ref<ChunkStore> block;
    final boolean german;
    int selected=-1,offset;
    ItemStack selectedStack;
    boolean reserve;
    CastingMachine machine;
    final java.util.Map<String,ItemStack> rendered=new java.util.HashMap<>();
    String feedback="";
    long feedbackUntil;
    CastingPage(PlayerRef player,Ref<ChunkStore> block){
        super(player,CustomPageLifetime.CanDismissOrCloseThroughInteraction,Data.CODEC);this.block=block;
        german=player.getLanguage()!=null&&player.getLanguage().startsWith("de");
    }
    String tr(String en,String de){return german?de:en;}
    @Override public void build(Ref<EntityStore> ref,UICommandBuilder ui,UIEventBuilder events,Store<EntityStore> store){
        rendered.clear();
        ui.append("Pages/EndlessEmber/CastingStation.ui");
        if(!valid(ref,store))return;
        machine=block.getStore().getComponent(block,CastingMachine.TYPE);machine.viewers.add(this);
        for(int n=0;n<10;n++)ui.set("#S"+n+" #Role.Text",metalName(n));
        for(int n=0;n<44;n++)events.addEventBinding(CustomUIEventBindingType.Activating,"#S"+n,
            EventData.of("Action","slot").append("Slot",Integer.toString(n)),false);
        for(int n=0;n<45;n++)events.addEventBinding(CustomUIEventBindingType.Activating,"#P"+n,
            EventData.of("Action","player").append("Slot",Integer.toString(n)),false);
        for(String action:new String[]{"Tab","Metal","Start","Stop","Cast","Fill","Drain","One","Ten","All","Cancel","Prev","Next","Close"})
            events.addEventBinding(CustomUIEventBindingType.Activating,"#"+action,EventData.of("Action",action),false);
        refresh(ui,store.getComponent(ref,Player.getComponentType()));
    }
    boolean valid(Ref<EntityStore> ref,Store<EntityStore> store){
        if(!ref.isValid()||!block.isValid()||store.getExternalData().getWorld()!=block.getStore().getExternalData().getWorld())return false;
        var info=block.getStore().getComponent(block,BlockModule.BlockStateInfo.getComponentType());
        var transform=store.getComponent(ref,TransformComponent.getComponentType());
        Vector3i position=new Vector3i();
        if(info==null||transform==null||!info.fillWorldPos(position)||transform.getPosition().distanceSquared(position.x+.5,position.y+.5,position.z+.5)>100)return false;
        return block.getStore().getComponent(block,CastingMachine.TYPE)!=null&&block.getStore().getComponent(block,ItemContainerBlock.getComponentType())!=null;
    }
    void update(){
        Ref<EntityStore> ref=playerRef.getReference();
        if(ref==null||!ref.isValid()||!valid(ref,ref.getStore())){
            if(machine!=null)machine.viewers.remove(this);
            if(ref!=null&&ref.isValid())close();return;
        }
        UICommandBuilder ui=new UICommandBuilder();refresh(ui,ref.getStore().getComponent(ref,Player.getComponentType()));sendUpdate(ui,false);
    }
    void refresh(UICommandBuilder ui,Player player){
        if(player==null)return;
        ItemContainer c=block.getStore().getComponent(block,ItemContainerBlock.getComponentType()).getItemContainer();
        for(short n=0;n<44;n++)slot(ui,"#S"+n,c.getItemStack(n));
        ItemContainer inv=inventory(playerRef.getReference(),playerRef.getReference().getStore());
        for(short n=0;n<45;n++)slot(ui,"#P"+n,n+offset<inv.getCapacity()?inv.getItemStack((short)(n+offset)):null);
        ui.set("#Title.Text",tr("EMBER CASTING STATION","GLUTGIESSSTATION"));
        ui.set("#Tab.Text",reserve?tr("Workshop","Werkstatt"):tr("Reserve inventory","Vorratsinventar"));
        ui.set("#Workshop.Visible",!reserve);ui.set("#Reserve.Visible",reserve);
        ui.set("#MountedLabel.Text",tr("MOUNTED CELLS · FIRST PRIORITY","EINGEHÄNGTE ZELLEN · ERSTE PRIORITÄT"));
        ui.set("#ReserveLabel.Text",tr("RESERVE · USED AFTER MOUNTED CELLS","VORRAT · NACH EINGEHÄNGTEN ZELLEN"));
        ui.set("#InputsLabel.Text",tr("INPUTS: 1 MOLD · 2 CELL · 3 INGOTS · 4–6 INGREDIENTS","EINGABEN: 1 FORM · 2 ZELLE · 3 BARREN · 4–6 ZUTATEN"));
        ui.set("#OutputLabel.Text",tr("PRODUCTS","PRODUKTE"));ui.set("#EmptyLabel.Text",tr("EMPTY CELLS","LEERE ZELLEN"));ui.set("#FuelLabel.Text",tr("FUEL","BRENNSTOFF"));
        ui.set("#InventoryLabel.Text",tr("YOUR INVENTORY · SELECT ITEM, THEN TARGET SLOT","DEIN INVENTAR · GEGENSTAND WÄHLEN, DANN ZIELPLATZ"));
        ui.set("#Metal.Text",tr("Metal: ","Metall: ")+metalName(Math.floorMod(machine.metal,10)));
        ui.set("#Status.Text","Status: "+status()+" | "+tr("Fuel ","Brennstoff ")+Math.round(machine.fuelSeconds)+" s");
        ui.set("#Selection.Text",selected<0?Message.raw(tr("Click a filled machine slot to retrieve its contents.","Belegten Maschinenplatz anklicken, um den Inhalt zu entnehmen.")):
            Message.raw(tr("Selected: ","Ausgewählt: ")).insert(ItemStack.isEmpty(selectedStack)?Message.raw(""):selectedStack.getDisplayName()).insert(tr(" · click a target slot"," · Zielplatz anklicken")));
        ui.set("#Cast.Text",tr("Cast","Gießen"));ui.set("#Fill.Text",tr("Fill cell","Zelle füllen"));ui.set("#Drain.Text",tr("Drain cell","Zelle leeren"));
        ui.set("#Start.Text",tr("Start","Start"));ui.set("#Stop.Text",tr("Stop","Stopp"));
        ui.set("#All.Text",tr("All","Alles"));ui.set("#Cancel.Text",tr("Clear selection","Auswahl aufheben"));
        ui.set("#Close.Text",tr("Close","Schließen"));
        ui.set("#Mode.Text",tr("Mode: ","Modus: ")+(machine.mode==0?tr("Casting","Gießen"):machine.mode==1?tr("Fill","Befüllen"):tr("Drain","Entleeren"))+" · "+tr("Amount: ","Menge: ")+(machine.batch==100?tr("All","Alles"):machine.batch));
    }
    void slot(UICommandBuilder ui,String id,ItemStack stack){
        if(rendered.containsKey(id)&&CastingMachine.same(rendered.get(id),stack))return;
        rendered.put(id,stack);
        ui.set(id+" #Icon.Slots",new ItemGridSlot[]{ItemStack.isEmpty(stack)?new ItemGridSlot():new ItemGridSlot(stack)});
        ui.set(id+" #Count.Text",ItemStack.isEmpty(stack)?"":EmberCell.isCell(stack)?EmberCell.units(stack)+"/100 ×"+stack.getQuantity():Integer.toString(stack.getQuantity()));
    }
    @Override public void handleDataEvent(Ref<EntityStore> ref,Store<EntityStore> store,Data data){
        if(!valid(ref,store)){close();return;}
        Player player=store.getComponent(ref,Player.getComponentType());if(player==null)return;
        var c=block.getStore().getComponent(block,ItemContainerBlock.getComponentType()).getItemContainer();
        ItemContainer inv=inventory(ref,store);
        String action=data.action==null?"":data.action;
        int clicked;try{clicked=Integer.parseInt(data.slot);}catch(RuntimeException e){clicked=-1;}
        switch(action){
            case "Close"->{close();return;}
            case "Tab"->reserve=!reserve;
            case "Cancel"->{selected=-1;selectedStack=null;}
            case "Prev"->{offset=Math.max(0,offset-45);selected=-1;}
            case "Next"->{if(offset+45<inv.getCapacity())offset+=45;selected=-1;}
            case "player"->{
                int n=clicked+offset;if(clicked>=0&&clicked<45&&n<inv.getCapacity()){
                    selected=n;selectedStack=inv.getItemStack((short)n);if(ItemStack.isEmpty(selectedStack))selected=-1;
                }
            }
            case "slot"->{
                int n=clicked;if(n<0||n>=44)return;
                ItemStack destination=c.getItemStack((short)n);
                if(selected>=0&&selected<inv.getCapacity()){
                    ItemStack source=inv.getItemStack((short)selected);
                    if(!CastingMachine.same(source,selectedStack)){selected=-1;update();return;}
                    if(!CastingMachine.accepts(n,source)){feedback("Item does not fit this slot","Gegenstand passt nicht in diesen Platz");update();return;}
                    int max=n<10||n==CastingMachine.INPUT+1?1:source.getItem().getMaxStack();
                    int present=ItemStack.isEmpty(destination)?0:destination.getQuantity();
                    if(present>0&&!destination.isStackableWith(source)){feedback("Retrieve this slot first","Diesen Platz zuerst leeren");update();return;}
                    int quantity=Math.min(source.getQuantity(),max-present);
                    if(quantity>0){
                        var transfer=inv.moveItemStackFromSlotToSlot((short)selected,quantity,c,(short)n,true);
                        if(transfer.succeeded()&&n>=CastingMachine.INPUT&&n<CastingMachine.OUTPUT)machine.progress=0;
                    }
                    selected=-1;selectedStack=null;
                }else if(!ItemStack.isEmpty(destination)){
                    if(inv.canAddItemStack(destination,true,false)){
                        var result=inv.addItemStack(destination,true,false,false);
                        if(result.succeeded()){c.setItemStackForSlot((short)n,null);if(n>=CastingMachine.INPUT&&n<CastingMachine.OUTPUT)machine.progress=0;}
                    }else feedback("Your inventory is full","Dein Inventar ist voll");
                }
            }
            case "Metal"->{machine.metal=(machine.metal+1)%10;machine.progress=0;}
            case "Cast"->{machine.mode=0;machine.running=false;machine.progress=0;}
            case "Fill"->{machine.mode=1;machine.running=false;machine.progress=0;}
            case "Drain"->{machine.mode=2;machine.running=false;machine.progress=0;}
            case "One"->{machine.batch=1;machine.progress=0;}
            case "Ten"->{machine.batch=10;machine.progress=0;}
            case "All"->{machine.batch=100;machine.progress=0;}
            case "Start"->machine.running=true;
            case "Stop"->machine.running=false;
            default->{return;}
        }
        BlockModule.BlockStateInfo info=block.getStore().getComponent(block,BlockModule.BlockStateInfo.getComponentType());if(info!=null)info.markNeedsSaving();
        for(CastingPage page:java.util.List.copyOf(machine.viewers))page.update();
    }
    @Override public void onDismiss(Ref<EntityStore> ref,Store<EntityStore> store){if(machine!=null)machine.viewers.remove(this);}
    String metalName(int i){return german?new String[]{"Kupfer","Eisen","Silber","Gold","Kobalt","Thorium","Adamantit","Mithril","Onyxium","Prisma"}[i]:EmberCell.METALS[i];}
    void feedback(String en,String de){feedback=tr(en,de);feedbackUntil=System.nanoTime()+3_000_000_000L;}
    String status(){
        if(System.nanoTime()<feedbackUntil)return feedback;
        String s=machine.status;if(!german)return s;
        s=switch(s){
            case "Ready"->"Bereit";case "Stopped"->"Gestoppt";case "Fuel required"->"Brennstoff fehlt";
            case "Insert a mold in input 1"->"Gussform in Eingabe 1 einsetzen";
            case "Input 1 requires a casting mold"->"Eingabe 1 benötigt eine Gussform";
            case "Insert a cell in input 2"->"Zelle in Eingabe 2 einsetzen";
            case "Input 2 needs exactly one cell"->"Eingabe 2 benötigt genau eine Zelle";
            case "Insert ingots in input 3"->"Barren in Eingabe 3 einlegen";
            case "Cannot mix metals"->"Metalle können nicht gemischt werden";
            case "Cell full"->"Zelle voll";case "Cell empty"->"Zelle leer";
            case "Product outputs full"->"Produktausgänge voll";case "Empty-cell output full"->"Leergutausgabe voll";
            default->s.replace("Not enough ","Nicht genügend ").replace(" / empty-cell output blocked"," / Leergutausgabe blockiert")
                .replace("Fill +","Befüllen +").replace("Drain -","Entleeren -").replace(" Plate "," Platte ").replace(" Rod "," Stange ").replace(" Gear "," Zahnrad ");
        };
        for(int i=0;i<10;i++)s=s.replace(EmberCell.METALS[i],metalName(i));return s;
    }
    static ItemContainer inventory(Ref<EntityStore> ref,Store<EntityStore> store){
        java.util.List<ItemContainer> sections=new java.util.ArrayList<>();
        var storage=store.getComponent(ref,InventoryComponent.Storage.getComponentType());if(storage!=null)sections.add(storage.getInventory());
        var hotbar=store.getComponent(ref,InventoryComponent.Hotbar.getComponentType());if(hotbar!=null)sections.add(hotbar.getInventory());
        var backpack=store.getComponent(ref,InventoryComponent.Backpack.getComponentType());if(backpack!=null)sections.add(backpack.getInventory());
        return new CombinedItemContainer(sections.toArray(ItemContainer[]::new));
    }
    public static final class Data{
        public static final BuilderCodec<Data> CODEC=BuilderCodec.builder(Data.class,Data::new)
            .append(new KeyedCodec<>("Action",Codec.STRING),(o,v)->o.action=v,o->o.action).add()
            .append(new KeyedCodec<>("Slot",Codec.STRING),(o,v)->o.slot=v,o->o.slot).add().build();
        String action,slot="";
    }
}

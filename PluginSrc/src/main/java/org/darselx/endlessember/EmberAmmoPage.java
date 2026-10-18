package org.darselx.endlessember;

import com.hypixel.hytale.codec.*;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.protocol.packets.interface_.*;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.inventory.*;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.builder.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;

public final class EmberAmmoPage extends InteractiveCustomUIPage<EmberAmmoPage.Data> {
    final ItemContainer heldContainer;
    final short heldSlot;
    final ItemStack weapon;
    final boolean german;
    final String language;
    List<EmberAmmo.Choice> choices=List.of();
    EmberAmmoPage(PlayerRef player,ItemContainer container,short slot,ItemStack weapon){
        super(player,CustomPageLifetime.CanDismissOrCloseThroughInteraction,Data.CODEC);
        heldContainer=container;heldSlot=slot;this.weapon=weapon;
        language=player.getLanguage()==null?"en-US":player.getLanguage();
        german=language.startsWith("de");
    }
    static void open(InteractionContext ctx){
        if(!EmberAmmo.WEAPONS.containsKey(ctx.getHeldItem().getItemId()))return;
        var owner=ctx.getOwningEntity();var container=ctx.getHeldItemContainer();var slot=ctx.getHeldItemSlot();var weapon=ctx.getHeldItem();
        ctx.getCommandBuffer().run(store->{
            if(!owner.isValid()||!weapon.equals(container.getItemStack(slot)))return;
            var player=store.getComponent(owner,Player.getComponentType());var playerRef=store.getComponent(owner,PlayerRef.getComponentType());
            if(player!=null&&playerRef!=null)player.getPageManager().openCustomPage(owner,store,new EmberAmmoPage(playerRef,container,slot,weapon));
        });
    }
    boolean valid(Ref<EntityStore> ref,Store<EntityStore> store){
        var hotbar=store.getComponent(ref,InventoryComponent.Hotbar.getComponentType());
        return hotbar!=null&&hotbar.getInventory()==heldContainer&&hotbar.getActiveSlot()==heldSlot&&weapon.equals(heldContainer.getItemStack(heldSlot));
    }
    @Override public void build(Ref<EntityStore> ref,UICommandBuilder ui,UIEventBuilder events,Store<EntityStore> store){
        render(ui,events,InventoryComponent.getCombined(store,ref,InventoryComponent.HOTBAR_STORAGE_BACKPACK));
    }
    void render(UICommandBuilder ui,UIEventBuilder events,ItemContainer inventory){
        ui.append("Pages/EndlessEmber/AmmoMenu.ui");
        ui.set("#Title.Text",german?"MUNITION LADEN":"LOAD AMMUNITION");
        ui.set("#Weapon.Text",name(weapon.getItemId()));
        ui.set("#Hint.Text",german?"Auswahl bleibt aktiv, bis leer oder eine andere Munition gewählt wird.":"Selection stays active until empty or another ammunition type is selected.");
        ui.set("#Close.Text",german?"Schließen":"Close");
        choices=EmberAmmo.available(weapon,inventory);
        ui.set("#Empty.Text",german?"Keine passende Munition im Inventar.":"No compatible ammunition in your inventory.");
        ui.set("#Empty.Visible",choices.isEmpty());
        for(int i=0;i<11;i++){
            ui.set("#Row"+i+".Visible",i<choices.size());
            if(i>=choices.size())continue;
            var choice=choices.get(i);int count=EmberAmmo.count(inventory,choice.id());
            ui.set("#Icon"+i+".Slots",new ItemGridSlot[]{new ItemGridSlot(new ItemStack(choice.id()))});
            // TextButton.Text accepts a string, not a structured Message payload.
            String label=(EmberAmmo.selected(weapon,choice)?(german?"[Geladen] ":"[Loaded] "):"")
                +name(choice.id())+"  \u00d7 "+count;
            ui.set("#Load"+i+".Text",label);
            events.addEventBinding(CustomUIEventBindingType.Activating,"#Load"+i,EventData.of("Action",Integer.toString(i)),false);
        }
        events.addEventBinding(CustomUIEventBindingType.Activating,"#Close",EventData.of("Action","Close"),false);
    }
    String name(String id){
        String key=new ItemStack(id).getItem().getTranslationKey();
        String text=I18nModule.get().getMessage(language,key);
        if(text==null||text.isBlank())text=I18nModule.get().getMessage("en-US",key);
        return text==null||text.isBlank()?id:text;
    }
    @Override public void handleDataEvent(Ref<EntityStore> ref,Store<EntityStore> store,Data data){
        if("Close".equals(data.action)||!valid(ref,store)){close();return;}
        int index;try{index=Integer.parseInt(data.action);}catch(Exception e){return;}
        if(index<0||index>=choices.size())return;
        var inventory=InventoryComponent.getCombined(store,ref,InventoryComponent.HOTBAR_STORAGE_BACKPACK);
        var selected=EmberAmmo.select(weapon,choices.get(index),inventory);
        if(selected==null){rebuild();return;}
        heldContainer.setItemStackForSlot(heldSlot,selected);close();
    }
    public static final class Data {
        public static final BuilderCodec<Data> CODEC=BuilderCodec.builder(Data.class,Data::new)
            .append(new KeyedCodec<>("Action",Codec.STRING),(o,v)->o.action=v,o->o.action).add().build();
        String action;
    }
}

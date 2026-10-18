package org.darselx.endlessember;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.protocol.*;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.inventory.*;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import org.bson.*;

/** Server-owned gates; clients receive ordinary Simple interactions and synchronized branches. */
public final class EmberWeaponGate extends SimpleInstantInteraction {
    public static final BuilderCodec<EmberWeaponGate> CODEC=BuilderCodec.builder(EmberWeaponGate.class,EmberWeaponGate::new,SimpleInstantInteraction.CODEC)
        .append(new KeyedCodec<>("Action",Codec.STRING),(o,v)->o.action=v,o->o.action).add()
        .append(new KeyedCodec<>("Mode",Codec.INTEGER),(o,v)->o.mode=v,o->o.mode).add()
        .append(new KeyedCodec<>("Modes",Codec.INTEGER),(o,v)->o.modes=v,o->o.modes).add()
        .append(new KeyedCodec<>("RequireCrouch",Codec.BOOLEAN),(o,v)->o.requireCrouch=v,o->o.requireCrouch).add()
        .append(new KeyedCodec<>("Ammo",Codec.STRING),(o,v)->o.ammo=v,o->o.ammo).add().build();
    static final String META="EE_Arsenal";
    static final String[] FUELS={"Endless_Ember_MK1","Endless_Ember_MK2","Endless_Ember_MK3","Endless_Ember_Infinite"};
    String action="Mode",ammo="Ember_Ammo_Induction";
    int mode,modes=4;
    boolean requireCrouch;
    private static final java.util.Map<PlayerRef,Long> NOTICES=java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    public EmberWeaponGate(){}
    @Override public WaitForDataFrom getWaitForDataFrom(){return WaitForDataFrom.Server;}
    @Override public boolean needsRemoteSync(){return true;}
    static BsonDocument state(ItemStack item){
        var meta=item.getMetadata();return meta!=null&&meta.get(META) instanceof BsonDocument state?state.clone():new BsonDocument();
    }
    static double number(BsonDocument state,String key,double fallback){
        var v=state.get(key);return v instanceof BsonNumber n&&Double.isFinite(n.doubleValue())?n.doubleValue():fallback;
    }
    static int selected(ItemStack item,int count){return Math.floorMod((int)number(state(item),"Mode",0),Math.max(1,count));}
    static double heat(BsonDocument state,long now){
        double idle=Math.max(0,(now-number(state,"LastFire",now))/1000.0-.5);
        return Math.max(0,Math.min(120,number(state,"Heat",0))-idle*15);
    }
    static ItemContainer inventory(InteractionContext ctx){return InventoryComponent.getCombined(ctx.getCommandBuffer(),ctx.getOwningEntity(),InventoryComponent.HOTBAR_STORAGE_BACKPACK);}
    static boolean creative(InteractionContext ctx){
        var p=ctx.getCommandBuffer().getComponent(ctx.getOwningEntity(),Player.getComponentType());return p!=null&&p.getGameMode()==GameMode.Creative;
    }
    static void save(InteractionContext ctx,BsonDocument state){
        var item=ctx.getHeldItem().withMetadata(META,state);ctx.getHeldItemContainer().setItemStackForSlot(ctx.getHeldItemSlot(),item);ctx.setHeldItem(item);
    }
    static void notice(InteractionContext ctx,String key,String value){
        var player=ctx.getCommandBuffer().getComponent(ctx.getOwningEntity(),PlayerRef.getComponentType());if(player==null)return;
        long now=System.currentTimeMillis();if(now-NOTICES.getOrDefault(player,0L)<1200)return;NOTICES.put(player,now);
        var message=Message.translation("DarselXArsenal.Messages."+key);if(value!=null)message.param("value",Message.translation(value));player.sendMessage(message);
    }
    static void fail(InteractionContext ctx,String key){ctx.getState().state=InteractionState.Failed;if(key!=null)notice(ctx,key,null);}
    @Override protected void firstRun(InteractionType type,InteractionContext ctx,CooldownHandler cooldown){
        var item=ctx.getHeldItem();if(ItemStack.isEmpty(item)||ctx.getHeldItemContainer()==null){fail(ctx,null);return;}
        switch(action){
            case "AmmoMenu"->EmberAmmoPage.open(ctx);
            case "Mode"->{if(selected(item,modes)!=mode)fail(ctx,null);}
            case "Cycle"->{
                var movement=ctx.getCommandBuffer().getComponent(ctx.getOwningEntity(),MovementStatesComponent.getComponentType());
                if(requireCrouch&&(movement==null||!movement.getMovementStates().crouching)){fail(ctx,null);return;}
                int next=(selected(item,modes)+1)%Math.max(1,modes);var state=state(item);state.put("Mode",new BsonInt32(next));save(ctx,state);
                notice(ctx,"Mode",(modes==3?"DarselXArsenal.Modes.Pollen":"DarselXArsenal.Modes.Ammo")+next);
            }
            case "Flame"->flame(ctx,System.currentTimeMillis());
            case "Energy"->energy(ctx);
            case "CoilEnergy"->energy(ctx,false);
            case "LanceFuel"->fuel(ctx,2);
            case "Inspect"->{
                var state=state(item);var player=ctx.getCommandBuffer().getComponent(ctx.getOwningEntity(),PlayerRef.getComponentType());
                if(player!=null)player.sendMessage(Message.translation("DarselXArsenal.Messages.FuelHeat").param("fuel",Math.round(Math.max(0,number(state,"Fuel",0)))).param("heat",Math.round(heat(state,System.currentTimeMillis()))));
            }
            default->fail(ctx,null);
        }
    }
    void flame(InteractionContext ctx,long now){
        if(creative(ctx))return;
        var state=state(ctx.getHeldItem());double heat=heat(state,now);
        if(now<number(state,"LockedUntil",0)){fail(ctx,"Overheated");return;}
        double fuel=Math.max(0,number(state,"Fuel",0));
        if(fuel+.00001<.4){
            var inventory=inventory(ctx);boolean loaded=false;
            for(String id:EmberAmmo.fuels(ctx.getHeldItem())){
                var stack=new ItemStack(id);double seconds=stack.getItem().getFuelQuality();
                if(seconds>0&&inventory.removeItemStack(stack,true,true).succeeded()){fuel+=seconds;loaded=true;break;}
            }
            if(!loaded){fail(ctx,"FuelRequired");return;}
        }
        heat+=12;state.put("Fuel",new BsonDouble(Math.max(0,fuel-.4)));state.put("Heat",new BsonDouble(heat));state.put("LastFire",new BsonInt64(now));
        if(heat>=100)state.put("LockedUntil",new BsonInt64(now+6000));save(ctx,state);
    }
    void energy(InteractionContext ctx){
        energy(ctx,true);
    }
    void energy(InteractionContext ctx,boolean needsSlug){
        if(creative(ctx))return;
        var inventory=inventory(ctx);if(needsSlug&&!inventory.canRemoveItemStack(new ItemStack(ammo),true,true)){fail(ctx,"SlugRequired");return;}
        var state=state(ctx.getHeldItem());int energy=Math.max(0,Math.min(8,(int)number(state,"Energy",0)));
        if(energy==0){if(!inventory.removeItemStack(new ItemStack("Ember_Energy_Cell"),true,true).succeeded()){fail(ctx,"EnergyRequired");return;}energy=8;}
        state.put("Energy",new BsonInt32(energy-1));save(ctx,state);
    }
    void fuel(InteractionContext ctx,double cost){
        if(creative(ctx))return;
        var state=state(ctx.getHeldItem());double fuel=Math.max(0,number(state,"Fuel",0));
        if(fuel<cost){
            boolean loaded=false;
            for(String id:EmberAmmo.fuels(ctx.getHeldItem())){var stack=new ItemStack(id);double seconds=stack.getItem().getFuelQuality();
                if(seconds>0&&inventory(ctx).removeItemStack(stack,true,true).succeeded()){fuel+=seconds;loaded=true;break;}}
            if(!loaded){fail(ctx,"FuelRequired");return;}
        }
        state.put("Fuel",new BsonDouble(fuel-cost));save(ctx,state);
    }
}

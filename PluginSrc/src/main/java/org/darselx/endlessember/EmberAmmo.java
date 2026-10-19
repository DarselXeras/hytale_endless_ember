package org.darselx.endlessember;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import org.bson.*;
import java.util.*;

/** One authoritative compatibility list for the ammunition picker. */
final class EmberAmmo {
    record Choice(String id,String key,int value) {}
    static final Map<String,List<Choice>> WEAPONS=new LinkedHashMap<>();
    static {
        String[] guns={"Ember_Pistol","Ember_Repeater","Ember_Precision_Rifle"};
        String[] rounds={"Ember_Ammo_Pistol","Ember_Ammo_Rifle","Ember_Ammo_Precision"};
        for(int i=0;i<guns.length;i++){
            var choices=new ArrayList<Choice>();int mode=0;
            for(String suffix:new String[]{"","_AP","_Incendiary","_Marking"})choices.add(new Choice(rounds[i]+suffix,"Mode",mode++));
            WEAPONS.put(guns[i],List.copyOf(choices));
        }
        var machineGun=new ArrayList<Choice>();
        for(int i=0;i<EmberMachineGun.KINDS.length;i++)machineGun.add(new Choice(EmberMachineGun.ammo(i),"Mode",i));
        WEAPONS.put(EmberMachineGun.ID,List.copyOf(machineGun));
        var rockets=new ArrayList<Choice>();
        for(int i=0;i<EmberRocketLauncher.KINDS.length;i++)rockets.add(new Choice(EmberRocketLauncher.ammo(i),"Mode",i));
        WEAPONS.put(EmberRocketLauncher.ID,List.copyOf(rockets));
        single("Ember_Workshop_Crossbow","Weapon_Arrow_Crude");
        single("Ember_Shotgun","Ember_Ammo_Shotgun");
        single("Ember_Induction_Rifle","Ember_Ammo_Induction");
        single("Ember_Coil_Thrower","Ember_Energy_Cell");
        single("Ember_Harpoon_Launcher","Ember_Ammo_Harpoon");
        single("Ember_Slag_Launcher","Ember_Ammo_Slag");
        var pollen=new ArrayList<Choice>();int mode=0;
        for(String kind:new String[]{"Slow","Marking","Poison"})pollen.add(new Choice("Ember_Ammo_Pollen_"+kind,"Mode",mode++));
        WEAPONS.put("Ember_Pollen_Launcher",List.copyOf(pollen));
        var fuel=new ArrayList<Choice>();
        for(int i=0;i<EmberWeaponGate.FUELS.length;i++)fuel.add(new Choice(EmberWeaponGate.FUELS[i],"FuelType",i));
        WEAPONS.put("Ember_Flamethrower",List.copyOf(fuel));
        var signals=new ArrayList<Choice>();
        for(int i=0;i<EmberSignalPistol.COLOURS.length;i++)signals.add(new Choice(EmberSignalPistol.ammo(i),"SignalColour",i));
        WEAPONS.put("Ember_Signal_Pistol",List.copyOf(signals));
    }
    static void single(String weapon,String ammo){WEAPONS.put(weapon,List.of(new Choice(ammo,"AmmoType",0)));}
    static int count(ItemContainer inventory,String id){
        int count=0;
        for(short slot=0;slot<inventory.getCapacity();slot++){
            var stack=inventory.getItemStack(slot);
            if(!ItemStack.isEmpty(stack)&&id.equals(stack.getItemId()))count+=stack.getQuantity();
        }
        return count;
    }
    static List<Choice> available(ItemStack weapon,ItemContainer inventory){
        return WEAPONS.getOrDefault(weapon.getItemId(),List.of()).stream().filter(c->count(inventory,c.id)>0).toList();
    }
    static boolean selected(ItemStack weapon,Choice choice){
        return (int)EmberWeaponGate.number(EmberWeaponGate.state(weapon),choice.key,0)==choice.value;
    }
    static ItemStack select(ItemStack weapon,Choice choice,ItemContainer inventory){
        if(!WEAPONS.getOrDefault(weapon.getItemId(),List.of()).contains(choice)||count(inventory,choice.id)==0)return null;
        var state=EmberWeaponGate.state(weapon);state.put(choice.key,new BsonInt32(choice.value));
        state.put("AmmoPinned",BsonBoolean.TRUE);
        return weapon.withMetadata(EmberWeaponGate.META,state);
    }
    static String[] fuels(ItemStack weapon){
        var state=EmberWeaponGate.state(weapon);
        if(!state.containsKey("FuelType"))return EmberWeaponGate.FUELS;
        int index=(int)EmberWeaponGate.number(state,"FuelType",0);
        return new String[]{EmberWeaponGate.FUELS[Math.floorMod(index,EmberWeaponGate.FUELS.length)]};
    }
}

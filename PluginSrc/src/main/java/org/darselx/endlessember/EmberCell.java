package org.darselx.endlessember;

import com.hypixel.hytale.server.core.inventory.ItemStack;

/** Exact, serializable fill levels also survive native placement, rotation and block drops. */
public final class EmberCell {
    static final String[] METALS={"Copper","Iron","Silver","Gold","Cobalt","Thorium","Adamantite","Mithril","Onyxium","Prisma"};
    static final String EMPTY="Fluid_Capsule_Empty";
    static final String PREFIX="Fluid_Capsule_";
    private EmberCell(){}
    static String metal(String id){
        if(id==null)return null;
        for(String m:METALS)if(id.contains(PREFIX+m))return m;
        return null;
    }
    static boolean isCell(ItemStack s){return !ItemStack.isEmpty(s)&&(s.getItemId().equals(EMPTY)||metal(s.getItemId())!=null);}
    static int units(ItemStack s){
        if(!isCell(s)||s.getItemId().equals(EMPTY))return 0;
        String id=s.getItemId();
        int q=id.lastIndexOf("_Q");
        if(q>=0)try{return Math.clamp(Integer.parseInt(id.substring(q+2)),1,99);}catch(NumberFormatException ignored){}
        return 100;
    }
    static ItemStack create(String metal,int units){
        if(units<0||units>100)throw new IllegalArgumentException("Cell capacity exceeded");
        if(units==0)return new ItemStack(EMPTY,1);
        if(java.util.Arrays.stream(METALS).noneMatch(metal::equals))throw new IllegalArgumentException("Unknown metal");
        return new ItemStack(PREFIX+metal+(units==100?"":"_Q"+units),1);
    }
}

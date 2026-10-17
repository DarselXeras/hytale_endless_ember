package org.darselx.endlessember;

import com.hypixel.hytale.codec.*;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.*;
import java.util.*;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

/** World-thread-owned machine state. Plans are simulated before fuel or ingredients are touched. */
public final class CastingMachine implements Component<ChunkStore> {
    static final int CAPACITY=44, RESERVE=10, INPUT=34, OUTPUT=40, EMPTY=42, FUEL=43;
    static final String ID="Ember_Casting_Station";
    static ComponentType<ChunkStore,CastingMachine> TYPE;
    public static final BuilderCodec<CastingMachine> CODEC=BuilderCodec.builder(CastingMachine.class,CastingMachine::new)
        .append(new KeyedCodec<>("Running",Codec.BOOLEAN),(o,v)->o.running=v,o->o.running).add()
        .append(new KeyedCodec<>("Metal",Codec.INTEGER),(o,v)->o.metal=v,o->o.metal).add()
        .append(new KeyedCodec<>("Mode",Codec.INTEGER),(o,v)->o.mode=v,o->o.mode).add()
        .append(new KeyedCodec<>("Batch",Codec.INTEGER),(o,v)->o.batch=v,o->o.batch).add()
        .append(new KeyedCodec<>("Progress",Codec.FLOAT),(o,v)->o.progress=v,o->o.progress).add()
        .append(new KeyedCodec<>("FuelSeconds",Codec.FLOAT),(o,v)->o.fuelSeconds=v,o->o.fuelSeconds).add().build();
    boolean running;
    int metal,mode,batch=100;
    float progress,fuelSeconds;
    transient float elapsed;
    transient String status="Ready";
    transient boolean lit;
    transient boolean working;
    final transient Set<CastingPage> viewers=new HashSet<>();
    public CastingMachine(){}
    @Override public CastingMachine clone(){
        CastingMachine o=new CastingMachine();o.running=running;o.metal=metal;o.mode=mode;o.batch=batch;
        o.progress=progress;o.fuelSeconds=fuelSeconds;return o;
    }
    static ItemStack[] snapshot(ItemContainer c){
        ItemStack[] a=new ItemStack[CAPACITY];for(short i=0;i<CAPACITY;i++)a[i]=c.getItemStack(i);return a;
    }
    static boolean same(ItemStack a,ItemStack b){return ItemStack.isEmpty(a)?ItemStack.isEmpty(b):a.equals(b);}
    static void subtract(ItemStack[] a,int slot,int n){a[slot]=a[slot].withQuantity(a[slot].getQuantity()-n);}
    static boolean add(ItemStack[] a,int first,int end,ItemStack value){
        if(ItemStack.isEmpty(value))return true;
        int remaining=value.getQuantity(),max=value.getItem().getMaxStack();
        for(int pass=0;pass<2;pass++)for(int i=first;i<end;i++){
            ItemStack s=a[i];
            if(pass==0&&!ItemStack.isEmpty(s)&&s.isStackableWith(value)){
                int n=Math.min(remaining,max-s.getQuantity());if(n>0){a[i]=s.withQuantity(s.getQuantity()+n);remaining-=n;}
            }else if(pass==1&&ItemStack.isEmpty(s)){
                int n=Math.min(remaining,max);a[i]=value.withQuantity(n);remaining-=n;
            }
            if(remaining==0)return true;
        }
        return false;
    }
    record Plan(ItemStack[] contents,float seconds,String description){}
    Plan plan(ItemContainer c){
        ItemStack[] a=snapshot(c);
        if(mode==1||mode==2)return servicePlan(a);
        String m=EmberCell.METALS[Math.floorMod(metal,10)];
        ItemStack mold=a[INPUT];
        if(ItemStack.isEmpty(mold)){status="Insert a mold in input 1";return null;}
        String shape=mold.getItemId().replace("Ember_Mold_","");
        int need,quantity;float seconds;
        switch(shape){case "Plate"->{need=2;quantity=1;seconds=12;}case "Rod"->{need=1;quantity=2;seconds=8;}case "Gear"->{need=4;quantity=1;seconds=18;}default->{status="Input 1 requires a casting mold";return null;}}
        if(!takeMetal(a,m,need)){status="Not enough "+m+" / empty-cell output blocked";return null;}
        if(!add(a,OUTPUT,OUTPUT+2,new ItemStack("Ember_Cast_"+m+"_"+shape,quantity))){status="Product outputs full";return null;}
        return new Plan(a,seconds,m+" "+shape);
    }
    private Plan servicePlan(ItemStack[] a){
        int cellSlot=INPUT+1,barSlot=INPUT+2;
        ItemStack cell=a[cellSlot];
        if(!EmberCell.isCell(cell)){status="Insert a cell in input 2";return null;}
        if(cell.getQuantity()!=1){status="Input 2 needs exactly one cell";return null;}
        int units=EmberCell.units(cell),limit=batch==1?1:batch==10?10:100;
        if(mode==1){
            ItemStack bars=a[barSlot];String m=null;
            if(!ItemStack.isEmpty(bars))for(String candidate:EmberCell.METALS)if(bars.getItemId().equals("Ingredient_Bar_"+candidate)){m=candidate;break;}
            if(m==null){status="Insert ingots in input 3";return null;}
            if(units>0&&!m.equals(EmberCell.metal(cell.getItemId()))){status="Cannot mix metals";return null;}
            int n=Math.min(limit,Math.min(100-units,bars.getQuantity()));
            if(n==0){status="Cell full";return null;}
            a[cellSlot]=EmberCell.create(m,units+n);subtract(a,barSlot,n);
            return new Plan(a,Math.max(2,n*.5f),"Fill +"+n);
        }
        if(units==0){status="Cell empty";return null;}
        int n=Math.min(limit,units);String m=EmberCell.metal(cell.getItemId());
        if(!add(a,OUTPUT,OUTPUT+2,new ItemStack("Ingredient_Bar_"+m,n))){status="Product outputs full";return null;}
        if(n==units){
            if(!add(a,EMPTY,EMPTY+1,new ItemStack(EmberCell.EMPTY))){status="Empty-cell output full";return null;}
            a[cellSlot]=null;
        }else a[cellSlot]=EmberCell.create(m,units-n);
        return new Plan(a,Math.max(2,n*.2f),"Drain -"+n);
    }
    static boolean takeMetal(ItemStack[] a,String metal,int units){
        int remaining=units;
        // Exactly one mounted holder for this metal, then reserve. Never draw from other holders.
        int mounted=Arrays.asList(EmberCell.METALS).indexOf(metal);
        int[] slots=new int[25];slots[0]=mounted;for(int n=0;n<24;n++)slots[n+1]=RESERVE+n;
        for(int slot:slots){
            ItemStack s=a[slot];
            if(!EmberCell.isCell(s)||!metal.equals(EmberCell.metal(s.getItemId())))continue;
            int per=EmberCell.units(s);
            if(per==0)continue;
            while(!ItemStack.isEmpty(a[slot])&&remaining>0){
                s=a[slot];int n=Math.min(remaining,per);
                if(n==per){
                    if(!add(a,EMPTY,EMPTY+1,new ItemStack(EmberCell.EMPTY)))return false;
                    subtract(a,slot,1);
                }else{
                    // Keep a partially used cell in its slot; reserve stacks need a free reserve slot for the remainder.
                    ItemStack partial=EmberCell.create(metal,per-n);
                    if(s.getQuantity()==1)a[slot]=partial;
                    else{
                        subtract(a,slot,1);
                        if(!add(a,RESERVE,INPUT,partial))return false;
                    }
                }
                remaining-=n;
                if(remaining==0)return true;
            }
        }
        return remaining==0;
    }
    void tick(float dt,SimpleItemContainer inventory){
        elapsed+=dt;if(elapsed<.25f)return;float step=elapsed;elapsed=0;
        working=false;
        if(!running){status="Stopped";return;}
        Plan p=plan(inventory);if(p==null){progress=0;return;}
        if(!Float.isFinite(fuelSeconds)||fuelSeconds<0)fuelSeconds=0;
        if(fuelSeconds<=0){
            ItemStack fuel=inventory.getItemStack((short)FUEL);
            if(!isFuel(fuel)){status="Fuel required";return;}
            fuelSeconds=(float)fuel.getItem().getFuelQuality();
            inventory.setItemStackForSlot((short)FUEL,fuel.withQuantity(fuel.getQuantity()-1));
        }
        if(!Float.isFinite(progress)||progress<0||progress>p.seconds)progress=0;
        float consumed=Math.max(0,Math.min(step,Math.min(fuelSeconds,p.seconds-progress)));
        progress+=consumed;fuelSeconds-=consumed;status=p.description+" "+Math.round(progress)+" / "+Math.round(p.seconds)+" s";
        working=consumed>0;
        if(progress+.001f>=p.seconds){
            // Fuel was updated after the simulation; never overwrite that slot with the older snapshot.
            for(short i=0;i<FUEL;i++)if(!same(inventory.getItemStack(i),p.contents[i]))inventory.setItemStackForSlot(i,p.contents[i]);
            progress=0;
            if(mode!=0)running=false;
        }
    }
    static boolean accepts(int slot,ItemStack s){
        if(ItemStack.isEmpty(s))return true;
        if(slot<10)return EmberCell.isCell(s)&&EmberCell.METALS[slot].equals(EmberCell.metal(s.getItemId()))&&EmberCell.units(s)>0;
        if(slot<INPUT)return EmberCell.isCell(s);
        if(slot>=INPUT&&slot<OUTPUT)return true;
        if(slot==FUEL)return isFuel(s);
        return false;
    }
    static boolean isFuel(ItemStack s){
        return !ItemStack.isEmpty(s)&&ItemContainer.getMatchingResourceType(s.getItem(),"Fuel")!=null
            &&Double.isFinite(s.getItem().getFuelQuality())&&s.getItem().getFuelQuality()>0;
    }
}

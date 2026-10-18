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
    // Keep all original slots stable; additional reserve pages are appended.
    static final int RESERVE_PAGES=10, PAGE_SIZE=24, RESERVE_SIZE=RESERVE_PAGES*PAGE_SIZE;
    static final int CAPACITY=260, RESERVE=10, INPUT=34, OUTPUT=40, EMPTY=42, FUEL=43;
    static int reserveFirst(int page){
        if(page<0||page>=RESERVE_PAGES)throw new IllegalArgumentException("Reserve page");
        return page==0?RESERVE:44+(page-1)*PAGE_SIZE;
    }
    static int reserveSlot(int index){return index<PAGE_SIZE?RESERVE+index:44+index-PAGE_SIZE;}
    static boolean isReserve(int slot){return slot>=RESERVE&&slot<INPUT||slot>=44&&slot<CAPACITY;}
    static final String ID="Ember_Casting_Station";
    static ComponentType<ChunkStore,CastingMachine> TYPE;
    public static final BuilderCodec<CastingMachine> CODEC=BuilderCodec.builder(CastingMachine.class,CastingMachine::new)
        .append(new KeyedCodec<>("Running",Codec.BOOLEAN),(o,v)->o.running=v,o->o.running).add()
        .append(new KeyedCodec<>("Metal",Codec.INTEGER),(o,v)->o.metal=v,o->o.metal).add()
        .append(new KeyedCodec<>("Mode",Codec.INTEGER),(o,v)->o.mode=v,o->o.mode).add()
        .append(new KeyedCodec<>("Batch",Codec.INTEGER),(o,v)->o.batch=v,o->o.batch).add()
        .append(new KeyedCodec<>("Progress",Codec.FLOAT),(o,v)->o.progress=v,o->o.progress).add()
        .append(new KeyedCodec<>("FuelSeconds",Codec.FLOAT),(o,v)->o.fuelSeconds=v,o->o.fuelSeconds).add()
        .append(new KeyedCodec<>("AutoFill",Codec.BOOLEAN),(o,v)->o.autoFill=v,o->o.autoFill).add()
        .append(new KeyedCodec<>("OperationKey",Codec.STRING),(o,v)->o.operationKey=v,o->o.operationKey).add().build();
    boolean running;
    boolean autoFill=true;
    String operationKey="";
    int metal,mode,batch=100;
    float progress,fuelSeconds;
    transient float elapsed;
    transient String status="Ready";
    transient boolean lit;
    transient boolean working;
    transient boolean inventoryChanged;
    final transient Set<CastingPage> viewers=new HashSet<>();
    final transient CastingDisplays displays=new CastingDisplays();
    public CastingMachine(){}
    @Override public CastingMachine clone(){
        CastingMachine o=new CastingMachine();o.running=running;o.metal=metal;o.mode=mode;o.batch=batch;
        o.progress=progress;o.fuelSeconds=fuelSeconds;o.autoFill=autoFill;o.operationKey=operationKey;return o;
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
    static boolean addReserve(ItemStack[] a,ItemStack value){
        if(ItemStack.isEmpty(value))return true;
        int remaining=value.getQuantity(),max=value.getItem().getMaxStack();
        for(int pass=0;pass<2;pass++)for(int n=0;n<RESERVE_SIZE;n++){
            int slot=reserveSlot(n);ItemStack s=a[slot];
            if(pass==0&&!ItemStack.isEmpty(s)&&s.isStackableWith(value)){
                int amount=Math.min(remaining,Math.max(0,max-s.getQuantity()));
                if(amount>0){a[slot]=s.withQuantity(s.getQuantity()+amount);remaining-=amount;}
            }else if(pass==1&&ItemStack.isEmpty(s)){
                int amount=Math.min(remaining,max);a[slot]=value.withQuantity(amount);remaining-=amount;
            }
            if(remaining==0)return true;
        }
        return false;
    }
    static long reserveCount(ItemContainer inventory,String metal){
        long count=0;
        for(int n=0;n<RESERVE_SIZE;n++){
            ItemStack cell=inventory.getItemStack((short)reserveSlot(n));
            if(EmberCell.isCell(cell)&&EmberCell.units(cell)>0&&metal.equals(EmberCell.metal(cell.getItemId())))count+=cell.getQuantity();
        }
        return count;
    }
    /** Logistics do not require fuel or an active recipe. Move exactly one cell. */
    static void replenishMounted(ItemStack[] a){
        for(int holder=0;holder<10;holder++)if(ItemStack.isEmpty(a[holder])){
            for(int n=0;n<RESERVE_SIZE;n++){
                int slot=reserveSlot(n);ItemStack cell=a[slot];
                if(EmberCell.isCell(cell)&&EmberCell.units(cell)>0&&EmberCell.METALS[holder].equals(EmberCell.metal(cell.getItemId()))){
                    a[holder]=cell.withQuantity(1);subtract(a,slot,1);break;
                }
            }
        }
    }
    static boolean commit(SimpleItemContainer inventory,ItemStack[] contents){
        boolean changed=false;
        for(short i=0;i<CAPACITY;i++)if(i!=FUEL&&!same(inventory.getItemStack(i),contents[i])){
            inventory.setItemStackForSlot(i,contents[i]);changed=true;
        }
        return changed;
    }
    record Plan(ItemStack[] contents,float seconds,String description,String key){
        Plan(ItemStack[] contents,float seconds,String description){this(contents,seconds,description,description);}
    }
    static String ingotMetal(ItemStack bars){
        if(!ItemStack.isEmpty(bars))for(String m:EmberCell.METALS)if(bars.getItemId().equals("Ingredient_Bar_"+m))return m;
        return null;
    }
    Plan plan(ItemContainer c){
        ItemStack[] a=snapshot(c);
        if(mode==2)return servicePlan(a);
        if(mode==1||(autoFill&&ingotMetal(a[INPUT+2])!=null))return fillPlan(a);
        String m=EmberCell.METALS[Math.floorMod(metal,10)];
        ItemStack mold=a[INPUT];
        if(ItemStack.isEmpty(mold)){status="Insert a mold in input 1";return null;}
        String shape=mold.getItemId().replace("Ember_Mold_","");
        int need,quantity;float seconds;
        switch(shape){case "Plate"->{need=2;quantity=1;seconds=12;}case "Rod"->{need=1;quantity=2;seconds=8;}case "Gear"->{need=4;quantity=1;seconds=18;}default->{status="Input 1 requires a casting mold";return null;}}
        if(!takeMetal(a,m,need)){status="Not enough "+m+" / empty-cell output blocked";return null;}
        if(!add(a,OUTPUT,OUTPUT+2,new ItemStack("Ember_Cast_"+m+"_"+shape,quantity))){status="Product outputs full";return null;}
        if(!add(a,OUTPUT,OUTPUT+2,new ItemStack("Ember_Slag",1))){status="Make room for slag in product outputs";return null;}
        return new Plan(a,seconds,m+" "+shape);
    }
    /** Fill one ingot per step so the rack shows the actual rising level. */
    private Plan fillPlan(ItemStack[] a){
        int barsSlot=INPUT+2;
        String m=ingotMetal(a[barsSlot]);
        if(m==null){status="Insert ingots in input 3";return null;}
        int mounted=Arrays.asList(EmberCell.METALS).indexOf(m),target=mounted,service=INPUT+1;
        ItemStack manual=a[service];
        if(EmberCell.isCell(manual)&&(EmberCell.units(manual)==0||m.equals(EmberCell.metal(manual.getItemId()))))target=service;
        ItemStack cell=a[target];int before=EmberCell.units(cell);
        if(!ItemStack.isEmpty(cell)&&(!EmberCell.isCell(cell)||before>0&&!m.equals(EmberCell.metal(cell.getItemId()))||cell.getQuantity()!=1)){
            status="Matching cell holder blocked";return null;
        }
        String key="Fill:"+m+":"+target+":"+(ItemStack.isEmpty(cell)?"new":cell.getItemId());
        if(ItemStack.isEmpty(cell)||before==100){
            ItemStack full=before==100?cell:null;
            // Select the next partial cell before reserving the full one. This
            // also permits a swap when the reserve has no free slots.
            ItemStack next=null;
            for(int n=0;n<RESERVE_SIZE;n++){
                int slot=reserveSlot(n);ItemStack candidate=a[slot];int units=EmberCell.units(candidate);
                if(EmberCell.isCell(candidate)&&units>0&&units<100&&m.equals(EmberCell.metal(candidate.getItemId()))){
                    next=candidate.withQuantity(1);subtract(a,slot,1);break;
                }
            }
            if(next==null){
                if(ItemStack.isEmpty(a[EMPTY])||!EmberCell.EMPTY.equals(a[EMPTY].getItemId())){status="Matching partial cell or empty cells required";return null;}
                next=new ItemStack(EmberCell.EMPTY);subtract(a,EMPTY,1);
            }
            if(!addReserve(a,full)){status="Reserve full";return null;}
            cell=next;before=EmberCell.units(cell);
        }
        int after=before+1;
        subtract(a,barsSlot,1);a[target]=EmberCell.create(m,after);
        return new Plan(a,.5f,"Fill "+m+" "+after+"/100",key);
    }
    private Plan servicePlan(ItemStack[] a){
        int cellSlot=INPUT+1,barSlot=INPUT+2;
        ItemStack cell=a[cellSlot];
        if(!EmberCell.isCell(cell)){status="Insert a cell in input 2";return null;}
        if(cell.getQuantity()!=1){status="Input 2 needs exactly one cell";return null;}
        int units=EmberCell.units(cell),limit=batch==1?1:batch==10?10:100;
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
        int[] slots=new int[1+RESERVE_SIZE];slots[0]=mounted;for(int n=0;n<RESERVE_SIZE;n++)slots[n+1]=reserveSlot(n);
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
                        if(!addReserve(a,partial))return false;
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
        ItemStack[] logistics=snapshot(inventory);replenishMounted(logistics);
        inventoryChanged=commit(inventory,logistics);
        boolean filling=autoFill&&mode!=2&&ingotMetal(inventory.getItemStack((short)(INPUT+2)))!=null;
        if(!running&&!filling){status=autoFill&&mode!=2?"Waiting for ingots":"Stopped";return;}
        Plan p=plan(inventory);if(p==null){progress=0;operationKey="";return;}
        if(operationKey!=null&&!operationKey.isEmpty()&&!operationKey.equals(p.key))progress=0;
        operationKey=p.key;
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
            replenishMounted(p.contents);
            inventoryChanged|=commit(inventory,p.contents);
            progress=0;
            if(mode==2)running=false;
        }
    }
    static boolean accepts(int slot,ItemStack s){
        if(ItemStack.isEmpty(s))return true;
        if(slot<10)return EmberCell.isCell(s)&&EmberCell.METALS[slot].equals(EmberCell.metal(s.getItemId()))&&EmberCell.units(s)>0;
        if(isReserve(slot))return EmberCell.isCell(s);
        if(slot>=INPUT&&slot<OUTPUT)return true;
        if(slot==FUEL)return isFuel(s);
        if(slot==EMPTY)return EmberCell.EMPTY.equals(s.getItemId());
        return false;
    }
    static boolean isFuel(ItemStack s){
        return !ItemStack.isEmpty(s)&&ItemContainer.getMatchingResourceType(s.getItem(),"Fuel")!=null
            &&Double.isFinite(s.getItem().getFuelQuality())&&s.getItem().getFuelQuality()>0;
    }
}

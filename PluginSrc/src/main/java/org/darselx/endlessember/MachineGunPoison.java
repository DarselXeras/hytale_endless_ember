package org.darselx.endlessember;

import com.hypixel.hytale.codec.*;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.*;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;
import java.util.function.IntConsumer;

/** Independent, serialized 15-second stacks; no exponential pollen-poison logic. */
public final class MachineGunPoison implements Component<EntityStore> {
    static ComponentType<EntityStore,MachineGunPoison> TYPE;
    public static final BuilderCodec<MachineGunPoison> CODEC=BuilderCodec.builder(MachineGunPoison.class,MachineGunPoison::new)
        .append(new KeyedCodec<>("Remaining",Codec.DOUBLE_ARRAY),(o,v)->o.remaining=Arrays.stream(v).filter(t->Double.isFinite(t)&&t>0).limit(5).map(t->Math.min(15,t)).toArray(),o->o.remaining).add()
        .append(new KeyedCodec<>("StackCooldown",Codec.DOUBLE),(o,v)->o.cooldown=Double.isFinite(v)?Math.clamp(v,0,2):0,o->o.cooldown).add()
        .append(new KeyedCodec<>("TickRemaining",Codec.DOUBLE),(o,v)->o.untilTick=Double.isFinite(v)?Math.clamp(v,.000001,1):1,o->o.untilTick).add()
        .append(new KeyedCodec<>("Source",Codec.STRING),(o,v)->o.source=v,o->o.source).add().build();
    double[] remaining=new double[0];
    double cooldown,untilTick=1;
    String source="";
    public MachineGunPoison(){}
    boolean add(){
        if(cooldown>1e-6||remaining.length>=5)return false;
        if(remaining.length==0)untilTick=1;
        remaining=Arrays.copyOf(remaining,remaining.length+1);remaining[remaining.length-1]=15;cooldown=2;return true;
    }
    void advance(double dt,IntConsumer tick){
        if(!Double.isFinite(dt)||dt<=0)return;
        while(dt>1e-7&&remaining.length>0){
            double step=Math.min(dt,Math.min(untilTick,Arrays.stream(remaining).min().orElse(dt)));
            for(int i=0;i<remaining.length;i++)remaining[i]-=step;
            cooldown=Math.max(0,cooldown-step);untilTick-=step;dt-=step;
            // A stack expiring exactly on a tick contributes its final tick.
            if(untilTick<=1e-7){tick.accept(remaining.length);untilTick=1;}
            remaining=Arrays.stream(remaining).filter(t->t>1e-7).toArray();
        }
        cooldown=Math.max(0,cooldown-dt);
    }
    @Override public MachineGunPoison clone(){
        var o=new MachineGunPoison();o.remaining=remaining.clone();o.cooldown=cooldown;o.untilTick=untilTick;o.source=source;return o;
    }
    static void damage(Ref<EntityStore> target,MachineGunPoison state,int count,ComponentAccessor<EntityStore> access){
        if(!EmberAdvancedArsenal.living(target,access))return;
        Ref<EntityStore> owner=null;
        try{if(!state.source.isEmpty())owner=access.getExternalData().getRefFromUUID(UUID.fromString(state.source));}catch(IllegalArgumentException ignored){}
        var damage=new Damage(new Damage.EntitySource(owner!=null&&owner.isValid()?owner:target),DamageCause.getAssetMap().getAsset("Poison"),2f*count);
        DamageSystems.executeDamage(target,access,damage);
    }
    static final class Tick extends EntityTickingSystem<EntityStore> {
        @Override public Query<EntityStore> getQuery(){return TYPE;}
        @Override public void tick(float dt,int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> buffer){
            var ref=chunk.getReferenceTo(index);var poison=chunk.getComponent(index,TYPE);
            if(EmberAdvancedArsenal.living(ref,buffer))poison.advance(dt,count->damage(ref,poison,count,buffer));
            else poison.remaining=new double[0];
            if(poison.remaining.length==0)buffer.removeComponent(ref,TYPE);
        }
    }
}

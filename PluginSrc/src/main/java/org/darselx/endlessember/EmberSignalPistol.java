package org.darselx.endlessember;

import com.hypixel.hytale.codec.*;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.Color;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.protocol.packets.worldmap.MapMarker;
import com.hypixel.hytale.protocol.packets.worldmap.MapMarkerIconSize;
import com.hypixel.hytale.protocol.packets.worldmap.TintComponent;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.model.config.*;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.*;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MapMarkerBuilder;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MarkersCollector;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import org.bson.BsonInt32;
import org.joml.Vector3d;
import java.util.*;

/** Harmless aimed projectiles. The firework and shared map marker begin at the burst. */
public final class EmberSignalPistol extends SimpleInstantInteraction {
    public static final BuilderCodec<EmberSignalPistol> CODEC=BuilderCodec.builder(EmberSignalPistol.class,EmberSignalPistol::new,SimpleInstantInteraction.CODEC)
        .append(new KeyedCodec<>("Action",Codec.STRING),(o,v)->o.action=v,o->o.action).add().build();
    static final String[] COLOURS={"Copper","Iron","Silver","Cobalt","Thorium","Gold","Adamantite","Mithril","Onyxium","Prisma","Festival"};
    static final int[] RGB={0xff913f,0xff4747,0xe1efff,0x478aff,0x5bff95,0xffdc42,0xff55a1,0x40edff,0xb36aff,0xffffff,0xffffff};
    static final int FESTIVAL=10;
    static final String PROVIDER="EndlessEmber.Signals";
    static final double RANGE=20,SPEED=18,TRAIL_SPACING=.4,CEILING=com.hypixel.hytale.math.util.ChunkUtil.HEIGHT-.05;
    static final long DURATION_NANOS=50_000_000_000L;
    static ResourceType<EntityStore,Runtime> RUNTIME;
    String action="Fire";
    public EmberSignalPistol(){}
    static void register(EndlessEmberPlugin plugin){
        plugin.getCodecRegistry(com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction.CODEC)
            .register("EE_SignalPistol",EmberSignalPistol.class,CODEC);
        RUNTIME=plugin.getEntityStoreRegistry().registerResource(Runtime.class,Runtime::new);
        plugin.getEntityStoreRegistry().registerSystem(new Tick());
    }
    @Override public WaitForDataFrom getWaitForDataFrom(){return WaitForDataFrom.Server;}
    @Override public boolean needsRemoteSync(){return true;}
    @Override protected void firstRun(InteractionType type,InteractionContext ctx,CooldownHandler cooldown){
        var held=ctx.getHeldItem();
        if(ItemStack.isEmpty(held)||!held.getItemId().equals("Ember_Signal_Pistol")||ctx.getHeldItemContainer()==null){EmberWeaponGate.fail(ctx,null);return;}
        if("Cycle".equals(action))cycle(ctx);else if("Fire".equals(action))fire(ctx);else EmberWeaponGate.fail(ctx,null);
    }
    static String ammo(int colour){return "Ember_Ammo_Signal_"+COLOURS[colour];}
    static int colour(String id){for(int i=0;i<COLOURS.length;i++)if(ammo(i).equals(id))return i;return -1;}
    static int selected(ItemStack held){return Math.floorMod((int)EmberWeaponGate.number(EmberWeaponGate.state(held),"SignalColour",0),COLOURS.length);}
    static boolean available(ItemContainer inventory,int colour){return inventory.canRemoveItemStack(new ItemStack(ammo(colour)),true,true);}
    static int choose(ItemContainer inventory,int selected,boolean creative){
        if(creative||available(inventory,selected))return selected;
        // Falling back to slot order lets players select a colour by carrying its ammunition.
        for(short slot=0;slot<inventory.getCapacity();slot++){
            var stack=inventory.getItemStack(slot);if(!ItemStack.isEmpty(stack)){int c=colour(stack.getItemId());if(c>=0)return c;}
        }
        return -1;
    }
    static void save(InteractionContext ctx,int colour){var state=EmberWeaponGate.state(ctx.getHeldItem());state.put("SignalColour",new BsonInt32(colour));state.put("AmmoPinned",org.bson.BsonBoolean.TRUE);EmberWeaponGate.save(ctx,state);}
    static void cycle(InteractionContext ctx){
        var inventory=EmberWeaponGate.inventory(ctx);int current=selected(ctx.getHeldItem());
        for(int offset=1;offset<=COLOURS.length;offset++){
            int c=(current+offset)%COLOURS.length;
            if(EmberWeaponGate.creative(ctx)||available(inventory,c)){
                save(ctx,c);EmberWeaponGate.notice(ctx,"SignalColour","DarselXAdvanced.Items."+ammo(c)+".name");return;
            }
        }
        EmberWeaponGate.fail(ctx,"SignalAmmo");
    }
    static void fire(InteractionContext ctx){
        var access=ctx.getCommandBuffer();var inventory=EmberWeaponGate.inventory(ctx);boolean creative=EmberWeaponGate.creative(ctx);
        int selected=selected(ctx.getHeldItem());
        boolean pinned=EmberWeaponGate.state(ctx.getHeldItem()).getBoolean("AmmoPinned",org.bson.BsonBoolean.FALSE).getValue();
        int c=pinned?(creative||available(inventory,selected)?selected:-1):choose(inventory,selected,creative);
        if(c<0){EmberWeaponGate.fail(ctx,"SignalAmmo");return;}
        var asset=ModelAsset.getAssetMap().getAsset(ammo(c));if(asset==null){EmberWeaponGate.fail(ctx,null);return;}
        var look=TargetUtil.getLook(ctx.getOwningEntity(),access);var eye=new Vector3d(look.getPosition());
        var direction=new Vector3d(look.getDirection());
        if(!direction.isFinite()||direction.lengthSquared()<1e-9){EmberWeaponGate.fail(ctx,null);return;}
        direction.set(launchDirection(direction,c));
        var start=new Vector3d(eye).fma(.65,direction);
        if(!EmberAdvancedArsenal.clear(eye,new Vector3d(eye).fma(.8,direction),access)){
            EmberWeaponGate.fail(ctx,"SignalBlocked");return;
        }
        Holder<EntityStore> holder=EntityStore.REGISTRY.newHolder();holder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());
        holder.addComponent(PropComponent.getComponentType(),PropComponent.get());
        // The flare model's longitudinal axis is +Y; align its cap with the launch direction.
        var rotation=new Rotation3f().premul(new org.joml.Quaterniond().rotationTo(new Vector3d(0,1,0),direction));
        holder.addComponent(TransformComponent.getComponentType(),new TransformComponent(start,rotation));
        holder.addComponent(ModelComponent.getComponentType(),new ModelComponent(Model.createStaticScaledModel(asset,.5f)));
        var visual=access.addEntity(holder,AddReason.SPAWN);
        if(visual==null){EmberWeaponGate.fail(ctx,null);return;}
        if(!creative&&!inventory.removeItemStack(new ItemStack(ammo(c)),true,true).succeeded()){
            access.removeEntity(visual,RemoveReason.REMOVE);EmberWeaponGate.fail(ctx,"SignalAmmo");return;
        }
        var data=access.getResource(RUNTIME);
        var flight=new Flight(visual,start,direction,c);data.flights.add(flight);emitTrail(flight,access,false);
        installProvider(access,data);save(ctx,c);EmberAdvancedArsenal.wear(ctx,.2);
    }
    static List<Vector3d> trailPoints(Flight flight,boolean finish){
        var points=new ArrayList<Vector3d>();
        // Sample total travelled distance, so even a long tick cannot leave gaps.
        while(flight.nextTrail<=Math.min(flight.range,flight.travelled)+.000001){
            double distance=Math.min(flight.nextTrail,flight.travelled);
            points.add(new Vector3d(flight.origin).fma(distance,flight.direction));
            flight.lastTrail=distance;flight.nextTrail+=TRAIL_SPACING;
        }
        if(finish&&flight.travelled-flight.lastTrail>.001){points.add(new Vector3d(flight.position));flight.lastTrail=flight.travelled;}
        return List.copyOf(points);
    }
    static void emitTrail(Flight flight,ComponentAccessor<EntityStore> access,boolean finish){
        for(var point:trailPoints(flight,finish))particles("Ember_Signal_"+COLOURS[flight.colour]+"_Trail",point,access);
    }
    static void installProvider(ComponentAccessor<EntityStore> access,Runtime data){
        if(data.providerInstalled)return;
        // The map thread only reads an immutable volatile snapshot, never ECS state.
        access.getExternalData().getWorld().getWorldMapManager().addMarkerProvider(PROVIDER,
            (world,player,collector)->collect(data,collector,System.nanoTime()));
        data.providerInstalled=true;
    }
    static void collect(Runtime data,MarkersCollector collector,long now){
        for(var signal:data.signals)if(now<signal.expiresAt)collector.addIgnoreViewDistance(signal.marker);
    }
    static final class Flight {
        final Ref<EntityStore> visual;final Vector3d position,origin,direction;final int colour;final double range;double travelled,nextTrail,lastTrail=-1;
        Flight(Ref<EntityStore> visual,Vector3d position,Vector3d direction,int colour){this.visual=visual;this.position=new Vector3d(position);this.origin=new Vector3d(position);this.direction=new Vector3d(direction).normalize();this.colour=colour;this.range=colour==FESTIVAL?22.5/this.direction.y:RANGE;}
    }
    static Vector3d launchDirection(Vector3d aim,int colour){
        var direction=new Vector3d(aim).normalize();
        if(colour==FESTIVAL){direction.y=Math.max(.6,direction.y);direction.normalize();}
        return direction;
    }
    record Signal(MapMarker marker,long expiresAt){}
    record Glow(Ref<EntityStore> visual,int colour,long born,double seconds){}
    record Firework(Vector3d point,float scale,int colour,long due){}
    static List<Firework> festivalBursts(Vector3d target,long now,java.util.random.RandomGenerator random){
        var bursts=new ArrayList<Firework>();long due=now;
        for(int i=0;i<5;i++){
            var point=new Vector3d(target).add(random.nextDouble(-8,8),random.nextDouble(-2.5,2.5),random.nextDouble(-8,8));
            point.y=Math.clamp(point.y,1,CEILING);
            bursts.add(new Firework(point,(float)random.nextDouble(.7,1.1),random.nextInt(9),due));
            due+=(long)(random.nextDouble(.2,1.1)*1_000_000_000L);
        }
        return List.copyOf(bursts);
    }
    static final class Runtime implements Resource<EntityStore>{
        final List<Flight> flights=new ArrayList<>();final List<Glow> lights=new ArrayList<>();final List<Firework> fireworks=new ArrayList<>();volatile List<Signal> signals=List.of();boolean queued,providerInstalled;
        @Override public Runtime clone(){return new Runtime();}
    }
    static final class Tick extends TickingSystem<EntityStore>{
        @Override public void tick(float dt,int index,Store<EntityStore> store){
            var data=store.getResource(RUNTIME);if(data.queued||(data.flights.isEmpty()&&data.signals.isEmpty()&&data.lights.isEmpty()&&data.fireworks.isEmpty()))return;
            data.queued=true;store.getExternalData().getWorld().execute(()->{
                try{if(!store.isShutdown())advance(dt,store,data,System.nanoTime());}finally{data.queued=false;}
            });
        }
    }
    static void particles(String id,Vector3d point,ComponentAccessor<EntityStore> access){
        particles(id,point,1,id.endsWith("_Trail")?15.5f:50f,access);
    }
    static void particles(String id,Vector3d point,float scale,float seconds,ComponentAccessor<EntityStore> access){
        var viewers=new ArrayList<Ref<EntityStore>>();
        access.getResource(com.hypixel.hytale.server.core.modules.entity.EntityModule.get().getPlayerSpatialResourceType())
            .getSpatialStructure().collect(point,id.endsWith("_Trail")?256:512,viewers);
        // Standalone world-space packet: deliberately no source entity or moving model.
        ParticleUtil.spawnParticleEffect(id,point.x,point.y,point.z,0,0,0,scale,null,null,viewers,access,seconds);
    }
    static Signal signal(Vector3d point,int c,long now){
        int rgb=RGB[c];var tint=new Color((byte)(rgb>>16),(byte)(rgb>>8),(byte)rgb);
        var marker=new MapMarkerBuilder("EndlessEmber.Signal."+UUID.randomUUID(),"Ember_Signal.png",new Transform(point))
            .withName(Message.translation("DarselXAdvanced.Items."+ammo(c)+".name"))
            .withIconSize(MapMarkerIconSize.Major).withComponent(new TintComponent(tint)).build();
        return new Signal(marker,now+DURATION_NANOS);
    }
    static void burst(Flight flight,Store<EntityStore> store,Runtime data,long now){
        if(flight.visual!=null&&flight.visual.isValid())store.removeEntity(flight.visual,RemoveReason.REMOVE);
        var marker=signal(flight.position,flight.colour,now);
        if(flight.colour==FESTIVAL){
            var bursts=festivalBursts(flight.position,now,java.util.concurrent.ThreadLocalRandom.current());data.fireworks.addAll(bursts);
            marker=new Signal(marker.marker,bursts.getLast().due+15_000_000_000L);
        }else showBurst(flight.position,flight.colour,1f/3f,50,store,data,now,"Ember_Signal_"+COLOURS[flight.colour]+"_Burst");
        var next=new ArrayList<>(data.signals);next.add(marker);data.signals=List.copyOf(next);
    }
    static void showBurst(Vector3d point,int colour,float scale,float seconds,Store<EntityStore> store,Runtime data,long now,String effect){
        Holder<EntityStore> holder=EntityStore.REGISTRY.newHolder();holder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());
        holder.addComponent(PropComponent.getComponentType(),PropComponent.get());
        holder.addComponent(TransformComponent.getComponentType(),new TransformComponent(new Vector3d(point),new Rotation3f()));
        holder.addComponent(UUIDComponent.getComponentType(),UUIDComponent.randomUUID());
        holder.addComponent(com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId.getComponentType(),
            new com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId(store.getExternalData().takeNextNetworkId()));
        holder.addComponent(BoundingBox.getComponentType(),new BoundingBox(new com.hypixel.hytale.math.shape.Box(new Vector3d(-.05),new Vector3d(.05))));
        holder.addComponent(DynamicLight.getComponentType(),new DynamicLight(lightColour(colour,0,seconds)));
        holder.addComponent(ModelComponent.getComponentType(),new ModelComponent(burstModel(effect,scale)));
        var ref=store.addEntity(holder,AddReason.SPAWN);
        if(ref!=null)data.lights.add(new Glow(ref,colour,now,seconds));
        else particles(effect,point,scale,seconds,store); // Unloaded destination: retain the world-space fallback.
    }
    static Model burstModel(String effect,float scale){
        var asset=ModelAsset.getAssetMap().getAsset("Ember_Signal_Burst_Anchor");
        if(asset==null)throw new IllegalStateException("Signal effect anchor model is missing");
        var base=Model.createStaticScaledModel(asset,1);
        // Stationary tracked model: clients receive the particle system with the entity,
        // independent of transient particle-event culling. Never attach this to the moving shell.
        var particle=new ModelParticle(effect,com.hypixel.hytale.protocol.EntityPart.Self,"Burst_Origin",null,scale,null,null,false,true);
        return new Model(base.getModelAssetId(),1,base.getRandomAttachmentIds(),base.getAttachments(),
            new com.hypixel.hytale.math.shape.Box(new Vector3d(-.05),new Vector3d(.05)),base.getModel(),base.getTexture(),
            base.getGradientSet(),base.getGradientId(),base.getEyeHeight(),base.getCrouchOffset(),base.getSittingOffset(),base.getSleepingOffset(),
            base.getAnimationSetMap(),base.getCamera(),base.getLight(),new ModelParticle[]{particle},base.getTrails(),
            base.getPhysicsValues(),base.getDetailBoxes(),base.getPhobia(),base.getPhobiaModelAssetId());
    }
    static com.hypixel.hytale.protocol.ColorLight lightColour(int colour,double age){
        return lightColour(colour,age,50);
    }
    static com.hypixel.hytale.protocol.ColorLight lightColour(int colour,double age,double seconds){
        double fade=seconds==15?Math.clamp((15-age)/5,0,1):Math.clamp(1-age/seconds,0,1);int rgb=RGB[colour];
        if(colour==9){
            int[] rainbow={0xff4747,0xff913f,0xffdc42,0x5bff95,0x40edff,0x478aff,0xb36aff,0xff4747};
            double[] frames={0,.16,.32,.48,.64,.80,.94,1};double progress=Math.clamp(age/seconds,0,1);int i=0;
            while(i<6&&progress>frames[i+1])i++;
            double t=(progress-frames[i])/(frames[i+1]-frames[i]);rgb=0;
            for(int shift:new int[]{16,8,0})rgb|=((int)Math.round(((rainbow[i]>>shift)&255)*(1-t)+((rainbow[i+1]>>shift)&255)*t))<<shift;
        }
        return new com.hypixel.hytale.protocol.ColorLight((byte)Math.round(24*Math.sqrt(fade)),
            (byte)Math.round(((rgb>>16)&255)*15/255.0*fade),(byte)Math.round(((rgb>>8)&255)*15/255.0*fade),
            (byte)Math.round((rgb&255)*15/255.0*fade));
    }
    static void advance(float dt,Store<EntityStore> store,Runtime data,long now){
        data.signals=data.signals.stream().filter(s->now<s.expiresAt).toList();
        var lights=data.lights.iterator();
        while(lights.hasNext()){
            var light=lights.next();double age=Math.max(0,(now-light.born)/1_000_000_000.0);
            if(!light.visual.isValid()){lights.remove();continue;}
            if(age>=light.seconds){store.removeEntity(light.visual,RemoveReason.REMOVE);lights.remove();continue;}
            var component=store.getComponent(light.visual,DynamicLight.getComponentType());
            if(component!=null){var colour=lightColour(light.colour,age,light.seconds);if(!colour.equals(component.getColorLight()))component.setColorLight(colour);}
        }
        var it=data.flights.iterator();
        while(it.hasNext()){
            var flight=it.next();
            // Flight is authoritative even if the optional model is unloaded at altitude.
            double distance=Math.min(Math.max(dt,0)*SPEED,flight.range-flight.travelled);
            // Burst just inside the build limit, so smoke, light and marker stay in the world.
            double ceilingDistance=flight.direction.y>0?Math.max(0,(CEILING-flight.position.y)/flight.direction.y):Double.POSITIVE_INFINITY;
            boolean ceiling=ceilingDistance<=distance;
            distance=Math.min(distance,ceilingDistance);
            var obstruction=distance>.001?TargetUtil.getTargetLocation(store.getExternalData().getWorld(),id->id!=0,
                flight.position.x,flight.position.y,flight.position.z,flight.direction.x,flight.direction.y,flight.direction.z,distance):null;
            double travelled=obstruction==null?distance:Math.clamp(flight.position.distance(obstruction)-.15,0,distance);
            flight.position.fma(travelled,flight.direction);flight.travelled+=travelled;
            boolean finish=ceiling||obstruction!=null||flight.travelled>=flight.range-.001;
            emitTrail(flight,store,finish);
            if(finish){burst(flight,store,data,now);it.remove();continue;}
            if(flight.visual!=null&&flight.visual.isValid()){
                var transform=store.getComponent(flight.visual,TransformComponent.getComponentType());
                if(transform!=null)transform.setPosition(flight.position);
            }
        }
        var fireworks=data.fireworks.iterator();
        while(fireworks.hasNext()){
            var firework=fireworks.next();if(now<firework.due)continue;
            // Do not restart an expired display after a long server pause.
            if(now<firework.due+15_000_000_000L)showBurst(firework.point,firework.colour,firework.scale,15,store,data,now,"Ember_Signal_Festival_Burst");
            fireworks.remove();
        }
    }
}

package org.darselx.endlessember;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EcsEvent;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.system.ICancellableEcsEvent;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.CraftRecipeEvent;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.function.Predicate;

/** Denies survival use without deleting inventory items or existing crops. */
final class CreativeCropRestrictions {
    private static final String[] CROPS = {"MysticalMountainEye", "OnyxiumHeart", "SpectralBloom"};

    static void register(EndlessEmberPlugin plugin) {
        plugin.getEntityStoreRegistry().registerSystem(new Placement());
        plugin.getEntityStoreRegistry().registerSystem(new Use());
        plugin.getEntityStoreRegistry().registerSystem(new Crafting());
    }

    static boolean isRestrictedItem(String id) {
        if (id == null) return false;
        for (String crop : CROPS) {
            if (id.equals("Plant_Seeds_" + crop) || id.equals("Ingredient_" + crop + "_Pollen")
                    || id.equals("Plant_Crop_" + crop + "_Block")) return true;
        }
        return false;
    }

    static boolean isRestrictedBlock(String id) {
        if (id == null) return false;
        for (String crop : CROPS) {
            if (id.contains("Plant_Crop_" + crop + "_Block")) return true;
        }
        return false;
    }

    private static boolean hasRestrictedItem(MaterialQuantity[] items) {
        if (items == null) return false;
        for (MaterialQuantity item : items) {
            if (item != null && isRestrictedItem(item.getItemId())) return true;
        }
        return false;
    }

    private abstract static class Restriction<E extends EcsEvent & ICancellableEcsEvent>
            extends EntityEventSystem<EntityStore, E> {
        private final Predicate<E> restricted;

        Restriction(Class<E> eventType, Predicate<E> restricted) {
            super(eventType);
            this.restricted = restricted;
        }

        @Override
        public Query<EntityStore> getQuery() { return Player.getComponentType(); }

        @Override
        public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                           CommandBuffer<EntityStore> commandBuffer, E event) {
            Player player = chunk.getComponent(index, Player.getComponentType());
            if (player != null && player.getGameMode() != GameMode.Creative && restricted.test(event)) {
                event.setCancelled(true);
            }
        }
    }

    private static final class Placement extends Restriction<PlaceBlockEvent> {
        Placement() {
            super(PlaceBlockEvent.class, event -> event.getItemInHand() != null
                    && isRestrictedItem(event.getItemInHand().getItemId()));
        }
    }

    private static final class Use extends Restriction<UseBlockEvent.Pre> {
        Use() {
            super(UseBlockEvent.Pre.class, event -> event.getBlockType() != null
                    && isRestrictedBlock(event.getBlockType().getId()));
        }
    }

    private static final class Crafting extends Restriction<CraftRecipeEvent.Pre> {
        Crafting() {
            super(CraftRecipeEvent.Pre.class, event -> hasRestrictedItem(event.getCraftedRecipe().getInput())
                    || hasRestrictedItem(event.getCraftedRecipe().getOutputs()));
        }
    }
}

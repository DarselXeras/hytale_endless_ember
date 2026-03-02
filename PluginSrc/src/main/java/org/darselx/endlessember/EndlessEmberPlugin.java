package org.darselx.endlessember;

import com.hypixel.hytale.protocol.BenchRequirement;
import com.hypixel.hytale.protocol.BenchType;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import org.bson.BsonDocument;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EndlessEmberPlugin extends JavaPlugin {
    private static final String RECIPES_FOLDER = "EmberPressRecipes";
    private static final String EXAMPLE_FILE = "emberpress_wood_trunk_to_charcoal.json";
    private static final String CREATIVE_ONLY_ITEM_ID = "Endless_Ember_Creative";
    private Path recipeDir;

    private static final String EXAMPLE_JSON = """
            {
              "Id": "Custom_EmberPress_WoodToCharcoal",
              "Recipe": {
                "Input": [
                  {
                    "ResourceTypeId": "Wood_Trunk",
                    "Quantity": 1
                  }
                ],
                "Output": [
                  {
                    "ItemId": "Ingredient_Charcoal",
                    "Quantity": 1
                  }
                ],
                "BenchRequirement": [
                  {
                    "Type": "Processing",
                    "Id": "EmberPress"
                  }
                ],
                "TimeSeconds": 10
              }
            }
            """;

    public EndlessEmberPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        try {
            String safePluginFolder = sanitizeFolderName(getName());
            Path base = resolveWorldBasePath();
            recipeDir = base.resolve("mods").resolve(safePluginFolder).resolve(RECIPES_FOLDER);
            Files.createDirectories(recipeDir);

            Path examplePath = recipeDir.resolve(EXAMPLE_FILE);
            if (Files.notExists(examplePath)) {
                Files.writeString(examplePath, EXAMPLE_JSON, StandardCharsets.UTF_8);
                getLogger().at(Level.INFO).log("Endless Ember: created example EmberPress recipe at " + examplePath);
            }

            int loaded = loadCustomRecipes(recipeDir);
            getLogger().at(Level.INFO).log("Endless Ember: loaded " + loaded + " custom EmberPress recipes from " + recipeDir);
            registerCreativeOnlyInventoryGuard();
            registerEndlessEmberCommandSafe();
        } catch (Exception ex) {
            getLogger().at(Level.WARNING).log("Endless Ember: failed during setup: " + ex.getMessage());
        }
    }

    private int loadCustomRecipes(Path recipeDir) throws Exception {
        List<CraftingRecipe> recipes = new ArrayList<>();

        if (Files.notExists(recipeDir)) return 0;

        for (Path file : Files.list(recipeDir).filter(p -> p.toString().toLowerCase().endsWith(".json")).toList()) {
            try {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                ParsedRecipe parsed = parseRecipeJson(json, stripExtension(file.getFileName().toString()));

                if (parsed.input == null || parsed.input.length == 0) {
                    getLogger().at(Level.WARNING).log("Endless Ember: skipped " + file + " (Input > 0 required)");
                    continue;
                }
                if (parsed.output == null || parsed.output.length == 0) {
                    getLogger().at(Level.WARNING).log("Endless Ember: skipped " + file + " (Output missing)");
                    continue;
                }

                BenchRequirement[] req = new BenchRequirement[]{defaultEmberPressRequirement()};
                CraftingRecipe recipe = new CraftingRecipe(
                        parsed.input,
                        parsed.output[0],
                        parsed.output,
                        parsed.output[0].getQuantity(),
                        req,
                        parsed.timeSeconds,
                        false,
                        1
                );
                setRecipeId(recipe, parsed.id);
                recipes.add(recipe);
            } catch (Exception ex) {
                getLogger().at(Level.WARNING).log("Endless Ember: failed to parse " + file + " -> " + ex.getMessage());
            }
        }

        if (!recipes.isEmpty()) {
            invokeLoadAssets(recipes);
        }

        return recipes.size();
    }

    private ParsedRecipe parseRecipeJson(String json, String fallbackId) {
        String id = extractString(json, "Id", fallbackId);
        float timeSeconds = extractFloat(json, "TimeSeconds", 10.0f);

        MaterialQuantity[] input = new MaterialQuantity[]{
                new MaterialQuantity(
                        extractNullableString(json, "Input", "ItemId"),
                        extractNullableString(json, "Input", "ResourceTypeId"),
                        extractNullableString(json, "Input", "ItemTag"),
                        Math.max(1, extractInt(json, "Input", "Quantity", 1)),
                        (BsonDocument) null
                )
        };

        MaterialQuantity[] output = new MaterialQuantity[]{
                new MaterialQuantity(
                        extractNullableString(json, "Output", "ItemId"),
                        extractNullableString(json, "Output", "ResourceTypeId"),
                        extractNullableString(json, "Output", "ItemTag"),
                        Math.max(1, extractInt(json, "Output", "Quantity", 1)),
                        (BsonDocument) null
                )
        };

        if (input[0].getItemId() == null && input[0].getResourceTypeId() == null) {
            input = new MaterialQuantity[0];
        }
        if (output[0].getItemId() == null && output[0].getResourceTypeId() == null) {
            output = new MaterialQuantity[0];
        }

        return new ParsedRecipe(id, input, output, timeSeconds);
    }

    private String extractString(String json, String key, String def) {
        Matcher m = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(json);
        return m.find() ? m.group(1) : def;
    }

    private String extractNullableString(String json, String section, String key) {
        Matcher sectionMatcher = Pattern.compile("\\\"" + Pattern.quote(section) + "\\\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL).matcher(json);
        if (!sectionMatcher.find()) return null;
        String block = sectionMatcher.group(1);
        Matcher m = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(block);
        return m.find() ? m.group(1) : null;
    }

    private int extractInt(String json, String section, String key, int def) {
        Matcher sectionMatcher = Pattern.compile("\\\"" + Pattern.quote(section) + "\\\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL).matcher(json);
        if (!sectionMatcher.find()) return def;
        String block = sectionMatcher.group(1);
        Matcher m = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*([0-9]+)").matcher(block);
        return m.find() ? Integer.parseInt(m.group(1)) : def;
    }

    private float extractFloat(String json, String key, float def) {
        Matcher m = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)").matcher(json);
        return m.find() ? Float.parseFloat(m.group(1)) : def;
    }

    private BenchRequirement defaultEmberPressRequirement() {
        BenchRequirement b = new BenchRequirement();
        b.type = BenchType.Processing;
        b.id = "EmberPress";
        b.requiredTierLevel = 0;
        return b;
    }

    private void setRecipeId(CraftingRecipe recipe, String id) throws Exception {
        Field f = CraftingRecipe.class.getDeclaredField("id");
        f.setAccessible(true);
        f.set(recipe, id);
    }

    private void invokeLoadAssets(List<CraftingRecipe> recipes) throws Exception {
        Object store = CraftingRecipe.getAssetStore();
        for (var m : store.getClass().getMethods()) {
            if (!m.getName().equals("loadAssets")) continue;
            Class<?>[] p = m.getParameterTypes();
            try {
                if (p.length == 2) {
                    m.invoke(store, getName(), recipes);
                    return;
                }
                if (p.length == 4) {
                    m.invoke(store, getName(), recipes, null, Boolean.FALSE);
                    return;
                }
                if (p.length == 3) {
                    m.invoke(store, getName(), recipes, null);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        throw new NoSuchMethodException("No compatible loadAssets overload found on " + store.getClass().getName());
    }

    private void registerEndlessEmberCommandSafe() {
        Object registry = getCommandRegistry();
        EndlessEmberCommand command = new EndlessEmberCommand(this);

        for (var m : registry.getClass().getMethods()) {
            String n = m.getName();
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 1) continue;
            if (!(n.toLowerCase().contains("register") && n.toLowerCase().contains("command"))) continue;
            try {
                if (p[0].isAssignableFrom(command.getClass())) {
                    m.invoke(registry, command);
                    getLogger().at(Level.INFO).log("Endless Ember: command registered via " + n + "(" + p[0].getSimpleName() + ")");
                    return;
                }
            } catch (Throwable ignored) {
            }
        }

        getLogger().at(Level.WARNING).log("Endless Ember: could not register command (/endlessember...) on this server build.");
    }

    private void registerCreativeOnlyInventoryGuard() {
        try {
            Class<?> eventClass = Class.forName("com.hypixel.hytale.server.core.event.events.entity.LivingEntityInventoryChangeEvent");
            Object eventRegistry = this.getClass().getMethod("getEventRegistry").invoke(this);
            java.util.function.Consumer<Object> handler = this::onInventoryChangeReflective;

            for (var m : eventRegistry.getClass().getMethods()) {
                if (!m.getName().equals("registerGlobal")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 2) continue;
                if (!Class.class.isAssignableFrom(p[0])) continue;
                if (!java.util.function.Consumer.class.isAssignableFrom(p[1])) continue;
                m.invoke(eventRegistry, eventClass, handler);
                getLogger().at(Level.INFO).log("Endless Ember: Creative-only inventory guard enabled for " + CREATIVE_ONLY_ITEM_ID);
                return;
            }

            getLogger().at(Level.WARNING).log("Endless Ember: could not hook inventory guard (registerGlobal overload not found).");
        } catch (Throwable t) {
            getLogger().at(Level.WARNING).log("Endless Ember: failed to enable inventory guard: " + t.getMessage());
        }
    }

    private void onInventoryChangeReflective(Object event) {
        try {
            Object entity = event.getClass().getMethod("getEntity").invoke(event);
            if (entity == null) return;

            // Only handle players; avoid hard dependency on Player class at compile time.
            Class<?> playerClass = Class.forName("com.hypixel.hytale.server.core.entity.entities.Player");
            if (!playerClass.isInstance(entity)) return;

            Object gameMode = entity.getClass().getMethod("getGameMode").invoke(entity);
            if (gameMode != null && "Creative".equals(String.valueOf(gameMode))) return;

            Object inventory = entity.getClass().getMethod("getInventory").invoke(entity);
            Object combined = inventory.getClass().getMethod("getCombinedHotbarFirst").invoke(inventory);
            short capacity = ((Number) combined.getClass().getMethod("getCapacity").invoke(combined)).shortValue();

            Class<?> itemStackClass = Class.forName("com.hypixel.hytale.server.core.inventory.ItemStack");
            var isEmptyMethod = itemStackClass.getMethod("isEmpty", itemStackClass);
            var getItemIdMethod = itemStackClass.getMethod("getItemId");

            for (short slot = 0; slot < capacity; slot++) {
                Object stack = combined.getClass().getMethod("getItemStack", short.class).invoke(combined, slot);
                boolean empty = (Boolean) isEmptyMethod.invoke(null, stack);
                if (empty) continue;

                String itemId = (String) getItemIdMethod.invoke(stack);
                if (CREATIVE_ONLY_ITEM_ID.equals(itemId)) {
                    combined.getClass().getMethod("removeItemStackFromSlot", short.class).invoke(combined, slot);
                }
            }
        } catch (Throwable ignored) {
            // Silent by design to avoid log spam on high-frequency inventory updates.
        }
    }

    private String stripExtension(String filename) {
        int i = filename.lastIndexOf('.');
        return i > 0 ? filename.substring(0, i) : filename;
    }

    public int reloadEmberPressRecipes() throws Exception {
        if (recipeDir == null) {
            String safePluginFolder = sanitizeFolderName(getName());
            Path base = resolveWorldBasePath();
            recipeDir = base.resolve("mods").resolve(safePluginFolder).resolve(RECIPES_FOLDER);
        }
        Files.createDirectories(recipeDir);
        return loadCustomRecipes(recipeDir);
    }

    @Override
    protected void shutdown() {
        // no-op
    }

    private Path resolveWorldBasePath() {
        String fromProperty = System.getProperty("hytale.world.path");
        if (fromProperty != null && !fromProperty.trim().isEmpty()) {
            return Path.of(fromProperty.trim());
        }
        return Path.of(System.getProperty("user.dir"));
    }

    private String sanitizeFolderName(String input) {
        if (input == null || input.isBlank()) return "DarselX_EndlessEmber";
        return input.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private record ParsedRecipe(String id, MaterialQuantity[] input, MaterialQuantity[] output, float timeSeconds) {}
}

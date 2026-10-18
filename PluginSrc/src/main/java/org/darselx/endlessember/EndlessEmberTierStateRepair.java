package org.darselx.endlessember;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Repairs the server-side state graph after asset loading while retaining the
 * original acyclic block packets for clients.
 */
public final class EndlessEmberTierStateRepair {
    private static final String[] BENCH_IDS = {"Ember_Tinkerbench", "Ember_Weaponbench", "Ember_Ammunitionbench"};
    private static final String BLOCK_TYPE_CLASS =
            "com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType";
    private static final String STATE_DATA_CLASS =
            "com.hypixel.hytale.server.core.asset.type.blocktype.config.StateData";
    private static final String[] TIERS = {"Tier2", "Tier3", "Tier4"};
    private static final String[] CRAFT_STATES = {"CraftCompleted", "CraftCompletedInstant"};
    private static final List<Object> PACKET_GUARD = new ArrayList<>();

    private EndlessEmberTierStateRepair() {
    }

    public static void apply(Object plugin) {
        for (String benchId : BENCH_IDS) {
            try {
                repair(benchId);
                log(plugin, Level.INFO,
                        "Endless Ember: repaired " + benchId + " tier-state reset graph");
            } catch (Throwable error) {
                log(plugin, Level.WARNING,
                        "Endless Ember: failed to repair " + benchId + " tier states: " + error);
            }
        }
    }

    private static void repair(String benchId) throws Exception {
        Class<?> blockTypeClass = Class.forName(BLOCK_TYPE_CLASS);
        Class<?> stateDataClass = Class.forName(STATE_DATA_CLASS);
        Object assetMap = blockTypeClass.getMethod("getAssetMap").invoke(null);
        Object root = invoke(assetMap, "getAsset", benchId);
        Object rootState = root == null ? null : invoke(root, "getState");
        if (root == null || rootState == null) {
            throw new IllegalStateException("Missing block or root state: " + benchId);
        }

        // Cache the JSON-defined packets first. The following self targets remain
        // server-only and therefore cannot create duplicate client block keys.
        primePacket(root);
        for (String craftState : CRAFT_STATES) {
            primePacket(getBlockForState(root, craftState));
        }
        for (String tierName : TIERS) {
            Object tierBlock = getBlockForState(root, tierName);
            Object tierState = tierBlock == null ? null : invoke(tierBlock, "getState");
            if (tierBlock == null || tierState == null) {
                throw new IllegalStateException("Missing block or crafting states: " + tierName);
            }
            primePacket(tierBlock);
            for (String craftState : CRAFT_STATES) {
                primePacket(getBlockForState(tierBlock, craftState));
            }
        }

        for (String craftState : CRAFT_STATES) {
            assignState(blockTypeClass, getBlockForState(root, craftState), rootState);
        }

        Field stateToBlockField = declaredField(stateDataClass, "stateToBlock");
        Field blockToStateField = declaredField(stateDataClass, "blockToState");

        // Hytale applies upgrades from the root block. A tier block therefore only
        // needs its own reset target to survive BenchWindow.onClose0().
        for (String tierName : TIERS) {
            Object tierBlock = getBlockForState(root, tierName);
            Object tierState = invoke(tierBlock, "getState");
            String tierBlockKey = (String) invoke(rootState, "getBlockForState", tierName);
            if (tierBlockKey == null) {
                throw new IllegalStateException("Missing root target for " + tierName);
            }

            addStateTarget(stateToBlockField, blockToStateField,
                    tierState, tierName, tierBlockKey);
            for (String craftState : CRAFT_STATES) {
                assignState(blockTypeClass, getBlockForState(tierBlock, craftState), tierState);
            }
        }
    }

    private static Object getBlockForState(Object block, String state) throws Exception {
        return block == null ? null : invoke(block, "getBlockForState", state);
    }

    private static void primePacket(Object block) throws Exception {
        if (block != null) {
            // BlockType caches packets in a SoftReference. Keep the safe packet
            // strongly reachable after mutating the server-only state graph.
            PACKET_GUARD.add(invoke(block, "toPacket"));
        }
    }

    private static void assignState(Class<?> blockTypeClass, Object block, Object state)
            throws Exception {
        if (block == null) {
            return;
        }
        declaredField(blockTypeClass, "state").set(block, state);
    }

    @SuppressWarnings("unchecked")
    private static void addStateTarget(Field stateToBlockField, Field blockToStateField,
                                       Object stateData, String stateName, String blockKey)
            throws Exception {
        Map<String, String> existing =
                (Map<String, String>) stateToBlockField.get(stateData);
        Map<String, String> stateToBlock = new LinkedHashMap<>();
        if (existing != null) {
            stateToBlock.putAll(existing);
        }
        stateToBlock.put(stateName, blockKey);
        stateToBlockField.set(stateData, stateToBlock);

        Map<String, String> blockToState = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : stateToBlock.entrySet()) {
            blockToState.put(entry.getValue(), entry.getKey());
        }
        blockToStateField.set(stateData, Collections.unmodifiableMap(blockToState));
    }

    private static Field declaredField(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Object invoke(Object target, String name, Object... args) throws Exception {
        if (target == null) {
            throw new IllegalStateException("Cannot call " + name + " on null");
        }
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            boolean compatible = true;
            for (int i = 0; i < parameterTypes.length; i++) {
                if (args[i] != null && !parameterTypes[i].isInstance(args[i])) {
                    compatible = false;
                    break;
                }
            }
            if (compatible) {
                method.setAccessible(true);
                return method.invoke(target, args);
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }

    private static void log(Object plugin, Level level, String message) {
        try {
            Object logger = invoke(plugin, "getLogger");
            Object api = invoke(logger, "at", level);
            invoke(api, "log", message);
        } catch (Throwable ignored) {
            System.out.println(message);
        }
    }
}

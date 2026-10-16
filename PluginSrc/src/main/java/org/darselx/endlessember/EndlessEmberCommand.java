package org.darselx.endlessember;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;

public class EndlessEmberCommand extends AbstractCommandCollection {
    public EndlessEmberCommand(EndlessEmberPlugin plugin) {
        super("endlessember", "Endless Ember commands");
        this.addSubCommand(new EmberPressCommand(plugin));
        this.addSubCommand(new ParticlesCommand(plugin));
    }

    private static class ParticlesCommand extends CommandBase {
        private final EndlessEmberPlugin plugin;
        private final com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg<String> mode;

        ParticlesCommand(EndlessEmberPlugin plugin) {
            super("particles", "Plant particles: full, reduced, off, or status. Changes apply after restart.");
            this.plugin = plugin;
            mode = withRequiredArg("mode", "full, reduced, off, or status",
                    com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes.STRING);
        }

        @Override
        protected void executeSync(CommandContext ctx) {
            String value = mode.get(ctx);
            if ("status".equalsIgnoreCase(value)) {
                ctx.sendMessage(Message.raw("[Endless Ember] Plant particles: " + plugin.plantParticleStatus()));
                return;
            }
            try {
                plugin.savePlantParticleMode(value);
                ctx.sendMessage(Message.raw("[Endless Ember] Plant particles saved: "
                        + PlantParticleConfig.parse(value) + ". Restart the world/server to apply."));
            } catch (Exception error) {
                ctx.sendMessage(Message.raw("[Endless Ember] Could not save particle setting: " + error.getMessage()));
            }
        }
    }

    private static class EmberPressCommand extends AbstractCommandCollection {
        public EmberPressCommand(EndlessEmberPlugin plugin) {
            super("emberpress", "EmberPress commands");
            this.addSubCommand(new ReloadCommand(plugin));
        }
    }

    private static class ReloadCommand extends CommandBase {
        private final EndlessEmberPlugin plugin;

        public ReloadCommand(EndlessEmberPlugin plugin) {
            super("reload", "Reload EmberPress custom recipes");
            this.plugin = plugin;
        }

        @Override
        protected void executeSync(CommandContext ctx) {
            try {
                int loaded = plugin.reloadEmberPressRecipes();
                ctx.sendMessage(Message.raw("[Endless Ember] Reload complete. Loaded " + loaded + " custom EmberPress recipe(s)."));
            } catch (Exception ex) {
                ctx.sendMessage(Message.raw("[Endless Ember] Reload failed: " + ex.getMessage()));
            }
        }
    }
}

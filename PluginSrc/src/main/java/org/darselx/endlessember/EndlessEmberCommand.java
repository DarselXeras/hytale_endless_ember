package org.darselx.endlessember;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;

public class EndlessEmberCommand extends AbstractCommandCollection {
    public EndlessEmberCommand(EndlessEmberPlugin plugin) {
        super("endlessember", "Endless Ember commands");
        this.addSubCommand(new EmberPressCommand(plugin));
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

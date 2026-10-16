package org.darselx.endlessember;

import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

public final class EndlessEmberTierStateFixPlugin extends EndlessEmberPlugin {
    public EndlessEmberTierStateFixPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void start() {
        super.start();
        EndlessEmberTierStateRepair.apply(this);
    }
}

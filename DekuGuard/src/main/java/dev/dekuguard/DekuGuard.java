package dev.dekuguard;

import org.bukkit.plugin.java.JavaPlugin;

public final class DekuGuard extends JavaPlugin {

    private ViolationManager violations;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        violations = new ViolationManager(this);

        getServer().getPluginManager().registerEvents(new MovementListener(this, violations), this);
        getServer().getPluginManager().registerEvents(new CombatListener(this, violations), this);

        DekuGuardCommand cmd = new DekuGuardCommand(this, violations);
        getCommand("dekuguard").setExecutor(cmd);
        getCommand("dekuguard").setTabCompleter(cmd);

        getLogger().info("DekuGuard enabled.");
    }

    @Override
    public void onDisable() {
        if (violations != null) violations.shutdown();
        getLogger().info("DekuGuard disabled.");
    }
}

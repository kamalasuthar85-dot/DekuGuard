package dev.dekuguard;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ViolationManager {

    private final DekuGuard plugin;
    private final Map<UUID, PlayerData> data = new ConcurrentHashMap<>();
    private final Set<UUID> alertsOff = ConcurrentHashMap.newKeySet();
    private final FileLog fileLog;

    public ViolationManager(DekuGuard plugin) {
        this.plugin = plugin;
        this.fileLog = new FileLog(new File(plugin.getDataFolder(), "violations.log"));
    }

    public void shutdown() {
        fileLog.close();
    }

    public FileConfiguration cfg() {
        return plugin.getConfig();
    }

    public PlayerData get(Player p) {
        return data.computeIfAbsent(p.getUniqueId(), id -> new PlayerData());
    }

    public void remove(UUID id) {
        data.remove(id);
        alertsOff.remove(id);
    }

    public boolean enabled(String check) {
        return cfg().getBoolean("checks." + check + ".enabled", true);
    }

    public double num(String check, String key, double def) {
        return cfg().getDouble("checks." + check + "." + key, def);
    }

    public boolean worldDisabled(World w) {
        return cfg().getStringList("general.disabled-worlds").contains(w.getName());
    }

    public boolean toggleAlerts(UUID id) {
        if (alertsOff.remove(id)) return true;
        alertsOff.add(id);
        return false;
    }

    /**
     * Register a violation. Returns the player's new vl for that check.
     * Handles decay, staff alerts, logging and punishment.
     */
    public double flag(Player p, String check, double add, String info) {
        if (p.hasPermission("dekuguard.bypass")) return 0;

        String key = check.toLowerCase();
        PlayerData d = get(p);
        long now = System.currentTimeMillis();

        double decaySec = Math.max(1, cfg().getDouble("general.decay-seconds", 30));
        double cur = d.vl.getOrDefault(key, 0.0);
        long last = d.vlTime.getOrDefault(key, now);
        cur = Math.max(0, cur - (now - last) / 1000.0 / decaySec);
        cur += add;
        d.vl.put(key, cur);
        d.vlTime.put(key, now);

        String vlText = String.format("%.1f", cur);

        if (cfg().getBoolean("general.log-to-file", true)) {
            fileLog.log(p.getName() + " " + check + " vl=" + vlText + " ping=" + p.getPing() + " " + info);
        }

        long cooldown = cfg().getLong("general.alert-cooldown-ms", 1000);
        long lastAlert = d.lastAlert.getOrDefault(key, 0L);
        if (now - lastAlert >= cooldown) {
            d.lastAlert.put(key, now);
            sendAlert(p, check, vlText, info);
        }

        double punishAt = cfg().getDouble("checks." + key + ".punish-vl", 10);
        if (cur >= punishAt) {
            d.vl.put(key, 0.0);
            runPunishment(p, check);
        }
        return cur;
    }

    private void sendAlert(Player p, String check, String vlText, String info) {
        String msg = ChatColor.RED + "[DekuGuard] " + ChatColor.WHITE + p.getName()
                + ChatColor.GRAY + " failed " + ChatColor.RED + check
                + ChatColor.GRAY + " (vl " + vlText + ") " + ChatColor.DARK_GRAY + info;

        // Clickable: staff click an alert to teleport to the player.
        BaseComponent[] parts = TextComponent.fromLegacyText(msg);
        for (BaseComponent part : parts) {
            part.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tp " + p.getName()));
            part.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new Text("Click to teleport to " + p.getName() + " (ping " + p.getPing() + "ms)")));
        }
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("dekuguard.alerts") && !alertsOff.contains(staff.getUniqueId())) {
                staff.spigot().sendMessage(parts);
            }
        }
        if (cfg().getBoolean("general.log-to-console", true)) {
            plugin.getLogger().info(ChatColor.stripColor(msg));
        }
    }

    private void runPunishment(Player p, String check) {
        List<String> commands = cfg().getStringList("general.punish-commands");
        for (String raw : commands) {
            final String cmd = ChatColor.translateAlternateColorCodes('&',
                    raw.replace("%player%", p.getName()).replace("%check%", check));
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
        }
    }
}

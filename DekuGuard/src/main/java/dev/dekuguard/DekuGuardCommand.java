package dev.dekuguard;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DekuGuardCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = ChatColor.RED + "[DekuGuard] " + ChatColor.GRAY;

    private final DekuGuard plugin;
    private final ViolationManager vm;

    public DekuGuardCommand(DekuGuard plugin, ViolationManager vm) {
        this.plugin = plugin;
        this.vm = vm;
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 0) {
            s.sendMessage(PREFIX + "/dg reload | alerts | vl <player> | reset <player>");
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "reload" -> {
                plugin.reloadConfig();
                s.sendMessage(PREFIX + "Config reloaded.");
            }
            case "alerts" -> {
                if (!(s instanceof Player p)) {
                    s.sendMessage(PREFIX + "Players only.");
                    return true;
                }
                boolean on = vm.toggleAlerts(p.getUniqueId());
                s.sendMessage(PREFIX + "Alerts " + (on ? "enabled" : "disabled") + ".");
            }
            case "vl" -> {
                if (a.length < 2) {
                    s.sendMessage(PREFIX + "/dg vl <player>");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) {
                    s.sendMessage(PREFIX + "Player not online.");
                    return true;
                }
                PlayerData d = vm.get(t);
                if (d.vl.isEmpty()) {
                    s.sendMessage(PREFIX + t.getName() + " has no violations.");
                    return true;
                }
                s.sendMessage(PREFIX + "Violations for " + t.getName() + ":");
                for (Map.Entry<String, Double> en : d.vl.entrySet()) {
                    s.sendMessage(ChatColor.GRAY + " - " + ChatColor.WHITE + en.getKey()
                            + ChatColor.GRAY + ": " + String.format("%.1f", en.getValue()));
                }
            }
            case "reset" -> {
                if (a.length < 2) {
                    s.sendMessage(PREFIX + "/dg reset <player>");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) {
                    s.sendMessage(PREFIX + "Player not online.");
                    return true;
                }
                vm.get(t).clearViolations();
                s.sendMessage(PREFIX + "Cleared violations for " + t.getName() + ".");
            }
            default -> s.sendMessage(PREFIX + "Unknown subcommand.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            for (String o : List.of("reload", "alerts", "vl", "reset")) {
                if (o.startsWith(a[0].toLowerCase())) out.add(o);
            }
        } else if (a.length == 2 && (a[0].equalsIgnoreCase("vl") || a[0].equalsIgnoreCase("reset"))) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(a[1].toLowerCase())) out.add(p.getName());
            }
        }
        return out;
    }
}

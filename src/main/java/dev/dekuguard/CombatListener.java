package dev.dekuguard;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * Combat checks: Reach, KillAura (angle + multi-target), NoSwing, Criticals, AutoClicker.
 */
public class CombatListener implements Listener {

    private final DekuGuard plugin;
    private final ViolationManager vm;

    public CombatListener(DekuGuard plugin, ViolationManager vm) {
        this.plugin = plugin;
        this.vm = vm;
    }

    // ---------------------------------------------------------------- hits

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (e.getCause() != DamageCause.ENTITY_ATTACK) return; // skip sweeps, thorns, projectiles
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return;
        if (p.hasPermission("dekuguard.bypass") || vm.worldDisabled(p.getWorld())) return;

        Entity target = e.getEntity();
        PlayerData d = vm.get(p);
        long now = System.currentTimeMillis();
        boolean cancel = false;

        // Closest point of the target's hitbox to the attacker's eyes.
        Vector eye = p.getEyeLocation().toVector();
        BoundingBox box = target.getBoundingBox().expand(0.05);
        Vector nearest = new Vector(
                clamp(eye.getX(), box.getMinX(), box.getMaxX()),
                clamp(eye.getY(), box.getMinY(), box.getMaxY()),
                clamp(eye.getZ(), box.getMinZ(), box.getMaxZ()));
        double dist = eye.distance(nearest);

        // ---------------- Reach (blatant overshoots flag at once, borderline ones are buffered)
        if (vm.enabled("reach")) {
            double ping = Math.min(Math.max(p.getPing(), 0), 250);
            double max = vm.num("reach", "max-distance", 3.1) + ping * vm.num("reach", "ping-leniency", 0.0015);
            if (target instanceof Player) max += vm.num("reach", "player-target-leniency", 0.1);
            boolean over = dist > max;
            boolean blatant = dist > max + vm.num("reach", "blatant-extra", 0.5);
            double buf = d.buffer("reach", over, 1, 0.25);
            if (blatant || (over && buf >= vm.num("reach", "buffer", 2))) {
                vm.flag(p, "Reach", 1, String.format("%.2f > %.2f blocks", dist, max));
                d.setBuffer("reach", 0);
                cancel = true;
            } else if (over) {
                cancel = true; // still deny the hit, just do not count it yet
            }
        }

        // ---------------- KillAura: hit a target that is not under the crosshair
        if (vm.enabled("killaura") && dist > 1.0) {
            Vector toTarget = nearest.clone().subtract(eye);
            double angle = Math.toDegrees(p.getEyeLocation().getDirection().angle(toTarget));
            boolean bad = angle > vm.num("killaura", "max-angle", 55);
            double buf = d.buffer("killaura", bad, 1, 0.5);
            if (bad && buf >= vm.num("killaura", "buffer", 3)) {
                vm.flag(p, "KillAura", 1, String.format("angle %.0f deg dist %.2f", angle, dist));
                d.setBuffer("killaura", 0);
                cancel = true;
            }
        }

        // ---------------- Multi-aura: two different targets inside one tick
        if (vm.enabled("multiaura")) {
            long window = (long) vm.num("multiaura", "window-ms", 40);
            if (d.lastTarget != null && !d.lastTarget.equals(target.getUniqueId())
                    && now - d.lastHitTime < window) {
                vm.flag(p, "MultiAura", 1, "2 targets in " + (now - d.lastHitTime) + "ms");
                cancel = true;
            }
        }
        d.lastTarget = target.getUniqueId();
        d.lastHitTime = now;

        // ---------------- Criticals: server thinks it is a crit, yet the player has barely left the ground
        if (vm.enabled("criticals")) {
            boolean critState = p.getFallDistance() > 0 && !p.isOnGround() && !p.isSprinting()
                    && !p.isGliding() && !p.isFlying() && !p.getAllowFlight() && !p.isInsideVehicle();
            boolean calm = now - d.lastSpecial > 1500 && now - d.lastExempt > 1500 && now - d.lastVelocity > 1500;
            boolean bad = critState && calm && d.airTicks < (int) vm.num("criticals", "min-air-ticks", 3);
            double buf = d.buffer("criticals", bad, 1, 1);
            if (bad && buf >= vm.num("criticals", "buffer", 3)) {
                vm.flag(p, "Criticals", 1, "crit after " + d.airTicks + " air ticks");
                d.setBuffer("criticals", 0);
                cancel = true;
            }
        }

        // ---------------- NoSwing: the arm-swing packet follows the attack within a few ticks
        if (vm.enabled("noswing")) {
            final long hitTime = now;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) return;
                PlayerData pd = vm.get(p);
                boolean bad = pd.lastSwing < hitTime - 50;
                double buf = pd.buffer("noswing", bad, 1, 0.5);
                if (bad && buf >= vm.num("noswing", "buffer", 2)) {
                    vm.flag(p, "NoSwing", 1, "attack without arm swing");
                    pd.setBuffer("noswing", 0);
                }
            }, 4L);
        }

        if (cancel && vm.cfg().getBoolean("combat.cancel-flagged-hits", true)) {
            e.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------- clicks

    @EventHandler
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        Player p = e.getPlayer();
        PlayerData d = vm.get(p);
        long now = System.currentTimeMillis();
        d.lastSwing = now;

        if (!vm.enabled("autoclicker") || p.hasPermission("dekuguard.bypass") || vm.worldDisabled(p.getWorld())) return;

        // CPS over a sliding 1 second window
        d.clicks.addLast(now);
        while (!d.clicks.isEmpty() && now - d.clicks.peekFirst() > 1000) d.clicks.pollFirst();
        int cps = d.clicks.size();
        if (cps > vm.num("autoclicker", "max-cps", 22)) {
            vm.flag(p, "AutoClicker", 1, "cps " + cps);
        }

        // Consistency: humans jitter, macros don't
        if (d.lastClick != 0) {
            long gap = now - d.lastClick;
            if (gap < 1000) {
                d.intervals.addLast(gap);
                int samples = (int) vm.num("autoclicker", "consistency-samples", 30);
                while (d.intervals.size() > samples) d.intervals.pollFirst();
                if (d.intervals.size() >= samples) {
                    double mean = 0;
                    for (long v : d.intervals) mean += v;
                    mean /= d.intervals.size();
                    double var = 0;
                    for (long v : d.intervals) var += (v - mean) * (v - mean);
                    double std = Math.sqrt(var / d.intervals.size());
                    // mean < 150ms excludes normal block-mining swings (~200ms apart)
                    if (mean < 150 && std < vm.num("autoclicker", "consistency-stddev-ms", 6)) {
                        vm.flag(p, "AutoClicker", 1, String.format("stddev %.1fms avg %.0fms", std, mean));
                        d.intervals.clear();
                    }
                }
            } else {
                d.intervals.clear(); // a long pause starts a fresh sample
            }
        }
        d.lastClick = now;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}

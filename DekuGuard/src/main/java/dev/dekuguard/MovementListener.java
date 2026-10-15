package dev.dekuguard;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.Set;

/**
 * Movement checks: Speed, Fly, Step/HighJump, NoFall, Jesus, Timer, plus anti-knockback (Velocity).
 *
 * Fly compares each airborne tick to vanilla gravity, so it catches hovering, gliding and upward
 * flight without trusting the client's onGround flag.
 */
public class MovementListener implements Listener {

    private static final Set<Material> SPECIAL = Set.of(
            Material.WATER, Material.LAVA, Material.LADDER, Material.VINE, Material.SCAFFOLDING,
            Material.COBWEB, Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW, Material.HONEY_BLOCK,
            Material.BUBBLE_COLUMN, Material.TWISTING_VINES, Material.TWISTING_VINES_PLANT,
            Material.WEEPING_VINES, Material.WEEPING_VINES_PLANT, Material.CAVE_VINES,
            Material.CAVE_VINES_PLANT, Material.SEAGRASS, Material.TALL_SEAGRASS, Material.KELP,
            Material.KELP_PLANT, Material.SLIME_BLOCK, Material.MOVING_PISTON, Material.PISTON_HEAD);

    private static final Set<Material> ICE = Set.of(
            Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE, Material.FROSTED_ICE);

    private final DekuGuard plugin;
    private final ViolationManager vm;

    public MovementListener(DekuGuard plugin, ViolationManager vm) {
        this.plugin = plugin;
        this.vm = vm;
    }

    // ---------------------------------------------------------------- state tracking

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        PlayerData d = vm.get(e.getPlayer());
        resetState(d, e.getPlayer().getLocation());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        vm.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent e) {
        resetState(vm.get(e.getPlayer()), e.getTo());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        resetState(vm.get(e.getPlayer()), e.getRespawnLocation());
    }

    @EventHandler
    public void onVehicleExit(VehicleExitEvent e) {
        if (e.getExited() instanceof Player p) {
            vm.get(p).lastExempt = System.currentTimeMillis();
        }
    }

    private void resetState(PlayerData d, Location at) {
        d.lastTeleport = System.currentTimeMillis();
        d.airTicks = 0;
        d.liquidTicks = 0;
        d.lastDeltaY = 0;
        d.prevGap = true;
        d.timerBalance = -vm.num("timer", "max-credit-ms", 400);
        d.lastGround = at;
        d.lastOnGround = at != null && nearGround(at);
    }

    // ---------------------------------------------------------------- anti-knockback

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent e) {
        Player p = e.getPlayer();
        PlayerData d = vm.get(p);
        final long now = System.currentTimeMillis();
        d.lastVelocity = now;

        if (!vm.enabled("velocity") || vm.worldDisabled(p.getWorld()) || p.hasPermission("dekuguard.bypass")) return;
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return;
        if (p.getAllowFlight() || p.isInsideVehicle() || p.isGliding() || p.isDead()) return;

        Vector v = e.getVelocity();
        final double hv = Math.hypot(v.getX(), v.getZ());
        if (hv < vm.num("velocity", "min-velocity", 0.25)) return;

        final Location start = p.getLocation();
        if (touchesSpecial(start)) return;
        final Vector dir = new Vector(v.getX() / hv, 0, v.getZ() / hv);
        if (blocked(start, dir)) return;

        final int seq = ++d.kbSeq;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || p.isDead() || d.kbSeq != seq || d.lastTeleport > now) return;
            Location end = p.getLocation();
            if (end.getWorld() != start.getWorld() || touchesSpecial(end)) return;

            double moved = Math.hypot(end.getX() - start.getX(), end.getZ() - start.getZ());
            boolean bad = moved < hv * vm.num("velocity", "min-ratio", 0.5);
            double buf = d.buffer("velocity", bad, 1, 1);
            if (bad && buf >= vm.num("velocity", "buffer", 3)) {
                vm.flag(p, "Velocity", 1, String.format("moved %.2f of expected push %.2f", moved, hv));
                d.setBuffer("velocity", 0);
            }
        }, 5L);
    }

    /** True if a solid block sits in the push direction, which would legitimately absorb the knockback. */
    private boolean blocked(Location start, Vector dir) {
        for (double dist : new double[]{0.7, 1.2}) {
            Location probe = start.clone().add(dir.getX() * dist, 0, dir.getZ() * dist);
            if (probe.getBlock().getType().isSolid()) return true;
            if (probe.clone().add(0, 1, 0).getBlock().getType().isSolid()) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- main move handler

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null || from.getWorld() != to.getWorld()) return;

        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        if (dx == 0 && dy == 0 && dz == 0) return; // head rotation only
        if (vm.worldDisabled(p.getWorld())) return;

        PlayerData d = vm.get(p);
        long now = System.currentTimeMillis();
        long dt = d.lastMove == 0 ? 50 : now - d.lastMove;
        d.lastMove = now;
        double lastDy = d.lastDeltaY;
        d.lastDeltaY = dy;

        boolean groundFrom = d.lastOnGround;
        boolean groundTo = nearGround(to);
        d.lastOnGround = groundTo;

        if (isExempt(p, d, now)) {
            d.airTicks = 0;
            d.liquidTicks = 0;
            d.prevGap = true;
            d.timerBalance = -vm.num("timer", "max-credit-ms", 400);
            if (groundTo) d.lastGround = to.clone();
            return;
        }

        // Pause while the server is lagging: our timing and gravity maths assume real ticks.
        try {
            if (Bukkit.getTPS()[0] < vm.cfg().getDouble("general.min-tps", 18.0)) {
                d.prevGap = true;
                if (groundTo) d.lastGround = to.clone();
                return;
            }
        } catch (Throwable ignored) {
            // No TPS API on this server: carry on.
        }

        boolean gap = dt > 400 || d.prevGap;
        d.prevGap = dt > 400;

        boolean special = touchesSpecial(to) || touchesSpecial(from) || bounceBlockBelow(to);
        if (special) d.lastSpecial = now;
        boolean recentSpecial = now - d.lastSpecial < 1200;
        boolean ceiling = to.clone().add(0, 2.0, 0).getBlock().getType().isSolid();
        boolean levitation = effectLevel(p, "LEVITATION") > 0;
        boolean slowFall = effectLevel(p, "SLOW_FALLING") > 0;
        Material under = to.clone().subtract(0, 0.5, 0).getBlock().getType();
        if (ICE.contains(under)) d.lastIce = now;

        boolean flagged = false;
        double horiz = Math.hypot(dx, dz);

        // ---------------- Timer: sending more movement than real time allows
        if (vm.enabled("timer")) {
            double credit = vm.num("timer", "max-credit-ms", 400);
            d.timerBalance = Math.max(-credit, d.timerBalance + 50 - dt);
            if (d.timerBalance > vm.num("timer", "threshold-ms", 100)) {
                vm.flag(p, "Timer", 1, String.format("balance +%.0fms", d.timerBalance));
                d.timerBalance = 0;
                flagged = true;
            }
        }

        // ---------------- Speed
        if (vm.enabled("speed") && !special && !gap) {
            double max = vm.num("speed", "max-horizontal", 0.46);
            max *= Math.max(0.2, p.getWalkSpeed() / 0.2);       // respect plugin-set walk speed
            max *= 1.0 + 0.2 * effectLevel(p, "SPEED");
            if (now - d.lastIce < 1500) max *= 2.2;              // ice momentum lingers after leaving it
            if (ceiling) max *= 1.6;                             // sprint-jump under a ceiling is faster
            boolean bad = horiz > max;
            double buf = d.buffer("speed", bad, 1, 0.5);
            if (bad && buf >= vm.num("speed", "buffer", 3)) {
                vm.flag(p, "Speed", 1, String.format("%.3f > %.3f b/t", horiz, max));
                d.setBuffer("speed", 0);
                flagged = true;
            }
        }

        // ---------------- Step / HighJump: gaining too much height in one move from the ground
        if (vm.enabled("step") && groundFrom && dy > 0 && !recentSpecial && !gap) {
            double maxJump = 0.42 + 0.1 * effectLevel(p, "JUMP_BOOST", "JUMP");
            double max = Math.max(0.6, maxJump) + vm.num("step", "tolerance", 0.06);
            if (dy > max && !standingOnEntity(p)) {
                vm.flag(p, "Step", 1, String.format("rose %.3f > %.3f in one tick", dy, max));
                flagged = true;
            }
        }

        // ---------------- Fly (gravity model) + NoFall
        if (groundTo) {
            d.airTicks = 0;
            d.setBuffer("fly", 0);
        } else {
            d.airTicks++;
        }

        boolean airChecks = !groundTo && !recentSpecial && !levitation && !slowFall && !ceiling && !gap;

        if (vm.enabled("fly") && airChecks && d.airTicks >= 4) {
            double expected = Math.max((lastDy - 0.08) * 0.98, -3.92);
            double diff = Math.abs(dy - expected);
            boolean bad = diff > vm.num("fly", "tolerance", 0.06);
            double buf = d.buffer("fly", bad, 1, 0.5);
            if (bad && buf >= vm.num("fly", "buffer", 3) && !standingOnEntity(p)) {
                vm.flag(p, "Fly", 1, String.format("dy %.3f expected %.3f air %d", dy, expected, d.airTicks));
                d.setBuffer("fly", 0);
                flagged = true;
            }
        }

        if (vm.enabled("nofall") && airChecks) {
            // Client claims it is on the ground while we can see air below and it is falling.
            boolean spoof = p.isOnGround() && !groundFrom && dy < -0.1;
            double buf = d.buffer("nofall", spoof, 1, 1);
            if (spoof && buf >= vm.num("nofall", "buffer", 2) && !standingOnEntity(p)) {
                vm.flag(p, "NoFall", 1, "onGround spoof while falling dy=" + String.format("%.3f", dy));
                d.setBuffer("nofall", 0);
                flagged = true;
            }
        }

        // ---------------- Jesus (walking on liquid)
        if (vm.enabled("jesus")) {
            Block below = to.clone().subtract(0, 0.1, 0).getBlock();
            Block feet = to.getBlock();
            boolean onLiquid = (below.getType() == Material.WATER || below.getType() == Material.LAVA)
                    && feet.getType() != Material.WATER && feet.getType() != Material.LAVA
                    && !groundTo
                    && !standsOnLilyPad(to)
                    && horiz > 0.08 && Math.abs(dy) < 0.05;
            d.liquidTicks = onLiquid ? d.liquidTicks + 1 : 0;
            if (d.liquidTicks >= (int) vm.num("jesus", "ticks", 6) && !standingOnEntity(p)) {
                vm.flag(p, "Jesus", 1, "walking on " + below.getType().name().toLowerCase());
                d.liquidTicks = 0;
                flagged = true;
            }
        }

        // ---------------- Setback
        if (flagged && vm.cfg().getBoolean("movement.setback", true)) {
            Location safe = d.lastGround != null ? d.lastGround : from;
            if (safe.getWorld() == to.getWorld()) {
                e.setTo(safe);
                d.lastDeltaY = 0;
                d.airTicks = 0;
                d.prevGap = true;
                d.lastOnGround = true;
            }
        } else if (groundTo && !flagged) {
            d.lastGround = to.clone();
        }
    }

    // ---------------------------------------------------------------- helpers

    private boolean isExempt(Player p, PlayerData d, long now) {
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR
                || p.getAllowFlight() || p.isFlying() || p.isGliding() || p.isRiptiding()
                || p.isInsideVehicle() || p.isDead() || p.isSleeping()) {
            d.lastExempt = now;
            return true;
        }
        if (p.hasPermission("dekuguard.bypass")) return true;
        if (now - d.lastExempt < 1500) return true;   // momentum after flying, gliding, riptide, vehicles
        if (now - d.lastTeleport < 1500) return true;
        return now - d.lastVelocity < 1500;           // knockback, explosions, wind charges, etc.
    }

    /** Level of a potion effect (amplifier + 1), 0 if absent. Name lookup keeps this version-neutral. */
    private int effectLevel(Player p, String... names) {
        for (String n : names) {
            PotionEffectType type = PotionEffectType.getByName(n);
            if (type == null) continue;
            PotionEffect eff = p.getPotionEffect(type);
            if (eff != null) return eff.getAmplifier() + 1;
        }
        return 0;
    }

    /** True when something solid supports the player's feet (checks the four corners and centre). */
    private boolean nearGround(Location l) {
        if (l.getWorld() == null) return false;
        double[] off = {-0.31, 0, 0.31};
        for (double ox : off) {
            for (double oz : off) {
                Block b = l.getWorld().getBlockAt(
                        (int) Math.floor(l.getX() + ox),
                        (int) Math.floor(l.getY() - 0.2),
                        (int) Math.floor(l.getZ() + oz));
                Material m = b.getType();
                if (m.isSolid() || m == Material.LILY_PAD || m == Material.SNOW) return true;
            }
        }
        return false;
    }

    private boolean touchesSpecial(Location l) {
        Block feet = l.getBlock();
        Block head = l.clone().add(0, 1, 0).getBlock();
        Block below = l.clone().subtract(0, 0.3, 0).getBlock();
        return SPECIAL.contains(feet.getType()) || SPECIAL.contains(head.getType()) || SPECIAL.contains(below.getType());
    }

    private boolean bounceBlockBelow(Location l) {
        Material m = l.clone().subtract(0, 0.5, 0).getBlock().getType();
        return m == Material.SLIME_BLOCK || m.name().endsWith("_BED");
    }

    private boolean standsOnLilyPad(Location l) {
        return l.getBlock().getType() == Material.LILY_PAD
                || l.clone().subtract(0, 0.1, 0).getBlock().getType() == Material.LILY_PAD;
    }

    /** Boats, minecarts and shulkers count as ground but are not blocks. */
    private boolean standingOnEntity(Player p) {
        for (Entity en : p.getNearbyEntities(1.0, 1.5, 1.0)) {
            if (en instanceof Boat || en instanceof Minecart || en instanceof Shulker) return true;
        }
        return false;
    }
}

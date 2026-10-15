# DekuGuard

A lightweight movement + combat anticheat for Paper servers (1.20+).

## Checks

**Movement**
- **Speed**: horizontal distance per tick, scaled by walk speed, Speed potion, ice momentum and ceilings.
- **Fly**: compares every airborne tick to vanilla gravity (`(lastDy - 0.08) * 0.98`). Catches hover, glide, upward flight.
- **Step**: gaining more height in one tick than a jump or step allows (also catches high jump).
- **NoFall**: client claims "on ground" while the server sees air below and the player falling.
- **Jesus**: walking on water or lava for several ticks.
- **Timer**: sending movement faster than real time allows (time-balance model with a burst allowance for lag).
- **Velocity**: anti-knockback. After a push, the player has to actually move (ignored near walls, water, webs).

**Combat**
- **Reach**: distance from eyes to the nearest point of the target hitbox, with ping leniency. Blatant overshoots flag immediately, borderline ones are buffered.
- **KillAura**: hitting a target far from the crosshair (angle check, buffered), plus **MultiAura** (two targets in one tick).
- **Criticals**: server thinks the hit is a crit but the player has barely left the ground (mini-jump crits).
- **NoSwing**: attack with no arm-swing packet after it.
- **AutoClicker**: CPS cap, plus click-interval consistency (humans jitter, macros don't).

## How punishment works
Every flag raises a per-check violation level (vl) that decays over time. Staff with `dekuguard.alerts` see clickable alerts (click = `/tp` to the player). At a check's `punish-vl`, all `punish-commands` run from the console. Movement flags set the player back to their last good ground position, flagged hits are cancelled, and everything is appended to `plugins/DekuGuard/violations.log` (written on a background thread).

## Build
```
mvn clean package
```
Copy `target/DekuGuard-1.0.0.jar` into your server's `plugins/` folder and restart. Needs Java 17+.
If your server runs a newer Paper, change the `paper-api` version in `pom.xml`.

## Commands (`/dg`)
- `/dg reload`: reload config
- `/dg alerts`: toggle your alerts
- `/dg vl <player>`: show a player's violation levels
- `/dg reset <player>`: clear them

Permissions: `dekuguard.admin`, `dekuguard.alerts`, `dekuguard.bypass`.

## Tuning and known limits
- Start with `punish-commands` set to something harmless (e.g. `say %player% flagged %check%`) and watch alerts and `violations.log` for a few days before enabling kicks or bans.
- Raise `buffer`, `tolerance`, or `max-distance` if you see false flags, especially on laggy players. Use `disabled-worlds` for minigame or creative worlds.
- Anything that moves players via `setVelocity` (jump pads, kits, knockback plugins) is exempted for 1.5 seconds. Flying, gliding, riptide, vehicles and teleports are also exempted.
- Plugins that call `damage(amount, player)` look like real attacks to the server and can trigger NoSwing, Reach and KillAura. Give those players `dekuguard.bypass` or lower the buffers.
- This is a server-side anticheat working from Bukkit events, so it cannot see raw packets. Very subtle cheats (small reach, legit-looking aim assist) need packet-level tools to catch.

package dev.dekuguard;

import org.bukkit.Location;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Per-player state used by the checks. Only touched from the main thread. */
public class PlayerData {

    // movement
    public Location lastGround;
    public long lastMove;
    public double lastDeltaY;
    public boolean lastOnGround = true;
    public boolean prevGap;
    public int airTicks;
    public int liquidTicks;
    public double timerBalance;
    public long lastTeleport;
    public long lastVelocity;
    public long lastExempt;    // flying, gliding, riptide, vehicle, creative...
    public long lastSpecial;   // near liquid / climbable / bounce block etc.
    public long lastIce;
    public int kbSeq;

    // combat
    public long lastSwing;
    public long lastHitTime;
    public UUID lastTarget;
    public long lastClick;
    public final Deque<Long> clicks = new ArrayDeque<>();
    public final Deque<Long> intervals = new ArrayDeque<>();

    // violations
    public final Map<String, Double> vl = new HashMap<>();
    public final Map<String, Long> vlTime = new HashMap<>();
    public final Map<String, Long> lastAlert = new HashMap<>();
    private final Map<String, Double> buffers = new HashMap<>();

    /** Raise the buffer by inc when bad, lower by dec otherwise. Returns the new value. */
    public double buffer(String key, boolean bad, double inc, double dec) {
        double v = buffers.getOrDefault(key, 0.0);
        v = bad ? v + inc : Math.max(0, v - dec);
        buffers.put(key, v);
        return v;
    }

    public void setBuffer(String key, double v) {
        buffers.put(key, v);
    }

    public void clearViolations() {
        vl.clear();
        vlTime.clear();
        buffers.clear();
    }
}

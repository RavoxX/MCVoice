package dev.mcvoice.client.audio;

/**
 * Stereo spatialization and distance attenuation.
 *
 * <p>Minecraft coordinates: +X east, +Y up, +Z south; yaw 0 faces +Z and
 * increases clockwise seen from above (90 = facing -X/west). The listener's
 * right-hand vector is therefore (-cos yaw, 0, -sin yaw).
 */
public final class Spatializer {
    /** Full volume inside this distance (blocks). */
    public static final double FULL_VOLUME_DISTANCE = 2.0;

    private Spatializer() {
    }

    /** Smooth 1 -> 0 falloff reaching 0 with zero slope at {@code range} (no pop at the edge). */
    public static double attenuation(double distance, double range) {
        if (distance <= FULL_VOLUME_DISTANCE) {
            return 1.0;
        }
        if (distance >= range) {
            return 0.0;
        }
        double t = (distance - FULL_VOLUME_DISTANCE) / (range - FULL_VOLUME_DISTANCE);
        return 1.0 - t * t * (3 - 2 * t);
    }

    /** Pan in [-1 (left), 1 (right)] of a source relative to the listener orientation. */
    public static double pan(double lx, double lz, float yawDeg, double sx, double sz) {
        double dx = sx - lx, dz = sz - lz;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.5) {
            return 0;
        }
        double yaw = Math.toRadians(yawDeg);
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
        return Math.max(-1, Math.min(1, (dx * rx + dz * rz) / len));
    }

    /** Whether the source is behind the listener (dot with forward < 0), in [-1, 1]. */
    public static double facing(double lx, double lz, float yawDeg, double sx, double sz) {
        double dx = sx - lx, dz = sz - lz;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.5) {
            return 1;
        }
        double yaw = Math.toRadians(yawDeg);
        return (dx * -Math.sin(yaw) + dz * Math.cos(yaw)) / len;
    }

    /** Equal-power stereo gains {left, right} into {@code out}. */
    public static void gains(double pan, double gain, double facing, double[] out) {
        double p = pan * 0.85; // keep some signal in the far ear
        double angle = (p + 1) * Math.PI / 4;
        double behind = facing < 0 ? 1 - 0.2 * -facing : 1;
        out[0] = Math.cos(angle) * gain * behind * Math.sqrt(2);
        out[1] = Math.sin(angle) * gain * behind * Math.sqrt(2);
    }
}

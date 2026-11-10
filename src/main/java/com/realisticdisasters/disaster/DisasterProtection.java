package com.realisticdisasters.disaster;

import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.realisticdisasters.config.DisasterConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;

/** Central "may a disaster touch this block?" check: config switch, spawn protection, listed areas, claim mods. */
public final class DisasterProtection {
    private static final GameProfile PROFILE =
            new GameProfile(UUID.fromString("6b1a5c0e-4d2f-4e57-9a52-7d1f3c2a9e10"), "[RealisticDisasters]");

    public static boolean canModify(ServerLevel level, BlockPos pos, BlockState state) {
        if (!DisasterConfig.BLOCK_DESTRUCTION.getAsBoolean()) return false;
        if (!level.isLoaded(pos)) return false;

        int spawnRadius = DisasterConfig.SPAWN_PROTECTION_RADIUS.getAsInt();
        if (spawnRadius > 0) {
            var respawn = level.getRespawnData();
            if (respawn.globalPos().dimension() == level.dimension()) {
                BlockPos s = respawn.pos();
                long dx = pos.getX() - s.getX(), dz = pos.getZ() - s.getZ();
                if (dx * dx + dz * dz <= (long) spawnRadius * spawnRadius) return false;
            }
        }

        List<? extends String> areas = DisasterConfig.PROTECTED_AREAS.get();
        if (!areas.isEmpty()) {
            String dim = level.dimension().identifier().toString();
            for (String entry : areas) {
                String[] p = entry.split(",");
                if (p.length != 4 || !p[0].trim().equals(dim)) continue;
                try {
                    double x = Double.parseDouble(p[1].trim()), z = Double.parseDouble(p[2].trim()), r = Double.parseDouble(p[3].trim());
                    double dx = pos.getX() + 0.5 - x, dz = pos.getZ() + 0.5 - z;
                    if (dx * dx + dz * dz <= r * r) return false;
                } catch (NumberFormatException ignored) {
                }
            }
        }

        if (DisasterConfig.RESPECT_CLAIMS.getAsBoolean()) {
            FakePlayer fake = FakePlayerFactory.get(level, PROFILE);
            BreakBlockEvent event = new BreakBlockEvent(level, pos, state, fake);
            NeoForge.EVENT_BUS.post(event);
            if (event.isCanceled()) return false;
        }
        return true;
    }

    private DisasterProtection() {}
}

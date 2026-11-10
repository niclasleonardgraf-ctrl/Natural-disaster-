package com.realisticdisasters.disaster.earthquake;

import java.util.List;

import com.realisticdisasters.RealisticDisasters;
import com.realisticdisasters.disaster.Disaster;
import com.realisticdisasters.disaster.DisasterManager;
import com.realisticdisasters.disaster.DisasterType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Development only ({@code -Drealisticdisasters.quakeScene=true}, never in production): when the first earthquake
 * starts, builds a small test village around its epicentre (flattened ground, houses with plank roofs and windows,
 * a stone-brick tower, trees, a pond, a gravel road) and moves the first player to a vantage point inside the
 * quake zone, so headless visual checks show what an earthquake does to a real-looking scene.
 */
@EventBusSubscriber(modid = RealisticDisasters.MODID)
public final class EarthquakeDevScene {
    private static boolean built, aerial;
    private static Disaster quake;
    private static Vec3 epicentre;

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !Boolean.getBoolean("realisticdisasters.quakeScene")
                || net.neoforged.fml.loading.FMLEnvironment.isProduction() || level.players().isEmpty()) return;
        if (built) {
            // Aftermath: look down on the epicentre from above, like an aerial survey photo of the rupture.
            if (!aerial && quake != null && quake.phase() == com.realisticdisasters.disaster.Phase.AFTERMATH) {
                aerial = true;
                BlockPos stand = BlockPos.containing(epicentre.x - 6, epicentre.y + 33, epicentre.z - 30);
                level.setBlock(stand, Blocks.BARRIER.defaultBlockState(), 2);
                level.players().getFirst().connection.teleport(stand.getX() + 0.5, stand.getY() + 1, stand.getZ() + 0.5, 0f, 52f);
            }
            return;
        }
        for (Disaster d : DisasterManager.active(level)) {
            if (d.type() == DisasterType.EARTHQUAKE) {
                built = true;
                quake = d;
                epicentre = d.pos();
                try {
                    build(level, d.pos(), level.players().getFirst());
                } catch (RuntimeException e) {
                    RealisticDisasters.LOGGER.warn("Earthquake dev scene failed", e);
                }
                return;
            }
        }
    }

    private static void build(ServerLevel level, Vec3 epi, ServerPlayer player) {
        int cx = Mth.floor(epi.x), cz = Mth.floor(epi.z), g = Mth.floor(epi.y) - 1;
        int r = 56;
        level.getServer().setWeatherParameters(12000, 0, false, false); // clear sky, so the comparison isn't hidden by rain
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState(), dirt = Blocks.DIRT.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                for (int y = g - 3; y <= g + 48; y++) {
                    m.set(x, y, z);
                    level.setBlock(m, y < g ? dirt : y == g ? grass : air, 2);
                }
            }
        }
        // Gravel road through the village.
        for (int x = cx - r; x <= cx + r; x++)
            for (int dz = -1; dz <= 1; dz++) level.setBlock(new BlockPos(x, g, cz + 9 + dz), Blocks.GRAVEL.defaultBlockState(), 2);
        // Houses around the epicentre.
        house(level, cx - 14, g + 1, cz - 4, 7, 6, Blocks.COBBLESTONE.defaultBlockState());
        house(level, cx + 3, g + 1, cz - 14, 8, 6, Blocks.STONE_BRICKS.defaultBlockState());
        house(level, cx + 9, g + 1, cz + 1, 6, 7, Blocks.OAK_PLANKS.defaultBlockState());
        house(level, cx - 6, g + 1, cz + 13, 7, 7, Blocks.BRICKS.defaultBlockState());
        house(level, cx + 18, g + 1, cz + 14, 6, 6, Blocks.COBBLESTONE.defaultBlockState());
        // Stone-brick tower.
        for (int y = g + 1; y <= g + 12; y++)
            for (int x = 0; x < 3; x++)
                for (int z = 0; z < 3; z++)
                    if (x != 1 || z != 1) level.setBlock(new BlockPos(cx - 3 + x, y, cz + 2 + z), Blocks.STONE_BRICKS.defaultBlockState(), 2);
        // Pond.
        for (int x = -4; x <= 4; x++)
            for (int z = -3; z <= 3; z++)
                if (x * x / 16.0 + z * z / 9.0 <= 1) {
                    level.setBlock(new BlockPos(cx - 20 + x, g, cz + 20 + z), Blocks.WATER.defaultBlockState(), 2);
                    level.setBlock(new BlockPos(cx - 20 + x, g - 1, cz + 20 + z), Blocks.WATER.defaultBlockState(), 2);
                }
        // Trees.
        var features = level.registryAccess().lookupOrThrow(Registries.CONFIGURED_FEATURE);
        var keys = List.of(net.minecraft.data.worldgen.features.TreeFeatures.OAK, net.minecraft.data.worldgen.features.TreeFeatures.BIRCH);
        int[][] trees = {{-24, -14}, {-18, -22}, {14, -26}, {26, -6}, {-28, 6}, {24, 28}, {-10, 26}, {6, 24}, {-30, -30}, {30, -24}};
        for (int[] t : trees) {
            BlockPos at = new BlockPos(cx + t[0], g + 1, cz + t[1]);
            features.get(keys.get(Math.floorMod(t[0] + t[1], keys.size()))).ifPresent(f ->
                    f.value().place(level, level.getChunkSource().getGenerator(), level.getRandom(), at));
        }
        // Viewer: just inside the zone, slightly raised, looking across the village toward the epicentre.
        double px = cx - 30.5, pz = cz - 26.5;
        for (int y = g + 1; y <= g + 3; y++) level.setBlock(new BlockPos(Mth.floor(px), y, Mth.floor(pz)), Blocks.STONE.defaultBlockState(), 2);
        float yaw = (float) (Math.toDegrees(Math.atan2(cz - pz, cx - px)) - 90);
        player.connection.teleport(px, g + 4, pz, yaw, 14f);
    }

    private static void house(ServerLevel level, int x0, int y0, int z0, int w, int d, BlockState wall) {
        int h = 4;
        for (int x = 0; x < w; x++)
            for (int z = 0; z < d; z++) {
                boolean edge = x == 0 || z == 0 || x == w - 1 || z == d - 1;
                for (int y = 0; y < h; y++) {
                    if (!edge) continue;
                    boolean window = y == 1 && (x == w / 2 || z == d / 2) && !(x == 0 && z == 0);
                    boolean door = (y == 0 || y == 1) && z == 0 && x == w / 2;
                    BlockState st = door ? Blocks.AIR.defaultBlockState() : window ? Blocks.GLASS_PANE.defaultBlockState() : wall;
                    level.setBlock(new BlockPos(x0 + x, y0 + y, z0 + z), st, 3);
                }
                level.setBlock(new BlockPos(x0 + x, y0 + h, z0 + z), Blocks.OAK_PLANKS.defaultBlockState(), 2);
            }
        // Pitched roof ridge: a row of stairs-like slabs along the middle.
        for (int x = -1; x <= w; x++)
            level.setBlock(new BlockPos(x0 + x, y0 + h + 1, z0 + d / 2), Blocks.SPRUCE_PLANKS.defaultBlockState(), 2);
    }

    private EarthquakeDevScene() {}
}

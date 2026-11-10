package com.realisticdisasters.disaster;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.realisticdisasters.RealisticDisasters;
import com.realisticdisasters.config.DisasterConfig;
import com.realisticdisasters.network.DisasterRemovePayload;
import com.realisticdisasters.network.DisasterSyncPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Owns every active disaster on the logical server: ticking, batched block changes, client sync and
 * natural spawning. Purely server-side.
 */
@EventBusSubscriber(modid = RealisticDisasters.MODID)
public final class DisasterManager {
    private static final int SYNC_INTERVAL = 4;

    private static final Map<ResourceKey<Level>, LevelState> LEVELS = new HashMap<>();
    private static int nextId = 1;

    private static final class LevelState {
        final List<Disaster> active = new ArrayList<>();
        final BlockChangeQueue blocks = new BlockChangeQueue();
        long nextSpawnRoll = -1;
        double avgCostMs;
    }

    private static LevelState state(ServerLevel level) {
        return LEVELS.computeIfAbsent(level.dimension(), k -> new LevelState());
    }

    // ------------------------------------------------------------------ API

    public static Disaster start(ServerLevel level, DisasterType type, Vec3 pos, int intensity) {
        int capped = Mth.clamp(intensity, 1, DisasterConfig.MAX_INTENSITY.getAsInt());
        Disaster d = type.create(level, nextId++, pos, capped);
        state(level).active.add(d);
        RealisticDisasters.LOGGER.info("Started {} #{} (intensity {}) at {} in {}", type.id(), d.id(), capped,
                BlockPos.containing(pos).toShortString(), level.dimension().identifier());
        return d;
    }

    public static List<Disaster> active(ServerLevel level) {
        LevelState s = LEVELS.get(level.dimension());
        return s == null ? List.of() : List.copyOf(s.active);
    }

    public static int activeCount(ServerLevel level) {
        LevelState s = LEVELS.get(level.dimension());
        return s == null ? 0 : s.active.size();
    }

    /** Ends a disaster; with {@code fade} it runs its aftermath first. */
    public static boolean stop(ServerLevel level, int id, boolean fade) {
        LevelState s = LEVELS.get(level.dimension());
        if (s == null) return false;
        for (Disaster d : s.active) {
            if (d.id() == id) {
                if (fade) d.beginAftermath();
                else d.forceFinish();
                return true;
            }
        }
        return false;
    }

    public static BlockChangeQueue blockQueue(ServerLevel level) {
        return state(level).blocks;
    }

    // ------------------------------------------------------------------ ticking

    /** Smoothed server time (ms) spent on disasters per tick, per dimension. */
    public static double averageTickCostMs(ServerLevel level) {
        LevelState s = LEVELS.get(level.dimension());
        return s == null ? 0 : s.avgCostMs;
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        long start = System.nanoTime();
        tickLevel(level);
        LevelState st = LEVELS.get(level.dimension());
        if (st != null) st.avgCostMs += ((System.nanoTime() - start) / 1.0e6 - st.avgCostMs) * 0.05;
    }

    private static void tickLevel(ServerLevel level) {
        devAutostart(level);
        LevelState s = LEVELS.get(level.dimension());

        if (DisasterConfig.NATURAL_SPAWNING.getAsBoolean()) {
            s = state(level);
            trySpawnNaturally(level, s);
        }
        if (s == null) return;

        boolean sync = level.getGameTime() % SYNC_INTERVAL == 0;
        Iterator<Disaster> it = s.active.iterator();
        while (it.hasNext()) {
            Disaster d = it.next();
            try {
                d.tick();
            } catch (RuntimeException e) {
                RealisticDisasters.LOGGER.error("Disaster {} #{} crashed, removing it", d.type().id(), d.id(), e);
                d.forceFinish();
            }
            if (d.isFinished()) {
                it.remove();
                sendToNearby(level, d.pos(), d.effectRadius(), new DisasterRemovePayload(d.id()));
            } else if (sync) {
                sendToNearby(level, d.pos(), d.effectRadius(), d.toPayload());
            }
        }
        s.blocks.drain(level, DisasterConfig.MAX_BLOCK_CHANGES_PER_TICK.getAsInt());
    }

    private static void sendToNearby(ServerLevel level, Vec3 pos, float radius, CustomPacketPayload payload) {
        double range = DisasterConfig.SYNC_RANGE.getAsInt() + radius;
        for (ServerPlayer player : level.players()) {
            double dx = player.getX() - pos.x, dz = player.getZ() - pos.z;
            if (dx * dx + dz * dz <= range * range && player.connection.hasChannel(payload)) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    // ------------------------------------------------------------------ natural spawning

    private static void trySpawnNaturally(ServerLevel level, LevelState s) {
        long now = level.getGameTime();
        int interval = DisasterConfig.SPAWN_CHECK_INTERVAL.getAsInt();
        if (s.nextSpawnRoll < 0) s.nextSpawnRoll = now + interval;
        if (now < s.nextSpawnRoll) return;
        s.nextSpawnRoll = now + interval;

        if (!level.dimensionType().hasSkyLight() || level.dimensionType().hasCeiling()) return;
        if (s.active.size() >= DisasterConfig.MAX_ACTIVE.getAsInt()) return;
        List<ServerPlayer> players = level.players().stream().filter(p -> !p.isSpectator()).toList();
        if (players.isEmpty() || level.getRandom().nextDouble() >= DisasterConfig.SPAWN_CHANCE.get()) return;

        ServerPlayer target = players.get(level.getRandom().nextInt(players.size()));
        double angle = level.getRandom().nextDouble() * Math.PI * 2;
        double dist = 70 + level.getRandom().nextDouble() * 60;
        int x = Mth.floor(target.getX() + Math.cos(angle) * dist);
        int z = Mth.floor(target.getZ() + Math.sin(angle) * dist);
        if (!level.hasChunkAt(new BlockPos(x, 0, z))) return;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos pos = new BlockPos(x, y, z);
        Holder<Biome> biome = level.getBiome(pos);

        List<DisasterType> candidates = new ArrayList<>();
        int total = 0;
        for (DisasterType type : DisasterType.values()) {
            if (type.enabled() && type.weight() > 0 && biomeMatches(biome, type.biomes())) {
                candidates.add(type);
                total += type.weight();
            }
        }
        if (total <= 0) return;
        int roll = level.getRandom().nextInt(total);
        for (DisasterType type : candidates) {
            roll -= type.weight();
            if (roll < 0) {
                // natural intensity: weighted toward the low end
                int max = DisasterConfig.MAX_INTENSITY.getAsInt();
                int intensity = 1 + (int) Math.floor(Math.pow(level.getRandom().nextDouble(), 1.6) * max);
                start(level, type, Vec3.atBottomCenterOf(pos), Math.min(intensity, max));
                return;
            }
        }
    }

    public static boolean biomeMatches(Holder<Biome> biome, List<? extends String> entries) {
        for (String entry : entries) {
            try {
                if (entry.startsWith("#")) {
                    if (biome.is(TagKey.create(Registries.BIOME, Identifier.parse(entry.substring(1))))) return true;
                } else if (biome.is(ResourceKey.create(Registries.BIOME, Identifier.parse(entry)))) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // malformed id in config: skip
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ dev hook

    private static boolean autostarted;

    /**
     * Development only: {@code -Drealisticdisasters.autostart=tornado,3,BUILDUP} starts a disaster 40 blocks in front
     * of the first player once they have been in the world for 3 seconds. Used for automated visual checks.
     */
    private static void devAutostart(ServerLevel level) {
        if (autostarted || net.neoforged.fml.loading.FMLEnvironment.isProduction()) return;
        String spec = System.getProperty("realisticdisasters.autostart");
        if (spec == null || level.players().isEmpty()) return;
        ServerPlayer player = level.players().getFirst();
        if (player.tickCount < 60) return;
        autostarted = true;
        String[] p = spec.split(",");
        try {
            DisasterType type = DisasterType.valueOf(p[0].trim().toUpperCase(java.util.Locale.ROOT));
            int intensity = p.length > 1 ? Integer.parseInt(p[1].trim()) : 3;
            // Move the viewer to open ground (dev worlds often spawn you next to structures), looking south, tilted up.
            double px = player.getX() + 160, pz = player.getZ() - 40;
            int py = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(px), Mth.floor(pz));
            player.connection.teleport(px, py, pz, 0f, -12f);
            Vec3 at = new Vec3(px, py, pz + 55);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(at.x), Mth.floor(at.z));
            Disaster d = start(level, type, new Vec3(at.x, y, at.z), intensity);
            if (p.length > 2) d.skipTo(com.realisticdisasters.disaster.Phase.valueOf(p[2].trim().toUpperCase(java.util.Locale.ROOT)));
        } catch (RuntimeException e) {
            RealisticDisasters.LOGGER.warn("Bad realisticdisasters.autostart value '{}'", spec, e);
        }
    }

    // ------------------------------------------------------------------ housekeeping

    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            DisasterRemovePayload clear = new DisasterRemovePayload(-1);
            if (sp.connection.hasChannel(clear)) PacketDistributor.sendToPlayer(sp, clear);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LEVELS.clear();
        nextId = 1;
        autostarted = false;
    }

    private DisasterManager() {}
}

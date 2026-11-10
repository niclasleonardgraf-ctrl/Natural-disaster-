package com.realisticdisasters.gametest;

import java.util.function.Consumer;

import com.realisticdisasters.RealisticDisasters;
import com.realisticdisasters.config.DisasterConfig;
import com.realisticdisasters.disaster.Disaster;
import com.realisticdisasters.disaster.DisasterManager;
import com.realisticdisasters.disaster.DisasterType;
import com.realisticdisasters.disaster.Phase;
import com.realisticdisasters.disaster.wildfire.WildfireDisaster;
import com.realisticdisasters.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Automated in-world tests (run with {@code ./gradlew runGameTestServer}). Only registered in development.
 */
public final class DisasterGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS =
            DeferredRegister.create(Registries.TEST_FUNCTION, RealisticDisasters.MODID);

    static {
        FUNCTIONS.register("tornado_damages_terrain", () -> DisasterGameTests::tornadoDamagesTerrain);
        FUNCTIONS.register("tornado_respects_block_toggle", () -> DisasterGameTests::tornadoRespectsBlockToggle);
        FUNCTIONS.register("tornado_intensity3_tick_cost", () -> DisasterGameTests::tornadoTickCost);
        FUNCTIONS.register("tornado_shelter_protects", () -> DisasterGameTests::tornadoShelterProtects);
        FUNCTIONS.register("tornado_breaks_glass_and_snaps_trees", () -> DisasterGameTests::tornadoBreaksGlassAndSnapsTrees);
        FUNCTIONS.register("wildfire_burns_and_chars", () -> DisasterGameTests::wildfireBurnsAndChars);
        FUNCTIONS.register("wildfire_respects_block_toggle", () -> DisasterGameTests::wildfireRespectsBlockToggle);
        FUNCTIONS.register("wildfire_intensity3_tick_cost", () -> DisasterGameTests::wildfireTickCost);
        FUNCTIONS.register("wildfire_stopped_by_river", () -> DisasterGameTests::wildfireStoppedByRiver);
        FUNCTIONS.register("wildfire_put_out_by_water", () -> DisasterGameTests::wildfirePutOutByWater);
        FUNCTIONS.register("wildfire_heats_the_world", () -> DisasterGameTests::wildfireHeatsTheWorld);
        FUNCTIONS.register("wildfire_whirl_lofts_objects", () -> DisasterGameTests::wildfireWhirlLoftsObjects);
    }

    public static void register(IEventBus modBus) {
        FUNCTIONS.register(modBus);
        modBus.addListener(DisasterGameTests::registerTests);
    }

    private static void registerTests(RegisterGameTestsEvent event) {
        // Separate environments => separate batches, so tests that flip global config never overlap.
        add(event, "tornado_damages_terrain", event.registerEnvironment(ModRegistries.id("env_terrain")), 500);
        add(event, "tornado_respects_block_toggle", event.registerEnvironment(ModRegistries.id("env_toggle")), 300);
        add(event, "tornado_intensity3_tick_cost", event.registerEnvironment(ModRegistries.id("env_perf")), 400);
        add(event, "tornado_shelter_protects", event.registerEnvironment(ModRegistries.id("env_shelter")), 300);
        add(event, "tornado_breaks_glass_and_snaps_trees", event.registerEnvironment(ModRegistries.id("env_materials")), 400);
        addWildfire(event, "wildfire_burns_and_chars", event.registerEnvironment(ModRegistries.id("env_wildfire_terrain")), 1400);
        addWildfire(event, "wildfire_respects_block_toggle", event.registerEnvironment(ModRegistries.id("env_wildfire_toggle")), 300);
        addWildfire(event, "wildfire_intensity3_tick_cost", event.registerEnvironment(ModRegistries.id("env_wildfire_perf")), 400, "wildfire_field");
        addWildfire(event, "wildfire_stopped_by_river", event.registerEnvironment(ModRegistries.id("env_wildfire_river")), 800);
        addWildfire(event, "wildfire_put_out_by_water", event.registerEnvironment(ModRegistries.id("env_wildfire_water")), 300);
        addWildfire(event, "wildfire_heats_the_world", event.registerEnvironment(ModRegistries.id("env_wildfire_heat")), 900);
        addWildfire(event, "wildfire_whirl_lofts_objects", event.registerEnvironment(ModRegistries.id("env_wildfire_whirl")), 300);
    }

    private static void addWildfire(RegisterGameTestsEvent event, String name, Holder<TestEnvironmentDefinition<?>> env, int maxTicks) {
        addWildfire(event, name, env, maxTicks, "wildfire_arena");
    }

    private static void addWildfire(RegisterGameTestsEvent event, String name, Holder<TestEnvironmentDefinition<?>> env, int maxTicks,
                                    String structure) {
        var fn = ResourceKey.create(Registries.TEST_FUNCTION, ModRegistries.id(name));
        var data = new TestData<>(env, ModRegistries.id(structure), maxTicks, 0, true, Rotation.NONE, false, 1, 1, true, 0);
        event.registerTest(ModRegistries.id(name), new FunctionGameTestInstance(fn, data));
    }

    private static void add(RegisterGameTestsEvent event, String name, Holder<TestEnvironmentDefinition<?>> env, int maxTicks) {
        var fn = ResourceKey.create(Registries.TEST_FUNCTION, ModRegistries.id(name));
        var data = new TestData<>(env, ModRegistries.id("tornado_arena"), maxTicks, 0, true, Rotation.NONE, false, 1, 1, true, 0);
        event.registerTest(ModRegistries.id(name), new FunctionGameTestInstance(fn, data));
    }

    // ------------------------------------------------------------------ helpers

    private static Disaster startPeakTornado(GameTestHelper helper, int intensity) {
        DisasterConfig.SPAWN_PROTECTION_RADIUS.set(0);
        BlockPos center = helper.absolutePos(new BlockPos(16, 4, 16));
        Disaster d = DisasterManager.start(helper.getLevel(), DisasterType.TORNADO, Vec3.atBottomCenterOf(center), intensity);
        d.skipTo(Phase.PEAK);
        return d;
    }

    /** Number of top-soil positions in the arena that are no longer grass. */
    private static int scarredTurf(GameTestHelper helper) {
        int n = 0;
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                if (!helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(x, 3, z))).is(Blocks.GRASS_BLOCK)) n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ tests

    private static void tornadoDamagesTerrain(GameTestHelper helper) {
        int baseline = scarredTurf(helper);
        Disaster tornado = startPeakTornado(helper, 4);
        Pig pig = helper.spawn(EntityType.PIG, new BlockPos(16, 4, 13));
        double startY = pig.getY();
        double[] maxLift = {0};
        helper.onEachTick(() -> maxLift[0] = Math.max(maxLift[0], pig.getY() - startY));

        helper.succeedWhen(() -> {
            helper.assertTrue(DisasterManager.active(helper.getLevel()).contains(tornado), "tornado should be active");
            helper.assertTrue(maxLift[0] > 2.0, "pig was not lifted yet (max lift " + maxLift[0] + ")");
            int scarred = scarredTurf(helper) - baseline;
            helper.assertTrue(scarred >= 12, "not enough terrain damage yet (" + scarred + ")");
            DisasterManager.stop(helper.getLevel(), tornado.id(), false);
        });
    }

    private static void tornadoRespectsBlockToggle(GameTestHelper helper) {
        DisasterConfig.BLOCK_DESTRUCTION.set(false);
        // Snapshot every block in the arena (top soil and above) so we only see changes made during the test.
        java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> before = new java.util.HashMap<>();
        for (int x = 0; x < 32; x++)
            for (int y = 3; y < 9; y++)
                for (int z = 0; z < 32; z++) {
                    if ((x - 16) * (x - 16) + (z - 16) * (z - 16) > 14 * 14) continue; // tornado's reach only
                    BlockPos p = helper.absolutePos(new BlockPos(x, y, z));
                    before.put(p, helper.getLevel().getBlockState(p));
                }
        Disaster tornado = startPeakTornado(helper, 5);
        helper.runAfterDelay(200, () -> {
            StringBuilder changed = new StringBuilder();
            int count = 0;
            for (var e : before.entrySet()) {
                var now = helper.getLevel().getBlockState(e.getKey());
                if (now != e.getValue()) {
                    count++;
                    if (count <= 5) changed.append(helper.relativePos(e.getKey()).toShortString()).append(' ')
                            .append(e.getValue().getBlock().getDescriptionId()).append("->").append(now.getBlock().getDescriptionId()).append("; ");
                }
            }
            var mobs = helper.getLevel().getEntities((net.minecraft.world.entity.Entity) null, helper.getBounds().inflate(8),
                    e -> !(e instanceof net.minecraft.world.entity.player.Player));
            changed.append(" entities: ").append(mobs.stream().map(e -> e.getType().getDescriptionId()).toList());
            DisasterConfig.BLOCK_DESTRUCTION.set(true);
            DisasterManager.stop(helper.getLevel(), tornado.id(), false);
            helper.assertTrue(count == 0, "blocks changed although destruction is disabled: " + count + " " + changed);
            helper.succeed();
        });
    }

    /** Performance target from the spec: no noticeable TPS drop at intensity 3 (budget here: 2 ms of the 50 ms tick). */
    private static void tornadoTickCost(GameTestHelper helper) {
        Disaster tornado = startPeakTornado(helper, 3);
        for (int i = 0; i < 6; i++) helper.spawn(EntityType.COW, new BlockPos(6 + i * 4, 4, 10));
        helper.runAfterDelay(300, () -> {
            double cost = DisasterManager.averageTickCostMs(helper.getLevel());
            DisasterManager.stop(helper.getLevel(), tornado.id(), false);
            RealisticDisasters.LOGGER.info("Tornado intensity 3 average server cost: {} ms/tick", String.format("%.3f", cost));
            helper.assertTrue(cost < 2.0, "tornado too expensive: " + cost + " ms/tick");
            helper.succeed();
        });
    }

    /** Shelter physics: a sealed bunker keeps its occupant on the ground while an exposed animal gets lifted. */
    private static void tornadoShelterProtects(GameTestHelper helper) {
        // obsidian bunker (wind can't break it) around (16,4,12); the pig stands inside
        for (int x = 15; x <= 17; x++)
            for (int y = 4; y <= 6; y++)
                for (int z = 11; z <= 13; z++)
                    if (!(x == 16 && z == 12 && y <= 5)) helper.setBlock(new BlockPos(x, y, z), Blocks.OBSIDIAN);
        Pig sheltered = helper.spawn(EntityType.PIG, new BlockPos(16, 4, 12));
        Pig exposed = helper.spawn(EntityType.PIG, new BlockPos(19, 4, 16));
        Vec3 start = sheltered.position();
        double startY = exposed.getY();
        double[] lift = {0};
        helper.onEachTick(() -> lift[0] = Math.max(lift[0], exposed.getY() - startY));
        Disaster tornado = startPeakTornado(helper, 4);
        helper.runAfterDelay(160, () -> {
            double moved = sheltered.position().distanceTo(start);
            DisasterManager.stop(helper.getLevel(), tornado.id(), false);
            helper.assertTrue(lift[0] > 2.0, "exposed pig was not lifted (" + lift[0] + ")");
            helper.assertTrue(moved < 1.0, "sheltered pig was moved " + moved + " blocks");
            helper.succeed();
        });
    }

    /** Material failure: glass shatters and a tree trunk snaps near the funnel (leaving a stump). */
    private static void tornadoBreaksGlassAndSnapsTrees(GameTestHelper helper) {
        BlockPos glass = new BlockPos(18, 4, 18);
        helper.setBlock(glass, Blocks.GLASS);
        for (int y = 4; y <= 8; y++) helper.setBlock(new BlockPos(14, y, 17), Blocks.OAK_LOG);
        Disaster tornado = startPeakTornado(helper, 4);
        helper.succeedWhen(() -> {
            helper.assertTrue(!helper.getLevel().getBlockState(helper.absolutePos(glass)).is(Blocks.GLASS), "glass still intact");
            int logs = 0;
            for (int y = 4; y <= 8; y++) if (helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(14, y, 17))).is(Blocks.OAK_LOG)) logs++;
            // A snapped trunk leaves a stump: the top must be gone, the stump may stay.
            helper.assertTrue(!helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(14, 8, 17))).is(Blocks.OAK_LOG)
                    && logs <= 3, "trunk not snapped (" + logs + " logs left)");
            DisasterManager.stop(helper.getLevel(), tornado.id(), false);
        });
    }

    // ------------------------------------------------------------------ wildfire

    private static WildfireDisaster startPeakWildfire(GameTestHelper helper, int intensity, boolean confine) {
        return startPeakWildfire(helper, intensity, confine, new BlockPos(16, 4, 16));
    }

    private static WildfireDisaster startPeakWildfire(GameTestHelper helper, int intensity, boolean confine, BlockPos at) {
        DisasterConfig.SPAWN_PROTECTION_RADIUS.set(0);
        // Rain would (correctly) smother the fire; clear it, including the lingering rain level.
        helper.getLevel().getServer().setWeatherParameters(12000, 0, false, false);
        helper.getLevel().setRainLevel(0f);
        helper.getLevel().setThunderLevel(0f);
        // Fuel moisture follows the time of day (fires lie down at night): test in the dry afternoon.
        var server = helper.getLevel().getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "time set noon");
        BlockPos center = helper.absolutePos(at);
        Disaster d = DisasterManager.start(helper.getLevel(), DisasterType.WILDFIRE, Vec3.atBottomCenterOf(center), intensity);
        WildfireDisaster fire = (WildfireDisaster) d;
        if (confine) fire.setMaxRadius(14); // stay inside the 32x32 arena
        fire.setWindAngle(Math.PI / 4);
        fire.skipTo(Phase.PEAK);
        return fire;
    }

    /** Arena turf (y = 3) that is no longer grass. */
    private static int scorchedTurf(GameTestHelper helper) {
        int n = 0;
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                var st = helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(x, 3, z)));
                if (st.is(ModRegistries.SCORCHED_EARTH.get()) || st.is(Blocks.COARSE_DIRT) || st.is(Blocks.DIRT)) n++;
            }
        }
        return n;
    }

    private static int countInArena(GameTestHelper helper, net.minecraft.world.level.block.Block block) {
        int n = 0;
        for (int x = 0; x < 32; x++)
            for (int y = 3; y < 12; y++)
                for (int z = 0; z < 32; z++)
                    if (helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(x, y, z))).is(block)) n++;
        return n;
    }

    private static void wildfireBurnsAndChars(GameTestHelper helper) {
        int baseline = scorchedTurf(helper);
        int leavesBefore = countInArena(helper, Blocks.OAK_LEAVES);
        // Light wind: the fire spreads in all directions and burns through the whole arena (a strong wind would leave
        // the upwind side to a slow backing fire that often goes out - realistic, but this test is about the scar).
        WildfireDisaster fire = startPeakWildfire(helper, 4, true);
        fire.setWindSpeed(0.2f);
        Pig pig = helper.spawn(EntityType.PIG, new BlockPos(18, 4, 18));
        Vec3 pigStart = pig.position();
        boolean[] reacted = {false};
        helper.onEachTick(() -> {
            if (pig.isOnFire() || pig.isRemoved() || pig.position().distanceTo(pigStart) > 3) reacted[0] = true;
        });

        helper.succeedWhen(() -> {
            helper.assertTrue(fire.ignitedCount() > 60, "fire did not spread (" + fire.ignitedCount() + " columns)");
            int scorched = scorchedTurf(helper) - baseline;
            helper.assertTrue(scorched >= 40, "not enough scorched turf yet (" + scorched + ")");
            helper.assertTrue(countInArena(helper, ModRegistries.CHARRED_LOG.get()) >= 3, "no charred tree trunk yet");
            helper.assertTrue(countInArena(helper, ModRegistries.SCORCHED_EARTH.get()) >= 25, "burnt ground is not scorched earth");
            int leaves = countInArena(helper, Blocks.OAK_LEAVES);
            helper.assertTrue(leaves < leavesBefore / 2, "canopy not burnt (" + leaves + "/" + leavesBefore + " leaves left, "
                    + fire.ignitedCount() + " ignited, " + fire.burningCount() + " burning, phase " + fire.phase() + ")");
            helper.assertTrue(reacted[0], "pig neither fled nor caught fire");
            DisasterManager.stop(helper.getLevel(), fire.id(), false);
        });
    }

    private static void wildfireRespectsBlockToggle(GameTestHelper helper) {
        DisasterConfig.BLOCK_DESTRUCTION.set(false);
        java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> before = new java.util.HashMap<>();
        for (int x = 0; x < 32; x++)
            for (int y = 3; y < 12; y++)
                for (int z = 0; z < 32; z++) {
                    BlockPos p = helper.absolutePos(new BlockPos(x, y, z));
                    before.put(p, helper.getLevel().getBlockState(p));
                }
        WildfireDisaster fire = startPeakWildfire(helper, 5, true);
        helper.runAfterDelay(200, () -> {
            StringBuilder changed = new StringBuilder();
            int count = 0;
            for (var e : before.entrySet()) {
                var now = helper.getLevel().getBlockState(e.getKey());
                if (now != e.getValue()) {
                    count++;
                    if (count <= 5) changed.append(helper.relativePos(e.getKey()).toShortString()).append(' ')
                            .append(e.getValue().getBlock().getDescriptionId()).append("->").append(now.getBlock().getDescriptionId()).append("; ");
                }
            }
            int ignited = fire.ignitedCount();
            DisasterConfig.BLOCK_DESTRUCTION.set(true);
            DisasterManager.stop(helper.getLevel(), fire.id(), false);
            helper.assertTrue(ignited > 30, "fire simulation did not run (" + ignited + " columns)");
            helper.assertTrue(count == 0, "blocks changed although destruction is disabled: " + count + " " + changed);
            helper.succeed();
        });
    }

    /** Spec performance target at intensity 3 (budget: 2 ms of the 50 ms tick). Runs on a 48x48 field so a real front builds up. */
    private static void wildfireTickCost(GameTestHelper helper) {
        // 48x48 field, ignited near one corner so the wind (towards +X +Z) drives a long front across it.
        WildfireDisaster fire = startPeakWildfire(helper, 3, false, new BlockPos(10, 4, 10));
        fire.setMaxRadius(38);
        for (int i = 0; i < 6; i++) helper.spawn(EntityType.COW, new BlockPos(8 + i * 6, 4, 40));
        helper.runAfterDelay(300, () -> {
            double cost = DisasterManager.averageTickCostMs(helper.getLevel());
            int ignited = fire.ignitedCount(), burning = fire.burningCount();
            DisasterManager.stop(helper.getLevel(), fire.id(), false);
            RealisticDisasters.LOGGER.info("Wildfire intensity 3 average server cost: {} ms/tick ({} columns ignited, {} burning)",
                    String.format("%.3f", cost), ignited, burning);
            helper.assertTrue(ignited > 250, "fire did not spread enough for a meaningful measurement (" + ignited + ")");
            helper.assertTrue(cost < 2.0, "wildfire too expensive: " + cost + " ms/tick");
            helper.succeed();
        });
    }

    /** A 3-wide river is a firebreak (with ember spotting off; embers can legitimately jump it). */
    private static void wildfireStoppedByRiver(GameTestHelper helper) {
        for (int x = 20; x <= 22; x++)
            for (int z = 0; z < 32; z++) {
                helper.setBlock(new BlockPos(x, 3, z), Blocks.WATER);
                helper.setBlock(new BlockPos(x, 4, z), Blocks.AIR);
            }
        WildfireDisaster fire = startPeakWildfire(helper, 4, false, new BlockPos(8, 4, 16));
        fire.setMaxRadius(30);
        fire.setWindAngle(0); // blowing straight at the river
        fire.setSpotting(false);
        fire.setWhirls(false);
        helper.runAfterDelay(700, () -> {
            int nearBank = 0, across = 0;
            for (int z = 0; z < 32; z++) {
                for (int x = 15; x <= 19; x++) if (isBurnt(helper, x, z)) nearBank++;
                for (int x = 23; x < 32; x++) if (isBurnt(helper, x, z)) across++;
            }
            DisasterManager.stop(helper.getLevel(), fire.id(), false);
            helper.assertTrue(nearBank >= 10, "fire never reached the river (" + nearBank + " burnt columns near the bank)");
            helper.assertTrue(across == 0, "fire crossed the river (" + across + " burnt columns on the far bank)");
            helper.succeed();
        });
    }

    private static boolean isBurnt(GameTestHelper helper, int x, int z) {
        var st = helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(x, 3, z)));
        return st.is(ModRegistries.SCORCHED_EARTH.get()) || st.is(Blocks.COARSE_DIRT);
    }

    /** Pouring water on burning ground puts that column out. */
    private static void wildfirePutOutByWater(GameTestHelper helper) {
        WildfireDisaster fire = startPeakWildfire(helper, 3, true);
        fire.setSpotting(false);
        long[] target = {Long.MIN_VALUE};
        helper.runAfterDelay(40, () -> {
            BlockPos a = helper.absolutePos(new BlockPos(3, 4, 3)), b = helper.absolutePos(new BlockPos(28, 4, 28));
            int minX = Math.min(a.getX(), b.getX()), maxX = Math.max(a.getX(), b.getX());
            int minZ = Math.min(a.getZ(), b.getZ()), maxZ = Math.max(a.getZ(), b.getZ());
            for (long key : fire.burningColumns()) {
                int x = BlockPos.getX(key), z = BlockPos.getZ(key);
                if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) {
                    target[0] = key;
                    // a bucket of water on the burning ground
                    helper.getLevel().setBlockAndUpdate(new BlockPos(x, a.getY(), z), Blocks.WATER.defaultBlockState());
                    break;
                }
            }
            helper.assertTrue(target[0] != Long.MIN_VALUE, "no burning column to put out (burning " + fire.burningCount()
                    + ", ignited " + fire.ignitedCount() + ", extinguished " + fire.extinguishedCount() + ", age " + fire.age() + ")");
        });
        helper.runAfterDelay(120, () -> {
            int x = BlockPos.getX(target[0]), z = BlockPos.getZ(target[0]);
            boolean still = fire.isBurning(x, z);
            int out = fire.extinguishedCount();
            DisasterManager.stop(helper.getLevel(), fire.id(), false);
            helper.assertTrue(!still, "the watered column is still burning");
            helper.assertTrue(out >= 1, "nothing was extinguished");
            helper.succeed();
        });
    }

    /** Heat from the front primes TNT, lights a campfire and melts snow. */
    private static void wildfireHeatsTheWorld(GameTestHelper helper) {
        // all three in the fire's path (it is lit at the centre; the wind blows toward +x +z)
        BlockPos tnt = new BlockPos(24, 4, 23), campfire = new BlockPos(13, 4, 22), snow = new BlockPos(21, 4, 17);
        helper.setBlock(tnt, Blocks.TNT);
        helper.setBlock(campfire, Blocks.CAMPFIRE.defaultBlockState().setValue(net.minecraft.world.level.block.CampfireBlock.LIT, false));
        helper.setBlock(snow, Blocks.SNOW);
        WildfireDisaster fire = startPeakWildfire(helper, 4, true);
        fire.setWindSpeed(0.3f);
        fire.setWhirls(false);
        boolean[] seen = new boolean[3];
        helper.onEachTick(() -> {
            if (!helper.getLevel().getBlockState(helper.absolutePos(tnt)).is(Blocks.TNT)) seen[0] = true;
            var cf = helper.getLevel().getBlockState(helper.absolutePos(campfire));
            if (cf.is(Blocks.CAMPFIRE) && cf.getValue(net.minecraft.world.level.block.CampfireBlock.LIT)) seen[1] = true;
            if (!helper.getLevel().getBlockState(helper.absolutePos(snow)).is(Blocks.SNOW)) seen[2] = true;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(seen[0], "TNT was not set off by the fire");
            helper.assertTrue(seen[1], "campfire was not lit by the fire");
            helper.assertTrue(seen[2], "snow did not melt");
            DisasterManager.stop(helper.getLevel(), fire.id(), false);
        });
    }

    /** A fire whirl pulls in and lofts light objects (fire-proof items, so they survive to be measured). */
    private static void wildfireWhirlLoftsObjects(GameTestHelper helper) {
        WildfireDisaster fire = startPeakWildfire(helper, 5, true);
        fire.setSpotting(false);
        BlockPos c = helper.absolutePos(new BlockPos(16, 4, 16));
        fire.forceWhirl(c.getX() + 0.5, c.getZ() + 0.5);
        double startY = c.getY();
        java.util.List<net.minecraft.world.entity.item.ItemEntity> items = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            var item = new net.minecraft.world.entity.item.ItemEntity(helper.getLevel(), c.getX() + 0.5 + (i % 2) * 1.2 - 0.6,
                    startY + 0.2, c.getZ() + 0.5 + (i / 2) * 1.2 - 0.6, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.NETHERITE_INGOT));
            helper.getLevel().addFreshEntity(item);
            items.add(item);
        }
        double[] lift = {0};
        helper.onEachTick(() -> {
            for (var item : items) lift[0] = Math.max(lift[0], item.getY() - startY);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(fire.whirlCount() >= 1, "no fire whirl");
            helper.assertTrue(lift[0] > 2.0, "whirl did not loft anything (max lift " + String.format("%.2f", lift[0]) + ")");
            DisasterManager.stop(helper.getLevel(), fire.id(), false);
        });
    }

    private DisasterGameTests() {}
}

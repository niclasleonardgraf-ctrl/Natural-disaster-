package com.realisticdisasters.gametest;

import java.util.function.Consumer;

import com.realisticdisasters.RealisticDisasters;
import com.realisticdisasters.config.DisasterConfig;
import com.realisticdisasters.disaster.Disaster;
import com.realisticdisasters.disaster.DisasterManager;
import com.realisticdisasters.disaster.DisasterType;
import com.realisticdisasters.disaster.Phase;
import com.realisticdisasters.disaster.meteor.MeteorShowerDisaster;
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
        FUNCTIONS.register("meteor_impact_makes_crater", () -> DisasterGameTests::meteorImpactMakesCrater);
        FUNCTIONS.register("meteor_core_contains_ores", () -> DisasterGameTests::meteorCoreContainsOres);
        FUNCTIONS.register("meteor_respects_block_toggle", () -> DisasterGameTests::meteorRespectsBlockToggle);
        FUNCTIONS.register("meteor_intensity3_tick_cost", () -> DisasterGameTests::meteorTickCost);
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
        addMeteor(event, "meteor_impact_makes_crater", event.registerEnvironment(ModRegistries.id("env_meteor_crater")), 300);
        addMeteor(event, "meteor_core_contains_ores", event.registerEnvironment(ModRegistries.id("env_meteor_ores")), 300);
        addMeteor(event, "meteor_respects_block_toggle", event.registerEnvironment(ModRegistries.id("env_meteor_toggle")), 300);
        addMeteor(event, "meteor_intensity3_tick_cost", event.registerEnvironment(ModRegistries.id("env_meteor_perf")), 400);
    }

    private static void add(RegisterGameTestsEvent event, String name, Holder<TestEnvironmentDefinition<?>> env, int maxTicks) {
        var fn = ResourceKey.create(Registries.TEST_FUNCTION, ModRegistries.id(name));
        var data = new TestData<>(env, ModRegistries.id("tornado_arena"), maxTicks, 0, true, Rotation.NONE, false, 1, 1, true, 0);
        event.registerTest(ModRegistries.id(name), new FunctionGameTestInstance(fn, data));
    }

    private static void addMeteor(RegisterGameTestsEvent event, String name, Holder<TestEnvironmentDefinition<?>> env, int maxTicks) {
        var fn = ResourceKey.create(Registries.TEST_FUNCTION, ModRegistries.id(name));
        var data = new TestData<>(env, ModRegistries.id("meteor_arena"), maxTicks, 0, true, Rotation.NONE, false, 1, 1, true, 0);
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

    // ------------------------------------------------------------------ meteor shower (arena: grass at y=11, stone y 0-8)

    private static final int METEOR_GROUND = 11;

    private static MeteorShowerDisaster startMeteorShower(GameTestHelper helper, int intensity) {
        DisasterConfig.SPAWN_PROTECTION_RADIUS.set(0);
        BlockPos center = helper.absolutePos(new BlockPos(16, METEOR_GROUND + 1, 16));
        return (MeteorShowerDisaster) DisasterManager.start(helper.getLevel(), DisasterType.METEOR_SHOWER,
                Vec3.atBottomCenterOf(center), intensity);
    }

    private static Vec3 arenaPoint(GameTestHelper helper, double x, double z) {
        BlockPos p = helper.absolutePos(new BlockPos(0, METEOR_GROUND + 1, 0));
        return new Vec3(p.getX() + x, p.getY(), p.getZ() + z);
    }

    private static int countInArena(GameTestHelper helper, java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> test) {
        int n = 0;
        for (int x = 0; x < 32; x++)
            for (int y = 0; y < 18; y++)
                for (int z = 0; z < 32; z++)
                    if (test.test(helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(x, y, z))))) n++;
        return n;
    }

    private static boolean scorched(net.minecraft.world.level.block.state.BlockState s) {
        return s.is(Blocks.BLACKSTONE) || s.is(Blocks.BASALT) || s.is(Blocks.SMOOTH_BASALT) || s.is(Blocks.MAGMA_BLOCK)
                || s.is(Blocks.COARSE_DIRT);
    }

    private static void meteorImpactMakesCrater(GameTestHelper helper) {
        MeteorShowerDisaster shower = startMeteorShower(helper, 4);
        Pig pig = helper.spawn(EntityType.PIG, new BlockPos(16, METEOR_GROUND + 1, 7));
        Vec3 pigStart = pig.position();
        double[] maxPush = {0};
        helper.onEachTick(() -> {
            if (pig.isAlive()) maxPush[0] = Math.max(maxPush[0], pig.position().subtract(pigStart).horizontalDistance());
            else maxPush[0] = Math.max(maxPush[0], 99); // killed by the blast also counts as hit
        });
        helper.assertTrue(shower.scheduleAt(arenaPoint(helper, 16.5, 16.5), 1.0f, 20), "target column not loaded");

        helper.succeedWhen(() -> {
            helper.assertTrue(shower.impactsLanded() >= 1, "meteor has not landed yet");
            int air = 0;
            for (int x = 13; x <= 19; x++)
                for (int z = 13; z <= 19; z++)
                    for (int y = METEOR_GROUND - 1; y <= METEOR_GROUND; y++)
                        if (helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(x, y, z))).isAir()) air++;
            helper.assertTrue(air >= 20, "crater bowl not carved yet (" + air + " air blocks)");
            int burnt = countInArena(helper, DisasterGameTests::scorched);
            helper.assertTrue(burnt >= 15, "not enough scorched blocks yet (" + burnt + ")");
            helper.assertTrue(maxPush[0] > 1.5, "pig was not thrown by the shockwave (" + maxPush[0] + ")");
            DisasterManager.stop(helper.getLevel(), shower.id(), false);
        });
    }

    private static void meteorCoreContainsOres(GameTestHelper helper) {
        DisasterConfig.METEOR_ORE_CHANCE.set(1.0);
        MeteorShowerDisaster shower = startMeteorShower(helper, 3);
        shower.scheduleAt(arenaPoint(helper, 16.5, 16.5), 1.0f, 10);
        var ores = MeteorShowerDisaster.oreTable();
        helper.succeedWhen(() -> {
            helper.assertTrue(shower.impactsLanded() >= 1, "meteor has not landed yet");
            int found = countInArena(helper, s -> ores.contains(s.getBlock()));
            helper.assertTrue(found >= 4, "meteorite core has too few ores (" + found + ")");
            DisasterConfig.METEOR_ORE_CHANCE.set(0.12);
            DisasterManager.stop(helper.getLevel(), shower.id(), false);
        });
    }

    private static void meteorRespectsBlockToggle(GameTestHelper helper) {
        DisasterConfig.BLOCK_DESTRUCTION.set(false);
        java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> before = new java.util.HashMap<>();
        for (int x = 0; x < 32; x++)
            for (int y = 0; y < 18; y++)
                for (int z = 0; z < 32; z++) {
                    BlockPos p = helper.absolutePos(new BlockPos(x, y, z));
                    before.put(p, helper.getLevel().getBlockState(p));
                }
        MeteorShowerDisaster shower = startMeteorShower(helper, 5);
        shower.scheduleAt(arenaPoint(helper, 16.5, 16.5), 1.5f, 10);
        shower.scheduleAt(arenaPoint(helper, 6.5, 8.5), 1.0f, 30);
        shower.scheduleAt(arenaPoint(helper, 25.5, 24.5), 1.0f, 50);
        helper.runAfterDelay(150, () -> {
            int count = 0;
            for (var e : before.entrySet()) if (helper.getLevel().getBlockState(e.getKey()) != e.getValue()) count++;
            int landed = shower.impactsLanded();
            DisasterConfig.BLOCK_DESTRUCTION.set(true);
            DisasterManager.stop(helper.getLevel(), shower.id(), false);
            helper.assertTrue(landed == 3, "expected 3 impacts, got " + landed);
            helper.assertTrue(count == 0, "blocks changed although destruction is disabled: " + count);
            helper.succeed();
        });
    }

    private static void meteorTickCost(GameTestHelper helper) {
        MeteorShowerDisaster shower = startMeteorShower(helper, 3);
        shower.skipTo(Phase.PEAK);
        for (int i = 0; i < 6; i++) helper.spawn(EntityType.COW, new BlockPos(6 + i * 4, METEOR_GROUND + 1, 10));
        // Two craters inside the arena on top of the natural barrage, so carving cost is measured too.
        shower.scheduleAt(arenaPoint(helper, 10.5, 20.5), 1.0f, 40);
        shower.scheduleAt(arenaPoint(helper, 22.5, 12.5), 1.0f, 140);
        helper.runAfterDelay(300, () -> {
            double cost = DisasterManager.averageTickCostMs(helper.getLevel());
            int landed = shower.impactsLanded();
            DisasterManager.stop(helper.getLevel(), shower.id(), false);
            RealisticDisasters.LOGGER.info("Meteor shower intensity 3 average server cost: {} ms/tick ({} impacts)",
                    String.format("%.3f", cost), landed);
            helper.assertTrue(landed >= 2, "expected at least 2 impacts, got " + landed);
            helper.assertTrue(cost < 2.0, "meteor shower too expensive: " + cost + " ms/tick");
            helper.succeed();
        });
    }

    private DisasterGameTests() {}
}

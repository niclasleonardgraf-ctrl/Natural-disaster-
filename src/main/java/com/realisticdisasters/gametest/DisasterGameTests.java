package com.realisticdisasters.gametest;

import java.util.function.Consumer;

import com.realisticdisasters.RealisticDisasters;
import com.realisticdisasters.config.DisasterConfig;
import com.realisticdisasters.disaster.Disaster;
import com.realisticdisasters.disaster.DisasterManager;
import com.realisticdisasters.disaster.DisasterType;
import com.realisticdisasters.disaster.Phase;
import com.realisticdisasters.disaster.volcano.VolcanoDisaster;
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
    }

    public static void register(IEventBus modBus) {
        FUNCTIONS.register(modBus);
        FUNCTIONS.register("volcano_erupts", () -> DisasterGameTests::volcanoErupts);
        FUNCTIONS.register("volcano_lava_cools", () -> DisasterGameTests::volcanoLavaCools);
        FUNCTIONS.register("volcano_respects_block_toggle", () -> DisasterGameTests::volcanoRespectsBlockToggle);
        FUNCTIONS.register("volcano_intensity3_tick_cost", () -> DisasterGameTests::volcanoTickCost);
        modBus.addListener(DisasterGameTests::registerTests);
    }

    private static void registerTests(RegisterGameTestsEvent event) {
        // Separate environments => separate batches, so tests that flip global config never overlap.
        add(event, "tornado_damages_terrain", event.registerEnvironment(ModRegistries.id("env_terrain")), 500);
        add(event, "tornado_respects_block_toggle", event.registerEnvironment(ModRegistries.id("env_toggle")), 300);
        add(event, "tornado_intensity3_tick_cost", event.registerEnvironment(ModRegistries.id("env_perf")), 400);
        add(event, "tornado_shelter_protects", event.registerEnvironment(ModRegistries.id("env_shelter")), 300);
        add(event, "tornado_breaks_glass_and_snaps_trees", event.registerEnvironment(ModRegistries.id("env_materials")), 400);
        add(event, "volcano_erupts", event.registerEnvironment(ModRegistries.id("env_volcano_erupts")), 600);
        add(event, "volcano_lava_cools", event.registerEnvironment(ModRegistries.id("env_volcano_cooling")), 900);
        add(event, "volcano_respects_block_toggle", event.registerEnvironment(ModRegistries.id("env_volcano_toggle")), 400);
        add(event, "volcano_intensity3_tick_cost", event.registerEnvironment(ModRegistries.id("env_volcano_perf")), 400);
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

    // ------------------------------------------------------------------ volcano

    private static VolcanoDisaster startPeakVolcano(GameTestHelper helper, int intensity) {
        DisasterConfig.SPAWN_PROTECTION_RADIUS.set(0);
        BlockPos center = helper.absolutePos(new BlockPos(16, 4, 16));
        VolcanoDisaster d = (VolcanoDisaster) DisasterManager.start(helper.getLevel(), DisasterType.VOLCANO, Vec3.atBottomCenterOf(center), intensity);
        d.skipTo(Phase.PEAK);
        return d;
    }

    /** Blocks in the arena column range that the volcano produces (cone rock, lava, scorch). */
    private static int volcanicBlocks(GameTestHelper helper, boolean cooledOnly) {
        int n = 0;
        for (int x = -8; x < 40; x++)
            for (int y = 2; y < 14; y++)
                for (int z = -8; z < 40; z++) {
                    var st = helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(x, y, z)));
                    if (st.is(Blocks.BASALT) || st.is(Blocks.BLACKSTONE)) n++;
                    else if (!cooledOnly && (st.is(Blocks.MAGMA_BLOCK) || st.is(Blocks.TUFF) || st.is(Blocks.LAVA))) n++;
                }
        return n;
    }

    private static void volcanoErupts(GameTestHelper helper) {
        VolcanoDisaster volcano = startPeakVolcano(helper, 3);
        helper.succeedWhen(() -> {
            helper.assertTrue(DisasterManager.active(helper.getLevel()).contains(volcano), "volcano should be active");
            helper.assertTrue(volcano.hasCone(), "flat arena should get a cone");
            helper.assertTrue(volcano.coneBlocks() >= 30, "cone not built yet (" + volcano.coneBlocks() + ")");
            helper.assertTrue(volcano.bombsLaunched() >= 4, "not enough lava bombs yet (" + volcano.bombsLaunched() + ")");
            helper.assertTrue(volcano.blasts() >= 1, "no eruption blast yet");
            helper.assertTrue(volcano.lavaPlaced() >= 3, "no lava flow yet (" + volcano.lavaPlaced() + ")");
            int found = volcanicBlocks(helper, false);
            helper.assertTrue(found >= 30, "too few volcanic blocks in the world (" + found + ")");
            DisasterManager.stop(helper.getLevel(), volcano.id(), false);
        });
    }

    private static void volcanoLavaCools(GameTestHelper helper) {
        DisasterConfig.VOLCANO_COOLING_TICKS.set(60);
        VolcanoDisaster volcano = startPeakVolcano(helper, 3);
        helper.succeedWhen(() -> {
            helper.assertTrue(volcano.lavaCooled() >= 4, "lava has not crusted over yet (" + volcano.lavaCooled() + "/" + volcano.lavaPlaced() + ")");
            helper.assertTrue(volcano.rockFormed() >= 3, "crust has not turned into rock yet (" + volcano.rockFormed() + ")");
            helper.assertTrue(volcanicBlocks(helper, true) >= 3, "no basalt / blackstone found");
            DisasterConfig.VOLCANO_COOLING_TICKS.set(900);
            DisasterManager.stop(helper.getLevel(), volcano.id(), false);
        });
    }

    private static void volcanoRespectsBlockToggle(GameTestHelper helper) {
        DisasterConfig.BLOCK_DESTRUCTION.set(false);
        java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> before = new java.util.HashMap<>();
        for (int x = -6; x < 38; x++)
            for (int y = 3; y < 14; y++)
                for (int z = -6; z < 38; z++) {
                    BlockPos p = helper.absolutePos(new BlockPos(x, y, z));
                    before.put(p, helper.getLevel().getBlockState(p));
                }
        VolcanoDisaster volcano = startPeakVolcano(helper, 5);
        helper.runAfterDelay(300, () -> {
            int count = 0;
            StringBuilder changed = new StringBuilder();
            for (var e : before.entrySet()) {
                var now = helper.getLevel().getBlockState(e.getKey());
                if (now != e.getValue()) {
                    count++;
                    if (count <= 5) changed.append(helper.relativePos(e.getKey()).toShortString()).append(' ')
                            .append(e.getValue().getBlock().getDescriptionId()).append("->").append(now.getBlock().getDescriptionId()).append("; ");
                }
            }
            int bombs = volcano.bombsLaunched();
            DisasterConfig.BLOCK_DESTRUCTION.set(true);
            DisasterManager.stop(helper.getLevel(), volcano.id(), false);
            helper.assertTrue(bombs > 0, "the volcano should still erupt (bombs) with destruction disabled");
            helper.assertTrue(count == 0, "blocks changed although destruction is disabled: " + count + " " + changed);
            helper.succeed();
        });
    }

    private static void volcanoTickCost(GameTestHelper helper) {
        VolcanoDisaster volcano = startPeakVolcano(helper, 3);
        for (int i = 0; i < 6; i++) helper.spawn(EntityType.COW, new BlockPos(2 + i * 5, 4, 3));
        helper.runAfterDelay(300, () -> {
            double cost = DisasterManager.averageTickCostMs(helper.getLevel());
            DisasterManager.stop(helper.getLevel(), volcano.id(), false);
            RealisticDisasters.LOGGER.info("Volcano intensity 3 average server cost: {} ms/tick (bombs {}, lava {}, cone {}, lightning {})",
                    String.format("%.3f", cost), volcano.bombsLaunched(), volcano.lavaPlaced(), volcano.coneBlocks(), volcano.lightningStrikes());
            helper.assertTrue(cost < 2.0, "volcano too expensive: " + cost + " ms/tick");
            helper.succeed();
        });
    }

    private DisasterGameTests() {}
}

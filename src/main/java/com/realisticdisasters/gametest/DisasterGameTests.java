package com.realisticdisasters.gametest;

import java.util.function.Consumer;

import com.realisticdisasters.RealisticDisasters;
import com.realisticdisasters.config.DisasterConfig;
import com.realisticdisasters.disaster.Disaster;
import com.realisticdisasters.disaster.DisasterManager;
import com.realisticdisasters.disaster.DisasterType;
import com.realisticdisasters.disaster.Phase;
import com.realisticdisasters.disaster.tsunami.TsunamiDisaster;
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
        FUNCTIONS.register("tsunami_floods_and_recedes", () -> DisasterGameTests::tsunamiFloodsAndRecedes);
        FUNCTIONS.register("tsunami_respects_block_toggle", () -> DisasterGameTests::tsunamiRespectsBlockToggle);
        FUNCTIONS.register("tsunami_intensity3_tick_cost", () -> DisasterGameTests::tsunamiTickCost);
        FUNCTIONS.register("tsunami_forces_and_debris", () -> DisasterGameTests::tsunamiForcesAndDebris);
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
        addTsunami(event, "tsunami_floods_and_recedes", event.registerEnvironment(ModRegistries.id("env_tsunami_flood")), 1600);
        addTsunami(event, "tsunami_respects_block_toggle", event.registerEnvironment(ModRegistries.id("env_tsunami_toggle")), 1200);
        addTsunami(event, "tsunami_intensity3_tick_cost", event.registerEnvironment(ModRegistries.id("env_tsunami_perf")), 500);
        addTsunami(event, "tsunami_forces_and_debris", event.registerEnvironment(ModRegistries.id("env_tsunami_forces")), 3200);
    }

    private static void addTsunami(RegisterGameTestsEvent event, String name, Holder<TestEnvironmentDefinition<?>> env, int maxTicks) {
        var fn = ResourceKey.create(Registries.TEST_FUNCTION, ModRegistries.id(name));
        var data = new TestData<>(env, ModRegistries.id("tsunami_arena"), maxTicks, 0, true, Rotation.NONE, false, 1, 1, true, 0);
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

    // ------------------------------------------------------------------ tsunami

    /** Starts a tsunami on the arena's beach; phases shortened by {@code durationScale} so a whole run fits a test. */
    private static TsunamiDisaster startTsunami(GameTestHelper helper, int intensity, double durationScale) {
        DisasterConfig.SPAWN_PROTECTION_RADIUS.set(0);
        double oldScale = DisasterConfig.TSUNAMI_DURATION_SCALE.get();
        DisasterConfig.TSUNAMI_DURATION_SCALE.set(durationScale);
        BlockPos beach = helper.absolutePos(new BlockPos(18, 5, 24));
        TsunamiDisaster t = (TsunamiDisaster) DisasterManager.start(helper.getLevel(), DisasterType.TSUNAMI,
                Vec3.atBottomCenterOf(beach), intensity);
        DisasterConfig.TSUNAMI_DURATION_SCALE.set(oldScale);
        return t;
    }

    private static java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> snapshot(GameTestHelper helper, int y0, int y1) {
        java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> map = new java.util.HashMap<>();
        for (int x = 0; x < 56; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = 0; z < 48; z++) {
                    BlockPos p = helper.absolutePos(new BlockPos(x, y, z));
                    map.put(p, helper.getLevel().getBlockState(p));
                }
        return map;
    }

    private static int countLand(GameTestHelper helper, int y, java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> test) {
        int n = 0;
        for (int x = 20; x < 46; x++)
            for (int z = 6; z < 42; z++)
                if (test.test(helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(x, y, z))))) n++;
        return n;
    }

    /**
     * The full cycle: the sea draws back, the wave floods the land, then everything it placed drains away again.
     * Afterwards every block of the original sea and the pond is still water, no flood water is left on land, and the
     * damage (torn-out plants, eroded turf) stays.
     */
    private static void tsunamiFloodsAndRecedes(GameTestHelper helper) {
        var before = snapshot(helper, 1, 11);
        java.util.List<BlockPos> originalWater = before.entrySet().stream()
                .filter(e -> e.getValue().getFluidState().isSource()).map(java.util.Map.Entry::getKey).toList();
        int plantsBefore = countLand(helper, 5, s -> s.is(Blocks.POPPY) || s.is(Blocks.SHORT_GRASS));
        int grassBefore = countLand(helper, 4, s -> s.is(Blocks.GRASS_BLOCK));
        TsunamiDisaster t = startTsunami(helper, 1, 0.3);
        helper.assertTrue(t.hasSea(), "tsunami did not find the arena's sea");
        // The arena's sea lies toward -x (neighbouring test arenas may hold seas too, so allow some angle).
        helper.assertTrue(t.seaDirX() < -0.6, "sea should be toward -x, got " + t.seaDirX() + "," + t.seaDirZ());
        helper.assertTrue(t.waterY() == helper.absolutePos(new BlockPos(0, 4, 0)).getY(), "wrong sea level " + t.waterY());
        Pig pig = helper.spawn(EntityType.PIG, new BlockPos(28, 5, 30));
        double pigStartX = pig.getX();

        int[] maxWithdrawn = {0}, maxPlaced = {0}, maxLandWater = {0};
        helper.onEachTick(() -> {
            maxWithdrawn[0] = Math.max(maxWithdrawn[0], t.withdrawnCount());
            maxPlaced[0] = Math.max(maxPlaced[0], t.placedWaterCount());
            if (helper.getTick() % 10 == 0) {
                maxLandWater[0] = Math.max(maxLandWater[0], countLand(helper, 5, s -> s.is(Blocks.WATER)));
            }
        });

        helper.succeedWhen(() -> {
            helper.assertTrue(maxWithdrawn[0] > 40, "the sea did not draw back (" + maxWithdrawn[0] + ")");
            helper.assertTrue(maxLandWater[0] > 300, "the land was not flooded (" + maxLandWater[0] + " water blocks at y5)");
            helper.assertTrue(t.isFinished(), "tsunami still running: " + t.phase() + " placed=" + t.placedWaterCount());
            helper.assertTrue(DisasterManager.blockQueue(helper.getLevel()).size() == 0, "block queue not drained yet");
            int leftover = 0;
            StringBuilder where = new StringBuilder();
            for (int y = 5; y <= 11; y++) {
                for (int x = 20; x < 46; x++)
                    for (int z = 6; z < 42; z++) {
                        var st = helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(x, y, z)));
                        if (st.getFluidState().isEmpty()) continue;
                        leftover++;
                        if (leftover <= 6) where.append(x).append(',').append(y).append(',').append(z)
                                .append(st.getFluidState().isSource() ? " src; " : " flow; ");
                    }
            }
            // the pond (24,5,24) is inside the counted area and must still be there
            helper.assertTrue(helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(24, 5, 24))).is(Blocks.WATER),
                    "the pre-existing pond was removed");
            helper.assertTrue(leftover == 1, "flood water left on land: " + (leftover - 1) + " blocks: " + where
                    + " placed=" + t.placedWaterCount() + " pending=" + t.pendingCount() + " maxPlaced=" + maxPlaced[0]);
            int missing = 0;
            for (BlockPos p : originalWater) if (!helper.getLevel().getBlockState(p).getFluidState().isSource()) missing++;
            helper.assertTrue(missing == 0, missing + " blocks of the original sea/pond are gone");
            int plantsAfter = countLand(helper, 5, s -> s.is(Blocks.POPPY) || s.is(Blocks.SHORT_GRASS));
            helper.assertTrue(plantsAfter < plantsBefore / 2, "plants not torn out (" + plantsAfter + "/" + plantsBefore + ")");
            int grassAfter = countLand(helper, 4, s -> s.is(Blocks.GRASS_BLOCK));
            helper.assertTrue(grassAfter < grassBefore - 30, "no erosion (" + grassAfter + "/" + grassBefore + ")");
            helper.assertTrue(pig.getX() - pigStartX > 3, "pig was not pushed/fled inland (" + (pig.getX() - pigStartX) + ")");
            RealisticDisasters.LOGGER.info("Tsunami test: withdrew {}, placed up to {} flood blocks, {} at y5; plants {} -> {}, grass {} -> {}",
                    maxWithdrawn[0], maxPlaced[0], maxLandWater[0], plantsBefore, plantsAfter, grassBefore, grassAfter);
        });
    }

    private static void tsunamiRespectsBlockToggle(GameTestHelper helper) {
        DisasterConfig.BLOCK_DESTRUCTION.set(false);
        var before = snapshot(helper, 1, 11);
        TsunamiDisaster t = startTsunami(helper, 3, 0.2);
        helper.succeedWhen(() -> {
            helper.assertTrue(t.isFinished(), "tsunami still running: " + t.phase());
            int count = 0;
            StringBuilder changed = new StringBuilder();
            for (var e : before.entrySet()) {
                var now = helper.getLevel().getBlockState(e.getKey());
                if (now != e.getValue() && tsunamiCouldCause(e.getValue(), now)) {
                    if (++count <= 5) changed.append(e.getKey().toShortString()).append(' ')
                            .append(e.getValue()).append("->").append(now).append("; ");
                }
            }
            DisasterConfig.BLOCK_DESTRUCTION.set(true);
            helper.assertTrue(count == 0, "blocks changed although destruction is disabled: " + count + " " + changed);
        });
    }

    /**
     * Whether a block change is one a tsunami makes (water added/removed, plants torn out, turf eroded). Other changes
     * in the arena come from neighbouring tests (tornado debris landing) or vanilla (grass dying), not from us.
     */
    private static boolean tsunamiCouldCause(net.minecraft.world.level.block.state.BlockState was,
                                             net.minecraft.world.level.block.state.BlockState now) {
        if (was.getFluidState().isEmpty() != now.getFluidState().isEmpty()) return true;
        if (was.is(Blocks.POPPY) || was.is(Blocks.SHORT_GRASS)) return true;
        return now.is(Blocks.SAND) || now.is(Blocks.GRAVEL) || now.is(Blocks.COARSE_DIRT);
    }

    private static int countIn(GameTestHelper helper, int x0, int x1, int y0, int y1, int z0, int z1,
                               net.minecraft.world.level.block.Block block) {
        int n = 0;
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++)
                    if (helper.getBlockState(new BlockPos(x, y, z)).is(block)) n++;
        return n;
    }

    /**
     * Material-dependent damage: an intensity-5 bore against a stone-brick sea wall (must hold), a wooden hut (must
     * partly fail and float off as debris), glass panes (must shatter) and a tree (must be uprooted).
     */
    private static void tsunamiForcesAndDebris(GameTestHelper helper) {
        for (int x = 22; x <= 26; x++)
            for (int z = 8; z <= 10; z++)
                for (int y = 5; y <= 7; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.STONE_BRICKS);
        for (int x = 22; x <= 27; x++)
            for (int z = 20; z <= 27; z++)
                for (int y = 5; y <= 7; y++)
                    if (x == 22 || x == 27 || z == 20 || z == 27) helper.setBlock(new BlockPos(x, y, z), Blocks.OAK_PLANKS);
        for (int z = 32; z <= 38; z++) helper.setBlock(new BlockPos(24, 5, z), Blocks.GLASS_PANE);
        for (int y = 5; y <= 9; y++) helper.setBlock(new BlockPos(29, y, 15), Blocks.OAK_LOG);
        for (int x = 27; x <= 31; x++)
            for (int z = 13; z <= 17; z++)
                for (int y = 8; y <= 10; y++)
                    if (helper.getBlockState(new BlockPos(x, y, z)).isAir())
                        helper.setBlock(new BlockPos(x, y, z), Blocks.OAK_LEAVES.defaultBlockState()
                                .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
        int planksBefore = countIn(helper, 22, 27, 5, 7, 20, 27, Blocks.OAK_PLANKS);
        TsunamiDisaster t = startTsunami(helper, 5, 0.4);
        int[] maxDebris = {0};
        helper.onEachTick(() -> maxDebris[0] = Math.max(maxDebris[0], t.debrisCount()));
        helper.succeedWhen(() -> {
            helper.assertTrue(t.isFinished(), "tsunami still running: " + t.phase());
            helper.assertTrue(DisasterManager.blockQueue(helper.getLevel()).size() == 0, "block queue not drained yet");
            int stone = countIn(helper, 22, 26, 5, 7, 8, 10, Blocks.STONE_BRICKS);
            helper.assertTrue(stone == 45, "the stone sea wall should hold (" + stone + "/45)");
            int planks = countIn(helper, 22, 27, 5, 7, 20, 27, Blocks.OAK_PLANKS);
            helper.assertTrue(planks < planksBefore * 0.8, "the wooden hut should partly fail (" + planks + "/" + planksBefore + ")");
            int glass = countIn(helper, 24, 24, 5, 5, 32, 38, Blocks.GLASS_PANE);
            helper.assertTrue(glass <= 2, "glass panes should shatter (" + glass + " left)");
            int logs = countIn(helper, 29, 29, 5, 9, 15, 15, Blocks.OAK_LOG);
            helper.assertTrue(logs == 0, "the tree should be uprooted (" + logs + " logs left)");
            helper.assertTrue(maxDebris[0] > 0, "no floating debris was produced");
            RealisticDisasters.LOGGER.info("Tsunami forces test: planks {} -> {}, glass 7 -> {}, max floating debris {}",
                    planksBefore, planks, glass, maxDebris[0]);
        });
    }

    /** Intensity 3 while the wave runs up and floods: block work + entity pushes must stay under 2 ms/tick. */
    private static void tsunamiTickCost(GameTestHelper helper) {
        TsunamiDisaster t = startTsunami(helper, 3, 1.0);
        t.skipTo(Phase.PEAK);
        for (int i = 0; i < 6; i++) helper.spawn(EntityType.COW, new BlockPos(24 + i * 4, 5, 14));
        helper.runAfterDelay(300, () -> {
            double cost = DisasterManager.averageTickCostMs(helper.getLevel());
            int placed = t.placedWaterCount();
            DisasterManager.stop(helper.getLevel(), t.id(), false);
            RealisticDisasters.LOGGER.info("Tsunami intensity 3 average server cost: {} ms/tick ({} flood blocks placed)",
                    String.format("%.3f", cost), placed);
            helper.assertTrue(placed > 500, "the flood did not progress (" + placed + " blocks)");
            helper.assertTrue(cost < 2.0, "tsunami too expensive: " + cost + " ms/tick");
            helper.succeed();
        });
    }

    private DisasterGameTests() {}
}

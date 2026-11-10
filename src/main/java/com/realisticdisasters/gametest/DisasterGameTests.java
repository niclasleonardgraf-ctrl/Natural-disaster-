package com.realisticdisasters.gametest;

import java.util.function.Consumer;

import com.realisticdisasters.RealisticDisasters;
import com.realisticdisasters.config.DisasterConfig;
import com.realisticdisasters.disaster.Disaster;
import com.realisticdisasters.disaster.DisasterManager;
import com.realisticdisasters.disaster.DisasterType;
import com.realisticdisasters.disaster.Phase;
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
        FUNCTIONS.register("earthquake_fissures_and_crumbling", () -> DisasterGameTests::earthquakeFissuresAndCrumbling);
        FUNCTIONS.register("earthquake_respects_block_toggle", () -> DisasterGameTests::earthquakeRespectsBlockToggle);
        FUNCTIONS.register("earthquake_intensity3_tick_cost", () -> DisasterGameTests::earthquakeTickCost);
        FUNCTIONS.register("earthquake_mobs_flee", () -> DisasterGameTests::earthquakeMobsFlee);
        FUNCTIONS.register("earthquake_tower_topples", () -> DisasterGameTests::earthquakeTowerTopples);
        FUNCTIONS.register("earthquake_fixtures_react", () -> DisasterGameTests::earthquakeFixturesReact);
        FUNCTIONS.register("earthquake_minor_spares_houses", () -> DisasterGameTests::earthquakeMinorSparesHouses);
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
        add(event, "earthquake_fissures_and_crumbling", event.registerEnvironment(ModRegistries.id("env_quake_damage")), 700, "earthquake_arena");
        add(event, "earthquake_respects_block_toggle", event.registerEnvironment(ModRegistries.id("env_quake_toggle")), 300, "earthquake_arena");
        add(event, "earthquake_intensity3_tick_cost", event.registerEnvironment(ModRegistries.id("env_quake_perf")), 400, "earthquake_arena");
        add(event, "earthquake_mobs_flee", event.registerEnvironment(ModRegistries.id("env_quake_flee")), 300, "earthquake_arena");
        add(event, "earthquake_tower_topples", event.registerEnvironment(ModRegistries.id("env_quake_topple")), 260, "earthquake_arena");
        add(event, "earthquake_fixtures_react", event.registerEnvironment(ModRegistries.id("env_quake_fixtures")), 200, "earthquake_arena");
        add(event, "earthquake_minor_spares_houses", event.registerEnvironment(ModRegistries.id("env_quake_minor")), 400, "earthquake_arena");
    }

    private static void add(RegisterGameTestsEvent event, String name, Holder<TestEnvironmentDefinition<?>> env, int maxTicks) {
        var fn = ResourceKey.create(Registries.TEST_FUNCTION, ModRegistries.id(name));
        var data = new TestData<>(env, ModRegistries.id("tornado_arena"), maxTicks, 0, true, Rotation.NONE, false, 1, 1, true, 0);
        event.registerTest(ModRegistries.id(name), new FunctionGameTestInstance(fn, data));
    }

    private static void add(RegisterGameTestsEvent event, String name, Holder<TestEnvironmentDefinition<?>> env, int maxTicks, String structure) {
        var fn = ResourceKey.create(Registries.TEST_FUNCTION, ModRegistries.id(name));
        var data = new TestData<>(env, ModRegistries.id(structure), maxTicks, 0, true, Rotation.NONE, false, 1, 1, true, 0);
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

    // ------------------------------------------------------------------ earthquake

    /** Ends the quake and removes debris still in flight, so it can't land in the next test's arena. */
    private static void stopQuake(GameTestHelper helper, Disaster quake) {
        DisasterManager.stop(helper.getLevel(), quake.id(), false);
        helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.FallingBlockEntity.class,
                helper.getBounds().inflate(32)).forEach(net.minecraft.world.entity.Entity::discard);
    }

    /** Epicentre in the middle of the earthquake arena, on the grass (y 9 is the top soil). */
    private static Disaster startEarthquake(GameTestHelper helper, int intensity, Phase phase) {
        DisasterConfig.SPAWN_PROTECTION_RADIUS.set(0);
        BlockPos center = helper.absolutePos(new BlockPos(20, 10, 20));
        Disaster d = DisasterManager.start(helper.getLevel(), DisasterType.EARTHQUAKE, Vec3.atBottomCenterOf(center), intensity);
        if (phase != Phase.WARNING) d.skipTo(phase);
        return d;
    }

    private static java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> snapshot(GameTestHelper helper, int y0, int y1) {
        java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> map = new java.util.HashMap<>();
        for (int x = 0; x < 40; x++)
            for (int y = y0; y < y1; y++)
                for (int z = 0; z < 40; z++) {
                    BlockPos p = helper.absolutePos(new BlockPos(x, y, z));
                    map.put(p, helper.getLevel().getBlockState(p));
                }
        return map;
    }

    private static void earthquakeFissuresAndCrumbling(GameTestHelper helper) {
        var ground = snapshot(helper, 4, 10);
        var structures = snapshot(helper, 10, 16);
        Disaster quake = startEarthquake(helper, 4, Phase.PEAK);
        java.util.Set<Integer> debrisSeen = new java.util.HashSet<>();
        helper.onEachTick(() -> helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.FallingBlockEntity.class,
                helper.getBounds().inflate(4)).forEach(fb -> debrisSeen.add(fb.getId())));

        helper.succeedWhen(() -> {
            helper.assertTrue(DisasterManager.active(helper.getLevel()).contains(quake), "earthquake should be active");
            long opened = ground.entrySet().stream()
                    .filter(e -> !e.getValue().isAir() && (helper.getLevel().getBlockState(e.getKey()).isAir()
                            || helper.getLevel().getBlockState(e.getKey()).is(Blocks.WATER))).count();
            helper.assertTrue(opened >= 20, "fissures have not opened enough ground yet (" + opened + " blocks)");
            long damaged = structures.entrySet().stream()
                    .filter(e -> !e.getValue().isAir() && helper.getLevel().getBlockState(e.getKey()) != e.getValue()).count();
            helper.assertTrue(damaged >= 6, "structures have not crumbled enough yet (" + damaged + " blocks)");
            helper.assertTrue(debrisSeen.size() >= 3, "no falling-block debris yet (" + debrisSeen.size() + ")");
            helper.assertTrue(helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(12, 10, 12))).is(Blocks.CRACKED_STONE_BRICKS)
                    || structures.entrySet().stream().anyMatch(e -> helper.getLevel().getBlockState(e.getKey()).is(Blocks.CRACKED_STONE_BRICKS)),
                    "stone bricks should crack");
            stopQuake(helper, quake);
        });
    }

    private static void earthquakeRespectsBlockToggle(GameTestHelper helper) {
        DisasterConfig.BLOCK_DESTRUCTION.set(false);
        // Inner area only: the arena edges sit next to the neighbouring tests' arenas.
        var before = snapshot(helper, 0, 20);
        before.keySet().removeIf(p -> {
            BlockPos r = helper.relativePos(p);
            return r.getX() < 4 || r.getX() > 35 || r.getZ() < 4 || r.getZ() > 35;
        });
        Disaster quake = startEarthquake(helper, 5, Phase.PEAK);
        int[] debris = {0};
        helper.onEachTick(() -> debris[0] += helper.getLevel().getEntitiesOfClass(
                net.minecraft.world.entity.item.FallingBlockEntity.class, helper.getBounds().deflate(4)).size());
        helper.runAfterDelay(220, () -> {
            int count = 0;
            StringBuilder changed = new StringBuilder();
            for (var e : before.entrySet()) {
                var now = helper.getLevel().getBlockState(e.getKey());
                if (now != e.getValue() && count++ < 5) {
                    changed.append(helper.relativePos(e.getKey()).toShortString()).append(' ')
                            .append(e.getValue().getBlock().getDescriptionId()).append("->").append(now.getBlock().getDescriptionId()).append("; ");
                }
            }
            DisasterConfig.BLOCK_DESTRUCTION.set(true);
            stopQuake(helper, quake);
            helper.assertTrue(count == 0, "blocks changed although destruction is disabled: " + count + " " + changed);
            helper.assertTrue(debris[0] == 0, "falling-block debris spawned although destruction is disabled");
            helper.succeed();
        });
    }

    private static void earthquakeTickCost(GameTestHelper helper) {
        Disaster quake = startEarthquake(helper, 3, Phase.PEAK);
        for (int i = 0; i < 6; i++) helper.spawn(EntityType.COW, new BlockPos(6 + i * 5, 10, 8));
        helper.runAfterDelay(300, () -> {
            double cost = DisasterManager.averageTickCostMs(helper.getLevel());
            stopQuake(helper, quake);
            RealisticDisasters.LOGGER.info("Earthquake intensity 3 average server cost: {} ms/tick", String.format("%.3f", cost));
            helper.assertTrue(cost < 2.0, "earthquake too expensive: " + cost + " ms/tick");
            helper.succeed();
        });
    }

    /**
     * The stone-brick pillar (x 12-13, y 10-15, z 12) snapped and pushed toward +x must fall over like a rigid body:
     * its blocks land spread out several blocks away, not in a heap at its foot.
     */
    private static void earthquakeTowerTopples(GameTestHelper helper) {
        DisasterConfig.EARTHQUAKE_FISSURES.set(false);
        var quakeD = startEarthquake(helper, 4, Phase.PEAK);
        var quake = (com.realisticdisasters.disaster.earthquake.EarthquakeDisaster) quakeD;
        helper.assertTrue(quake.toppleNow(helper.absolutePos(new BlockPos(12, 15, 12)), new Vec3(1, 0, 0)), "pillar did not topple");
        helper.runAfterDelay(150, () -> {
            DisasterConfig.EARTHQUAKE_FISSURES.set(true);
            stopQuake(helper, quake);
            int far = 0, standing = 0;
            StringBuilder where = new StringBuilder();
            for (int x = 0; x < 40; x++)
                for (int y = 10; y < 16; y++)
                    for (int z = 6; z < 19; z++) {
                        var st = helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(x, y, z)));
                        if (!st.is(Blocks.STONE_BRICKS) && !st.is(Blocks.CRACKED_STONE_BRICKS)) continue;
                        where.append(x).append(',').append(y).append(',').append(z).append(' ');
                        if (x >= 16) far++;
                        if ((x == 12 || x == 13) && z == 12 && y >= 12) standing++;
                    }
            helper.assertTrue(standing == 0, "the pillar is still standing (" + standing + " blocks): " + where);
            helper.assertTrue(far >= 3, "the pillar did not fall over sideways (" + far + " blocks landed 3+ blocks away): " + where);
            helper.succeed();
        });
    }

    /** Doors bang and hanging lanterns fall when the shaking reaches them. */
    private static void earthquakeFixturesReact(GameTestHelper helper) {
        DisasterConfig.EARTHQUAKE_FISSURES.set(false);
        BlockPos door = new BlockPos(16, 10, 32), lantern = new BlockPos(27, 13, 27);
        helper.setBlock(door, Blocks.OAK_DOOR.defaultBlockState());
        helper.setBlock(door.above(), Blocks.OAK_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
        helper.setBlock(lantern, Blocks.LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true));
        var quake = (com.realisticdisasters.disaster.earthquake.EarthquakeDisaster) startEarthquake(helper, 4, Phase.PEAK);
        boolean[] doorMoved = {false};
        helper.onEachTick(() -> {
            quake.shakeBlock(helper.absolutePos(door), 3f);
            quake.shakeBlock(helper.absolutePos(lantern), 3f);
            var st = helper.getLevel().getBlockState(helper.absolutePos(door));
            if (st.getBlock() instanceof net.minecraft.world.level.block.DoorBlock && st.getValue(net.minecraft.world.level.block.DoorBlock.OPEN)) doorMoved[0] = true;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(doorMoved[0], "the door never swung open");
            helper.assertTrue(!helper.getLevel().getBlockState(helper.absolutePos(lantern)).is(Blocks.LANTERN), "the hanging lantern is still up");
            DisasterConfig.EARTHQUAKE_FISSURES.set(true);
            stopQuake(helper, quake);
        });
    }

    /** A minor (intensity 1) quake rattles things but leaves an ordinary house standing: roof and walls intact. */
    private static void earthquakeMinorSparesHouses(GameTestHelper helper) {
        DisasterConfig.EARTHQUAKE_FISSURES.set(false);
        var before = snapshot(helper, 10, 15);
        before.keySet().removeIf(p -> {
            BlockPos r = helper.relativePos(p);
            return r.getX() < 24 || r.getX() > 30 || r.getZ() < 24 || r.getZ() > 30 || before.get(p).isAir()
                    || before.get(p).is(Blocks.GLASS);
        });
        Disaster quake = startEarthquake(helper, 1, Phase.PEAK);
        helper.runAfterDelay(300, () -> {
            long changed = before.entrySet().stream().filter(e -> helper.getLevel().getBlockState(e.getKey()) != e.getValue()).count();
            DisasterConfig.EARTHQUAKE_FISSURES.set(true);
            stopQuake(helper, quake);
            helper.assertTrue(changed <= 2, "a minor quake damaged the house (" + changed + " blocks)");
            helper.succeed();
        });
    }

    /** During the warning, animals near the epicentre run away from it. */
    private static void earthquakeMobsFlee(GameTestHelper helper) {
        Pig pig = helper.spawn(EntityType.PIG, new BlockPos(17, 10, 20));
        Disaster quake = startEarthquake(helper, 3, Phase.WARNING);
        Vec3 epicentre = quake.pos();
        double startDist = Math.hypot(pig.getX() - epicentre.x, pig.getZ() - epicentre.z);
        helper.succeedWhen(() -> {
            double dist = Math.hypot(pig.getX() - epicentre.x, pig.getZ() - epicentre.z);
            helper.assertTrue(dist > startDist + 6, "pig did not flee (distance " + String.format("%.1f", dist) + ")");
            stopQuake(helper, quake);
        });
    }

    private DisasterGameTests() {}
}

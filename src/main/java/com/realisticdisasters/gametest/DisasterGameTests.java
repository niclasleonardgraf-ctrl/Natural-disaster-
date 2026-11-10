package com.realisticdisasters.gametest;

import java.util.function.Consumer;

import com.realisticdisasters.RealisticDisasters;
import com.realisticdisasters.config.DisasterConfig;
import com.realisticdisasters.disaster.Disaster;
import com.realisticdisasters.disaster.DisasterManager;
import com.realisticdisasters.disaster.DisasterType;
import com.realisticdisasters.disaster.Phase;
import com.realisticdisasters.disaster.tsunami.TsunamiDisaster;
import com.realisticdisasters.disaster.meteor.MeteorShowerDisaster;
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
import com.realisticdisasters.disaster.volcano.VolcanoDisaster;

/**
 * Automated in-world tests (run with {@code ./gradlew runGameTestServer}). Only registered in development.
 */
public final class DisasterGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS =
            DeferredRegister.create(Registries.TEST_FUNCTION, RealisticDisasters.MODID);

    static {
        FUNCTIONS.register("tornado_damages_terrain", () -> DisasterGameTests::tornadoDamagesTerrain);
        FUNCTIONS.register("blizzard_buries_and_pushes", () -> h -> WindStormGameTests.buriesAndPushes(h, DisasterType.BLIZZARD));
        FUNCTIONS.register("blizzard_respects_block_toggle", () -> h -> WindStormGameTests.respectsBlockToggle(h, DisasterType.BLIZZARD));
        FUNCTIONS.register("blizzard_intensity3_tick_cost", () -> h -> WindStormGameTests.tickCost(h, DisasterType.BLIZZARD));
        FUNCTIONS.register("sandstorm_buries_and_pushes", () -> h -> WindStormGameTests.buriesAndPushes(h, DisasterType.SANDSTORM));
        FUNCTIONS.register("sandstorm_respects_block_toggle", () -> h -> WindStormGameTests.respectsBlockToggle(h, DisasterType.SANDSTORM));
        FUNCTIONS.register("sandstorm_intensity3_tick_cost", () -> h -> WindStormGameTests.tickCost(h, DisasterType.SANDSTORM));
        FUNCTIONS.register("sandstorm_builds_piles", () -> WindStormGameTests::sandstormBuildsPiles);
        FUNCTIONS.register("windstorm_lee_shelters", () -> WindStormGameTests::leeShelters);
        FUNCTIONS.register("windstorm_weathers_fires_and_crops", () -> WindStormGameTests::weathersFiresAndCrops);
        FUNCTIONS.register("tornado_respects_block_toggle", () -> DisasterGameTests::tornadoRespectsBlockToggle);
        FUNCTIONS.register("tsunami_floods_and_recedes", () -> DisasterGameTests::tsunamiFloodsAndRecedes);
        FUNCTIONS.register("tsunami_respects_block_toggle", () -> DisasterGameTests::tsunamiRespectsBlockToggle);
        FUNCTIONS.register("tsunami_intensity3_tick_cost", () -> DisasterGameTests::tsunamiTickCost);
        FUNCTIONS.register("tsunami_forces_and_debris", () -> DisasterGameTests::tsunamiForcesAndDebris);
        FUNCTIONS.register("tornado_intensity3_tick_cost", () -> DisasterGameTests::tornadoTickCost);
        FUNCTIONS.register("meteor_impact_makes_crater", () -> DisasterGameTests::meteorImpactMakesCrater);
        FUNCTIONS.register("meteor_core_contains_ores", () -> DisasterGameTests::meteorCoreContainsOres);
        FUNCTIONS.register("meteor_respects_block_toggle", () -> DisasterGameTests::meteorRespectsBlockToggle);
        FUNCTIONS.register("meteor_intensity3_tick_cost", () -> DisasterGameTests::meteorTickCost);
        FUNCTIONS.register("meteor_sync_payload_roundtrip", () -> DisasterGameTests::meteorSyncRoundTrip);
        FUNCTIONS.register("meteor_blast_shatters_glass_and_throws_ejecta", () -> DisasterGameTests::meteorBlastAndEjecta);
        FUNCTIONS.register("tornado_shelter_protects", () -> DisasterGameTests::tornadoShelterProtects);
        FUNCTIONS.register("tornado_breaks_glass_and_snaps_trees", () -> DisasterGameTests::tornadoBreaksGlassAndSnapsTrees);
        FUNCTIONS.register("wildfire_burns_and_chars", () -> DisasterGameTests::wildfireBurnsAndChars);
        FUNCTIONS.register("wildfire_respects_block_toggle", () -> DisasterGameTests::wildfireRespectsBlockToggle);
        FUNCTIONS.register("wildfire_intensity3_tick_cost", () -> DisasterGameTests::wildfireTickCost);
        FUNCTIONS.register("wildfire_stopped_by_river", () -> DisasterGameTests::wildfireStoppedByRiver);
        FUNCTIONS.register("wildfire_put_out_by_water", () -> DisasterGameTests::wildfirePutOutByWater);
    }

    private static void addTsunami(RegisterGameTestsEvent event, String name, Holder<TestEnvironmentDefinition<?>> env, int maxTicks) {
        var fn = ResourceKey.create(Registries.TEST_FUNCTION, ModRegistries.id(name));
        var data = new TestData<>(env, ModRegistries.id("tsunami_arena"), maxTicks, 0, true, Rotation.NONE, false, 1, 1, true, 0);
        event.registerTest(ModRegistries.id(name), new FunctionGameTestInstance(fn, data));
    }

    public static void register(IEventBus modBus) {
        FUNCTIONS.register(modBus);
        FUNCTIONS.register("earthquake_fissures_and_crumbling", () -> DisasterGameTests::earthquakeFissuresAndCrumbling);
        FUNCTIONS.register("earthquake_respects_block_toggle", () -> DisasterGameTests::earthquakeRespectsBlockToggle);
        FUNCTIONS.register("earthquake_intensity3_tick_cost", () -> DisasterGameTests::earthquakeTickCost);
        FUNCTIONS.register("earthquake_mobs_flee", () -> DisasterGameTests::earthquakeMobsFlee);
        FUNCTIONS.register("earthquake_tower_topples", () -> DisasterGameTests::earthquakeTowerTopples);
        FUNCTIONS.register("earthquake_fixtures_react", () -> DisasterGameTests::earthquakeFixturesReact);
        FUNCTIONS.register("earthquake_minor_spares_houses", () -> DisasterGameTests::earthquakeMinorSparesHouses);
        FUNCTIONS.register("volcano_erupts", () -> DisasterGameTests::volcanoErupts);
        FUNCTIONS.register("volcano_lava_cools", () -> DisasterGameTests::volcanoLavaCools);
        FUNCTIONS.register("volcano_respects_block_toggle", () -> DisasterGameTests::volcanoRespectsBlockToggle);
        FUNCTIONS.register("volcano_intensity3_tick_cost", () -> DisasterGameTests::volcanoTickCost);
        FUNCTIONS.register("volcano_pyroclastic_flow", () -> DisasterGameTests::volcanoPyroclasticFlow);
        FUNCTIONS.register("volcano_mountain_scales", () -> DisasterGameTests::volcanoMountainScales);
        modBus.addListener(DisasterGameTests::registerTests);
        FUNCTIONS.register("wildfire_heats_the_world", () -> DisasterGameTests::wildfireHeatsTheWorld);
        FUNCTIONS.register("wildfire_whirl_lofts_objects", () -> DisasterGameTests::wildfireWhirlLoftsObjects);
    }

    private static void registerTests(RegisterGameTestsEvent event) {
        // Separate environments => separate batches, so tests that flip global config never overlap.
        add(event, "tornado_damages_terrain", event.registerEnvironment(ModRegistries.id("env_terrain")), 500);
        add(event, "tornado_respects_block_toggle", event.registerEnvironment(ModRegistries.id("env_toggle")), 300);
        add(event, "tornado_intensity3_tick_cost", event.registerEnvironment(ModRegistries.id("env_perf")), 400);
        add(event, "tornado_shelter_protects", event.registerEnvironment(ModRegistries.id("env_shelter")), 300);
        add(event, "tornado_breaks_glass_and_snaps_trees", event.registerEnvironment(ModRegistries.id("env_materials")), 400);
        add(event, "earthquake_fissures_and_crumbling", event.registerEnvironment(ModRegistries.id("env_quake_damage")), 700, "earthquake_arena");
        add(event, "earthquake_respects_block_toggle", event.registerEnvironment(ModRegistries.id("env_quake_toggle")), 300, "earthquake_arena");
        add(event, "earthquake_intensity3_tick_cost", event.registerEnvironment(ModRegistries.id("env_quake_perf")), 400, "earthquake_arena");
        add(event, "earthquake_mobs_flee", event.registerEnvironment(ModRegistries.id("env_quake_flee")), 300, "earthquake_arena");
        add(event, "earthquake_tower_topples", event.registerEnvironment(ModRegistries.id("env_quake_topple")), 260, "earthquake_arena");
        add(event, "earthquake_fixtures_react", event.registerEnvironment(ModRegistries.id("env_quake_fixtures")), 200, "earthquake_arena");
        add(event, "earthquake_minor_spares_houses", event.registerEnvironment(ModRegistries.id("env_quake_minor")), 400, "earthquake_arena");
        addWildfire(event, "wildfire_burns_and_chars", event.registerEnvironment(ModRegistries.id("env_wildfire_terrain")), 1400);
        addWildfire(event, "wildfire_respects_block_toggle", event.registerEnvironment(ModRegistries.id("env_wildfire_toggle")), 300);
        addWildfire(event, "wildfire_intensity3_tick_cost", event.registerEnvironment(ModRegistries.id("env_wildfire_perf")), 400, "wildfire_field");
        addWildfire(event, "wildfire_stopped_by_river", event.registerEnvironment(ModRegistries.id("env_wildfire_river")), 800);
        addWildfire(event, "wildfire_put_out_by_water", event.registerEnvironment(ModRegistries.id("env_wildfire_water")), 300);
        addWildfire(event, "wildfire_heats_the_world", event.registerEnvironment(ModRegistries.id("env_wildfire_heat")), 900);
        addWildfire(event, "wildfire_whirl_lofts_objects", event.registerEnvironment(ModRegistries.id("env_wildfire_whirl")), 300);
        add(event, "volcano_erupts", event.registerEnvironment(ModRegistries.id("env_volcano_erupts")), 600);
        add(event, "volcano_lava_cools", event.registerEnvironment(ModRegistries.id("env_volcano_cooling")), 900);
        add(event, "volcano_respects_block_toggle", event.registerEnvironment(ModRegistries.id("env_volcano_toggle")), 400);
        add(event, "volcano_intensity3_tick_cost", event.registerEnvironment(ModRegistries.id("env_volcano_perf")), 400);
        add(event, "volcano_pyroclastic_flow", event.registerEnvironment(ModRegistries.id("env_volcano_pdc")), 400);
        add(event, "volcano_mountain_scales", event.registerEnvironment(ModRegistries.id("env_volcano_scale")), 400);
        addMeteor(event, "meteor_impact_makes_crater", event.registerEnvironment(ModRegistries.id("env_meteor_crater")), 300);
        addMeteor(event, "meteor_core_contains_ores", event.registerEnvironment(ModRegistries.id("env_meteor_ores")), 300);
        addMeteor(event, "meteor_respects_block_toggle", event.registerEnvironment(ModRegistries.id("env_meteor_toggle")), 300);
        addMeteor(event, "meteor_intensity3_tick_cost", event.registerEnvironment(ModRegistries.id("env_meteor_perf")), 400);
        addMeteor(event, "meteor_sync_payload_roundtrip", event.registerEnvironment(ModRegistries.id("env_meteor_sync")), 100);
        addMeteor(event, "meteor_blast_shatters_glass_and_throws_ejecta", event.registerEnvironment(ModRegistries.id("env_meteor_blast")), 300);
        addTsunami(event, "tsunami_floods_and_recedes", event.registerEnvironment(ModRegistries.id("env_tsunami_flood")), 1600);
        addTsunami(event, "tsunami_respects_block_toggle", event.registerEnvironment(ModRegistries.id("env_tsunami_toggle")), 1200);
        addTsunami(event, "tsunami_intensity3_tick_cost", event.registerEnvironment(ModRegistries.id("env_tsunami_perf")), 500);
        addTsunami(event, "tsunami_forces_and_debris", event.registerEnvironment(ModRegistries.id("env_tsunami_forces")), 3200);
        add(event, "blizzard_buries_and_pushes", event.registerEnvironment(ModRegistries.id("env_blizzard_effect")), 600);
        add(event, "blizzard_respects_block_toggle", event.registerEnvironment(ModRegistries.id("env_blizzard_toggle")), 300);
        add(event, "blizzard_intensity3_tick_cost", event.registerEnvironment(ModRegistries.id("env_blizzard_perf")), 400);
        add(event, "sandstorm_buries_and_pushes", event.registerEnvironment(ModRegistries.id("env_sandstorm_effect")), 600);
        add(event, "sandstorm_respects_block_toggle", event.registerEnvironment(ModRegistries.id("env_sandstorm_toggle")), 300);
        add(event, "sandstorm_intensity3_tick_cost", event.registerEnvironment(ModRegistries.id("env_sandstorm_perf")), 400);
        add(event, "sandstorm_builds_piles", event.registerEnvironment(ModRegistries.id("env_sandstorm_piles")), 100);
        add(event, "windstorm_lee_shelters", event.registerEnvironment(ModRegistries.id("env_windstorm_lee")), 400);
        add(event, "windstorm_weathers_fires_and_crops", event.registerEnvironment(ModRegistries.id("env_windstorm_weather")), 100);
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

    private static void add(RegisterGameTestsEvent event, String name, Holder<TestEnvironmentDefinition<?>> env, int maxTicks, String structure) {
        var fn = ResourceKey.create(Registries.TEST_FUNCTION, ModRegistries.id(name));
        var data = new TestData<>(env, ModRegistries.id(structure), maxTicks, 0, true, Rotation.NONE, false, 1, 1, true, 0);
        event.registerTest(ModRegistries.id(name), new FunctionGameTestInstance(fn, data));
    }

    private static void addMeteor(RegisterGameTestsEvent event, String name, Holder<TestEnvironmentDefinition<?>> env, int maxTicks) {
        var fn = ResourceKey.create(Registries.TEST_FUNCTION, ModRegistries.id(name));
        var data = new TestData<>(env, ModRegistries.id("meteor_arena"), maxTicks, 0, true, Rotation.NONE, false, 1, 1, true, 0);
        event.registerTest(ModRegistries.id(name), new FunctionGameTestInstance(fn, data));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Tests run one after another on neighbouring grid cells, and a stopped disaster's already-queued block changes
     * (and flying ejecta) keep landing for a moment. Start every test from a clean slate so one test's leftovers
     * can't change blocks in the next one's arena.
     */
    private static void cleanSlate(GameTestHelper helper) {
        var level = helper.getLevel();
        for (Disaster d : DisasterManager.active(level)) DisasterManager.stop(level, d.id(), false);
        DisasterManager.blockQueue(level).clear();
        for (var fb : level.getEntitiesOfClass(net.minecraft.world.entity.item.FallingBlockEntity.class, helper.getBounds().inflate(96))) {
            fb.discard();
        }
    }

    private static Disaster startPeakTornado(GameTestHelper helper, int intensity) {
        cleanSlate(helper);
        config(DisasterConfig.SPAWN_PROTECTION_RADIUS, 0);
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
        cleanSlate(helper);
        config(DisasterConfig.SPAWN_PROTECTION_RADIUS, 0);
        config(DisasterConfig.BLOCK_DESTRUCTION, false);
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
            config(DisasterConfig.BLOCK_DESTRUCTION, true);
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
        // A cow standing right where the mountain will rise: the ground must lift it, not bury it.
        net.minecraft.world.entity.animal.cow.Cow cow = helper.spawn(EntityType.COW, new BlockPos(24, 4, 16));
        cow.setNoAi(true);
        double cowStartY = cow.getY();
        VolcanoDisaster volcano = startPeakVolcano(helper, 3);
        helper.succeedWhen(() -> {
            // (it may well die from heat or bombs - but it must ride up on the new ground, never end up buried in it)
            helper.assertTrue(cow.getY() > cowStartY + 1.5, "cow was not lifted by the rising mountain (dy=" + (cow.getY() - cowStartY) + ")");
            helper.assertTrue(!cow.isAlive() || !helper.getLevel().getBlockState(cow.blockPosition()).isSuffocating(helper.getLevel(), cow.blockPosition()),
                    "cow is buried inside the mountain");
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
        for (int x = 0; x < 32; x++)
            for (int y = 3; y < 14; y++)
                for (int z = 0; z < 32; z++) {
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

    /** A pyroclastic flow races down the mountain, burns what it catches and leaves an ash deposit. */
    private static void volcanoPyroclasticFlow(GameTestHelper helper) {
        java.util.List<Pig> pigs = new java.util.ArrayList<>();
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4;
            Pig pig = helper.spawn(EntityType.PIG, new BlockPos(16 + (int) Math.round(Math.cos(a) * 13), 4, 16 + (int) Math.round(Math.sin(a) * 13)));
            pig.setNoAi(true);
            pigs.add(pig);
        }
        VolcanoDisaster volcano = startPeakVolcano(helper, 4);
        helper.runAfterDelay(5, volcano::triggerPyroclasticFlow);
        helper.succeedWhen(() -> {
            helper.assertTrue(volcano.pyroclasticFlows() >= 1, "no pyroclastic flow started");
            long hurt = pigs.stream().filter(p -> !p.isAlive() || p.getHealth() < p.getMaxHealth()).count();
            helper.assertTrue(hurt >= 1, "the flow didn't hit any of the animals around the vent");
            int ash = 0;
            for (int x = -16; x < 48; x++)
                for (int y = 3; y < 40; y++)
                    for (int z = -16; z < 48; z++)
                        if (helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(x, y, z))).is(ModRegistries.VOLCANIC_ASH.get())) ash++;
            helper.assertTrue(ash >= 3, "no ash deposit left behind (" + ash + ")");
            RealisticDisasters.LOGGER.info("Pyroclastic flow: {} of 8 animals hit, {} ash layers", hurt, ash);
            DisasterManager.stop(helper.getLevel(), volcano.id(), false);
        });
    }

    /** Volcano size scales with intensity up to a mountain the size of the largest vanilla peaks. */
    private static void volcanoMountainScales(GameTestHelper helper) {
        int last = 0;
        for (int i = 1; i <= 5; i++) {
            int h = VolcanoDisaster.plannedHeight(i);
            helper.assertTrue(h > last, "mountain height must grow with intensity");
            last = h;
        }
        helper.assertTrue(VolcanoDisaster.plannedHeight(5) >= 120, "intensity 5 should be mountain-sized");
        helper.assertTrue(VolcanoDisaster.plannedRadius(5) >= 180, "intensity 5 should have a mountain-sized base");
        // Grow a real intensity-4 mountain for a while and check its shape: summit over the vent, flanks lower.
        VolcanoDisaster volcano = startPeakVolcano(helper, 4);
        long start = System.nanoTime();
        helper.runAfterDelay(300, () -> {
            var level = helper.getLevel();
            BlockPos vent = volcano.ventPos();
            int summit = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, vent.getX() + 3, vent.getZ());
            int flank = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, vent.getX() + 14, vent.getZ());
            int base = helper.absolutePos(new BlockPos(0, 4, 0)).getY();
            RealisticDisasters.LOGGER.info("Volcano i4 (confined) after 300 ticks: growth {}, {} blocks placed, summit +{}, flank(14) +{}, planned {}x{}",
                    String.format("%.2f", volcano.growth()), volcano.coneBlocks(), summit - base, flank - base,
                    volcano.mountainHeight(), volcano.mountainRadius());
            DisasterManager.stop(level, volcano.id(), false);
            helper.assertTrue(volcano.coneBlocks() > 1000, "mountain is not growing (" + volcano.coneBlocks() + " blocks)");
            helper.assertTrue(summit - base >= 8, "summit too low (" + (summit - base) + ")");
            helper.assertTrue(summit > flank, "flanks should be lower than the summit");
            helper.succeed();
        });
    }

    private static VolcanoDisaster startPeakVolcano(GameTestHelper helper, int intensity) {
        DisasterConfig.SPAWN_PROTECTION_RADIUS.set(0);
        BlockPos center = helper.absolutePos(new BlockPos(16, 4, 16));
        VolcanoDisaster d = (VolcanoDisaster) DisasterManager.start(helper.getLevel(), DisasterType.VOLCANO, Vec3.atBottomCenterOf(center), intensity);
        d.confineForTest(18); // test arenas are only 37 blocks apart
        d.skipTo(Phase.PEAK);
        return d;
    }

    // ------------------------------------------------------------------ meteor shower (arena: grass at y=11, stone y 0-8)

    private static final int METEOR_GROUND = 11;

    /**
     * Writing one config value can reset others that were set just before it (the config is saved and re-read),
     * so tests only write values that differ and set their test-specific value last.
     */
    private static <T> void config(net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<T> value, T wanted) {
        if (!wanted.equals(value.get())) value.set(wanted);
    }

    /** Common meteor-test setup; call before setting the test's own config value. */
    private static void prepMeteor(GameTestHelper helper) {
        cleanSlate(helper);
        config(DisasterConfig.SPAWN_PROTECTION_RADIUS, 0);
        config(DisasterConfig.METEOR_STARTS_FIRES, false); // fire spreads by itself into neighbouring test arenas
        config(DisasterConfig.BLOCK_DESTRUCTION, true);
        config(DisasterConfig.METEOR_ORE_CHANCE, 0.12);
        // GameTestServer puts the world spawn on the first test batch, and the claim check (a fake-player
        // BreakBlockEvent) rightly honours vanilla spawn protection there. Craters are wide, so move the spawn away.
        var level = helper.getLevel();
        if (level.getRespawnData().pos().distSqr(helper.absolutePos(BlockPos.ZERO)) < 256 * 256) {
            level.setRespawnData(net.minecraft.world.level.storage.LevelData.RespawnData.of(level.dimension(),
                    helper.absolutePos(BlockPos.ZERO).offset(100_000, 0, 100_000), 0f, 0f));
        }
    }

    private static MeteorShowerDisaster startMeteorShower(GameTestHelper helper, int intensity) {
        prepMeteor(helper);
        return startPreparedShower(helper, intensity);
    }

    /** Starts a shower without touching config (the test has already set what it needs). */
    private static MeteorShowerDisaster startPreparedShower(GameTestHelper helper, int intensity) {
        var level = helper.getLevel();
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
        // melt, breccia lining, ejecta and burnt turf
        return s.is(Blocks.BLACKSTONE) || s.is(Blocks.BASALT) || s.is(Blocks.SMOOTH_BASALT) || s.is(Blocks.MAGMA_BLOCK)
                || s.is(Blocks.OBSIDIAN) || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.GRAVEL) || s.is(Blocks.COBBLESTONE)
                || s.is(Blocks.TUFF);
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
            if (air < 20) {
                StringBuilder col = new StringBuilder();
                for (int y = 4; y <= 14; y++) col.append(y).append('=').append(helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(16, y, 16))).getBlock().getDescriptionId().replace("block.minecraft.", "")).append(' ');
                helper.assertTrue(false, "crater bowl not carved yet (" + air + " air blocks) queue=" + DisasterManager.blockQueue(helper.getLevel()).size() + " col: " + col);
            }
            int burnt = countInArena(helper, DisasterGameTests::scorched);
            helper.assertTrue(burnt >= 25, "not enough lining / ejecta / scorched blocks yet (" + burnt + ")");
            int rim = 0; // raised rim: blocks above the old ground level just outside the bowl
            double craterR = shower.craterRadius(1f);
            for (int x = 0; x < 32; x++)
                for (int z = 0; z < 32; z++) {
                    double dd = Math.hypot(x + 0.5 - 16.5, z + 0.5 - 16.5);
                    if (dd > craterR * 0.95 && dd < craterR * 1.6
                            && !helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(x, METEOR_GROUND + 1, z))).isAir()) rim++;
                }
            helper.assertTrue(rim >= 10, "no raised rim yet (" + rim + ")");
            helper.assertTrue(maxPush[0] > 1.5, "pig was not thrown by the shockwave (" + maxPush[0] + ")");
            DisasterManager.stop(helper.getLevel(), shower.id(), false);
        });
    }

    private static void meteorCoreContainsOres(GameTestHelper helper) {
        prepMeteor(helper);
        config(DisasterConfig.METEOR_ORE_CHANCE, 1.0);
        MeteorShowerDisaster shower = startPreparedShower(helper, 3);
        shower.scheduleAt(arenaPoint(helper, 16.5, 16.5), 1.0f, 10);
        var ores = MeteorShowerDisaster.oreTable();
        helper.succeedWhen(() -> {
            helper.assertTrue(shower.impactsLanded() >= 1, "meteor has not landed yet");
            int found = countInArena(helper, s -> ores.contains(s.getBlock()));
            helper.assertTrue(found >= 4, "meteorite core has too few ores (" + found + ")");
            config(DisasterConfig.METEOR_ORE_CHANCE, 0.12);
            DisasterManager.stop(helper.getLevel(), shower.id(), false);
        });
    }

    private static void meteorRespectsBlockToggle(GameTestHelper helper) {
        prepMeteor(helper);
        config(DisasterConfig.BLOCK_DESTRUCTION, false);
        java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> before = new java.util.HashMap<>();
        for (int x = 0; x < 32; x++)
            for (int y = 0; y < 18; y++)
                for (int z = 0; z < 32; z++) {
                    BlockPos p = helper.absolutePos(new BlockPos(x, y, z));
                    before.put(p, helper.getLevel().getBlockState(p));
                }
        MeteorShowerDisaster shower = startPreparedShower(helper, 5);
        shower.scheduleAt(arenaPoint(helper, 16.5, 16.5), 1.5f, 10);
        shower.scheduleAt(arenaPoint(helper, 6.5, 8.5), 1.0f, 30);
        shower.scheduleAt(arenaPoint(helper, 25.5, 24.5), 1.0f, 50);
        helper.runAfterDelay(150, () -> {
            int count = 0;
            for (var e : before.entrySet()) if (helper.getLevel().getBlockState(e.getKey()) != e.getValue()) count++;
            int landed = shower.impactsLanded();
            config(DisasterConfig.BLOCK_DESTRUCTION, true);
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

    /** The air blast shatters windows well outside the crater, and real blocks are thrown out on ballistic arcs. */
    private static void meteorBlastAndEjecta(GameTestHelper helper) {
        java.util.List<BlockPos> glass = new java.util.ArrayList<>();
        for (int z = 14; z <= 18; z++) {
            for (int y = METEOR_GROUND + 1; y <= METEOR_GROUND + 2; y++) {
                BlockPos g = new BlockPos(2, y, z), pane = new BlockPos(30, y, z);
                helper.setBlock(g, Blocks.GLASS);
                helper.setBlock(pane, Blocks.GLASS_PANE);
                glass.add(g);
                glass.add(pane);
            }
        }
        MeteorShowerDisaster shower = startMeteorShower(helper, 3);
        int[] maxFlying = {0};
        helper.onEachTick(() -> maxFlying[0] = Math.max(maxFlying[0], helper.getLevel().getEntitiesOfClass(
                net.minecraft.world.entity.item.FallingBlockEntity.class, helper.getBounds().inflate(48)).size()));
        shower.scheduleAt(arenaPoint(helper, 16.5, 16.5), 1.0f, 10);
        helper.succeedWhen(() -> {
            helper.assertTrue(shower.impactsLanded() >= 1, "meteor has not landed yet");
            long intact = glass.stream().filter(g -> !helper.getLevel().getBlockState(helper.absolutePos(g)).isAir()).count();
            helper.assertTrue(intact == 0, intact + " of " + glass.size() + " glass blocks/panes survived the blast ~14 blocks out");
            helper.assertTrue(maxFlying[0] >= 5, "too few ejecta blocks flew (" + maxFlying[0] + ")");
            DisasterManager.stop(helper.getLevel(), shower.id(), false);
        });
    }

    /** The sync packet must decode to exactly what was sent (the client disconnects on leftover bytes). */
    private static void meteorSyncRoundTrip(GameTestHelper helper) {
        MeteorShowerDisaster shower = startMeteorShower(helper, 5);
        for (int i = 0; i < MeteorShowerDisaster.SLOT_COUNT + 2; i++) {
            shower.scheduleAt(arenaPoint(helper, 4.5 + i * 4, 16.5), 1.0f, 60 + i);
        }
        var sent = shower.toPayload();
        var buf = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),
                helper.getLevel().registryAccess());
        com.realisticdisasters.network.DisasterSyncPayload.CODEC.encode(buf, sent);
        var received = com.realisticdisasters.network.DisasterSyncPayload.CODEC.decode(buf);
        int leftover = buf.readableBytes();
        DisasterManager.stop(helper.getLevel(), shower.id(), false);
        helper.assertTrue(leftover == 0, "sync payload left " + leftover + " unread bytes");
        helper.assertTrue(java.util.Arrays.equals(sent.data(), received.data()), "sync data changed in transit");
        helper.assertTrue(sent.data()[MeteorShowerDisaster.D_SLOTS] > 0, "no meteor in the ring buffer");
        helper.succeed();
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

    private static java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> tsunamiSnapshot(GameTestHelper helper, int y0, int y1) {
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
        var before = tsunamiSnapshot(helper, 1, 11);
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
        var before = tsunamiSnapshot(helper, 1, 11);
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

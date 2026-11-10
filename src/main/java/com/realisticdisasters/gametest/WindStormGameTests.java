package com.realisticdisasters.gametest;

import java.util.HashMap;
import java.util.Map;

import com.realisticdisasters.RealisticDisasters;
import com.realisticdisasters.config.DisasterConfig;
import com.realisticdisasters.disaster.Disaster;
import com.realisticdisasters.disaster.DisasterManager;
import com.realisticdisasters.disaster.DisasterType;
import com.realisticdisasters.disaster.Phase;
import com.realisticdisasters.disaster.windstorm.WindStormDisaster;
import com.realisticdisasters.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** GameTests for the wind storms (blizzard, sandstorm). Registered from {@link DisasterGameTests}. */
final class WindStormGameTests {
    private static WindStormDisaster startPeak(GameTestHelper helper, DisasterType type, int intensity) {
        DisasterConfig.SPAWN_PROTECTION_RADIUS.set(0);
        DisasterConfig.BLIZZARD_CHANGES_WEATHER.set(false); // weather is global; keep the test world dry
        BlockPos center = helper.absolutePos(new BlockPos(16, 4, 16));
        Disaster d = DisasterManager.start(helper.getLevel(), type, Vec3.atBottomCenterOf(center), intensity);
        d.skipTo(Phase.PEAK);
        WindStormDisaster storm = (WindStormDisaster) d;
        storm.setWindHeading(0); // blow toward +X
        return storm;
    }

    private static void finish(GameTestHelper helper, Disaster d) {
        DisasterManager.stop(helper.getLevel(), d.id(), false);
        DisasterConfig.BLIZZARD_CHANGES_WEATHER.set(true);
    }

    private static boolean isDeposit(BlockState s, DisasterType type) {
        if (type == DisasterType.BLIZZARD) return s.is(Blocks.SNOW) || s.is(Blocks.ICE);
        return s.is(ModRegistries.SAND_LAYER.get()) || s.is(ModRegistries.RED_SAND_LAYER.get())
                || s.is(Blocks.SAND) || s.is(Blocks.RED_SAND);
    }

    /** Snow / sand deposited above the arena floor. */
    private static int deposits(GameTestHelper helper, DisasterType type) {
        int n = 0;
        for (int x = 0; x < 32; x++)
            for (int y = 4; y < 9; y++)
                for (int z = 0; z < 32; z++)
                    if (isDeposit(helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(x, y, z))), type)) n++;
        return n;
    }

    /** The storm lays down layers and an armour stand standing in the open gets shoved downwind. */
    static void buriesAndPushes(GameTestHelper helper, DisasterType type) {
        WindStormDisaster storm = startPeak(helper, type, 5);
        ArmorStand stand = helper.spawn(EntityType.ARMOR_STAND, new BlockPos(10, 4, 16));
        double startX = stand.getX();
        double[] maxPush = {0};
        helper.onEachTick(() -> maxPush[0] = Math.max(maxPush[0], stand.getX() - startX));
        helper.succeedWhen(() -> {
            helper.assertTrue(DisasterManager.active(helper.getLevel()).contains(storm), type.id() + " should be active");
            helper.assertTrue(maxPush[0] > 1.5, "armour stand was not pushed downwind yet (" + maxPush[0] + ")");
            int n = deposits(helper, type);
            helper.assertTrue(n >= 25, "not enough accumulation yet (" + n + ")");
            finish(helper, storm);
        });
    }

    /**
     * With {@code blockDestruction=false} nothing changes. Test batches run concurrently in one world, so the global
     * switch is only flipped inside a single server tick: 200 storm ticks are simulated synchronously, then every
     * block-touching hook is called directly, and the switch goes back on before any other test can tick.
     */
    static void respectsBlockToggle(GameTestHelper helper, DisasterType type) {
        BlockPos fire = helper.absolutePos(new BlockPos(12, 4, 12));
        helper.getLevel().setBlock(fire, net.minecraft.world.level.block.Blocks.CAMPFIRE.defaultBlockState(), 3);
        WindStormDisaster storm = startPeak(helper, type, 5);
        helper.runAfterDelay(1, () -> {
            Map<BlockPos, BlockState> before = new HashMap<>();
            for (int x = 0; x < 32; x++)
                for (int y = 3; y < 9; y++)
                    for (int z = 0; z < 32; z++) {
                        BlockPos p = helper.absolutePos(new BlockPos(x, y, z));
                        before.put(p, helper.getLevel().getBlockState(p));
                    }
            int queued = DisasterManager.blockQueue(helper.getLevel()).size();
            DisasterConfig.BLOCK_DESTRUCTION.set(false);
            try {
                for (int i = 0; i < 200; i++) storm.tick();
                BlockPos drift = helper.absolutePos(new BlockPos(19, 4, 21));
                for (int i = 0; i < 20; i++) {
                    storm.weatherColumn(fire.getX(), fire.getZ());
                    storm.accumulateColumn(drift.getX(), drift.getZ());
                }
            } finally {
                DisasterConfig.BLOCK_DESTRUCTION.set(true);
            }
            int queuedAfter = DisasterManager.blockQueue(helper.getLevel()).size();
            int count = 0;
            StringBuilder changed = new StringBuilder();
            for (var e : before.entrySet()) {
                BlockState now = helper.getLevel().getBlockState(e.getKey());
                if (now != e.getValue() && count++ < 5) {
                    changed.append(helper.relativePos(e.getKey()).toShortString()).append(' ')
                            .append(e.getValue().getBlock().getDescriptionId()).append("->").append(now.getBlock().getDescriptionId()).append("; ");
                }
            }
            finish(helper, storm);
            helper.assertTrue(count == 0, "blocks changed although destruction is disabled: " + count + " " + changed);
            helper.assertTrue(queuedAfter <= queued, "storm queued block changes although destruction is disabled");
            helper.succeed();
        });
    }

    static void tickCost(GameTestHelper helper, DisasterType type) {
        WindStormDisaster storm = startPeak(helper, type, 3);
        for (int i = 0; i < 6; i++) helper.spawn(EntityType.COW, new BlockPos(6 + i * 4, 4, 10));
        helper.runAfterDelay(300, () -> {
            double cost = DisasterManager.averageTickCostMs(helper.getLevel());
            finish(helper, storm);
            RealisticDisasters.LOGGER.info("{} intensity 3 average server cost: {} ms/tick", type.id(), String.format("%.3f", cost));
            helper.assertTrue(cost < 2.0, type.id() + " too expensive: " + cost + " ms/tick");
            helper.succeed();
        });
    }

    /** Sand drifts against the windward wall of the shed until it settles into a full sand block. */
    static void sandstormBuildsPiles(GameTestHelper helper) {
        WindStormDisaster storm = startPeak(helper, DisasterType.SANDSTORM, 5);
        BlockPos column = helper.absolutePos(new BlockPos(19, 4, 21)); // directly upwind of the shed's west wall
        BlockPos open = helper.absolutePos(new BlockPos(4, 4, 4));     // open ground for comparison
        for (int i = 0; i < 30; i++) {
            storm.accumulateColumn(column.getX(), column.getZ());
            storm.accumulateColumn(open.getX(), open.getZ());
        }
        BlockState pile = helper.getLevel().getBlockState(column);
        BlockState flat = helper.getLevel().getBlockState(open);
        finish(helper, storm);
        helper.assertTrue(pile.is(Blocks.SAND), "expected a sand pile against the wall, got " + pile);
        helper.assertTrue(flat.is(ModRegistries.SAND_LAYER.get()), "expected a thin sand layer on open ground, got " + flat);
        helper.succeed();
    }

    /** Wind physics: a wall's lee shelters you, the open doesn't; loose items tumble away downwind. */
    static void leeShelters(GameTestHelper helper) {
        WindStormDisaster storm = startPeak(helper, DisasterType.BLIZZARD, 5);
        ArmorStand open = helper.spawn(EntityType.ARMOR_STAND, new BlockPos(25, 4, 8));
        ArmorStand lee = helper.spawn(EntityType.ARMOR_STAND, new BlockPos(25, 4, 21)); // 2 blocks behind the shed wall
        var item = helper.spawnItem(net.minecraft.world.item.Items.STICK, new BlockPos(25, 4, 4));
        double openX = open.getX(), leeX = lee.getX(), itemX = item.getX();
        double[] max = {0, 0, 0};
        helper.onEachTick(() -> {
            max[0] = Math.max(max[0], open.getX() - openX);
            max[1] = Math.max(max[1], lee.getX() - leeX);
            if (!item.isRemoved()) max[2] = Math.max(max[2], item.getX() - itemX);
        });
        helper.runAfterDelay(300, () -> {
            finish(helper, storm);
            String msg = String.format("open %.2f, lee %.2f, item %.2f", max[0], max[1], max[2]);
            RealisticDisasters.LOGGER.info("Wind push over 15 s at intensity 5: {}", msg);
            helper.assertTrue(max[0] > 1.0, "open-ground stand barely moved: " + msg);
            helper.assertTrue(max[1] < max[0] * 0.5, "the wall's lee did not shelter: " + msg);
            helper.assertTrue(max[2] > 2.0, "loose item was not blown away: " + msg);
            helper.succeed();
        });
    }

    /** Open flames are blown out and exposed crops die (frost) / are buried (sand). */
    static void weathersFiresAndCrops(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos camp = helper.absolutePos(new BlockPos(12, 4, 12));
        BlockPos candle = helper.absolutePos(new BlockPos(13, 4, 12));
        BlockPos crop = helper.absolutePos(new BlockPos(14, 4, 12));
        level.setBlock(camp, net.minecraft.world.level.block.Blocks.CAMPFIRE.defaultBlockState(), 3);
        level.setBlock(candle, net.minecraft.world.level.block.Blocks.CANDLE.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT, true), 3);
        level.setBlock(crop.below(), net.minecraft.world.level.block.Blocks.FARMLAND.defaultBlockState(), 3);
        level.setBlock(crop, net.minecraft.world.level.block.Blocks.WHEAT.defaultBlockState(), 3);
        WindStormDisaster storm = startPeak(helper, DisasterType.SANDSTORM, 4);
        for (int i = 0; i < 12; i++) {
            storm.weatherColumn(camp.getX(), camp.getZ());
            storm.weatherColumn(candle.getX(), candle.getZ());
            storm.weatherColumn(crop.getX(), crop.getZ());
        }
        var lit = net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT;
        boolean campLit = level.getBlockState(camp).getValue(lit);
        boolean candleLit = level.getBlockState(candle).getValue(lit);
        boolean wheat = level.getBlockState(crop).is(net.minecraft.world.level.block.Blocks.WHEAT);
        finish(helper, storm);
        helper.assertTrue(!campLit, "campfire still burning");
        helper.assertTrue(!candleLit, "candle still burning");
        helper.assertTrue(!wheat, "exposed wheat survived the storm");
        helper.succeed();
    }

    private WindStormGameTests() {}
}

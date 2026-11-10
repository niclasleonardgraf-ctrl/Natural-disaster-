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
        ArmorStand stand = helper.spawn(EntityType.ARMOR_STAND, new BlockPos(4, 4, 16));
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

    static void respectsBlockToggle(GameTestHelper helper, DisasterType type) {
        DisasterConfig.BLOCK_DESTRUCTION.set(false);
        Map<BlockPos, BlockState> before = new HashMap<>();
        for (int x = 0; x < 32; x++)
            for (int y = 3; y < 9; y++)
                for (int z = 0; z < 32; z++) {
                    BlockPos p = helper.absolutePos(new BlockPos(x, y, z));
                    before.put(p, helper.getLevel().getBlockState(p));
                }
        WindStormDisaster storm = startPeak(helper, type, 5);
        helper.runAfterDelay(200, () -> {
            for (int i = 0; i < 20; i++) storm.accumulateColumn(helper.absolutePos(new BlockPos(19, 4, 21)).getX(),
                    helper.absolutePos(new BlockPos(19, 4, 21)).getZ());
            int count = 0;
            StringBuilder changed = new StringBuilder();
            for (var e : before.entrySet()) {
                BlockState now = helper.getLevel().getBlockState(e.getKey());
                if (now != e.getValue() && count++ < 5) {
                    changed.append(helper.relativePos(e.getKey()).toShortString()).append(' ')
                            .append(e.getValue().getBlock().getDescriptionId()).append("->").append(now.getBlock().getDescriptionId()).append("; ");
                }
            }
            DisasterConfig.BLOCK_DESTRUCTION.set(true);
            finish(helper, storm);
            helper.assertTrue(count == 0, "blocks changed although destruction is disabled: " + count + " " + changed);
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

    private WindStormGameTests() {}
}

package com.realisticdisasters.registry;

import com.realisticdisasters.RealisticDisasters;
import com.realisticdisasters.disaster.volcano.AshLayerBlock;
import com.realisticdisasters.disaster.volcano.LavaBombEntity;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageType;
import com.realisticdisasters.disaster.wildfire.CharredLogBlock;
import com.realisticdisasters.disaster.wildfire.ScorchedEarthBlock;
import com.realisticdisasters.disaster.wildfire.WildfireFlameBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModRegistries {
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, RealisticDisasters.MODID);
    public static final DeferredRegister<ParticleType<?>> PARTICLES = DeferredRegister.create(Registries.PARTICLE_TYPE, RealisticDisasters.MODID);
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(RealisticDisasters.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(RealisticDisasters.MODID);

    // Sounds (all original, synthesized by tools/gen_sounds.py)
    public static final DeferredHolder<SoundEvent, SoundEvent> TORNADO_ROAR = sound("disaster.tornado.roar");
    public static final DeferredHolder<SoundEvent, SoundEvent> TORNADO_DISTANT = sound("disaster.tornado.distant");
    public static final DeferredHolder<SoundEvent, SoundEvent> DISTANT_RUMBLE = sound("disaster.rumble");
    public static final DeferredHolder<SoundEvent, SoundEvent> WIND_GUST = sound("disaster.wind_gust");
    public static final DeferredHolder<SoundEvent, SoundEvent> TORNADO_CORE = sound("disaster.tornado.core");
    public static final DeferredHolder<SoundEvent, SoundEvent> WIND_HOWL = sound("disaster.wind_howl");
    public static final DeferredHolder<SoundEvent, SoundEvent> DEBRIS_THUD = sound("disaster.debris_thud");
    public static final DeferredHolder<SoundEvent, SoundEvent> WOOD_SNAP = sound("disaster.wood_snap");
    public static final DeferredHolder<SoundEvent, SoundEvent> DEBRIS_RATTLE = sound("disaster.debris_rattle");
    public static final DeferredHolder<SoundEvent, SoundEvent> QUAKE_RUMBLE = sound("disaster.earthquake.rumble");
    public static final DeferredHolder<SoundEvent, SoundEvent> QUAKE_DISTANT = sound("disaster.earthquake.distant");
    public static final DeferredHolder<SoundEvent, SoundEvent> QUAKE_CRACK = sound("disaster.earthquake.crack");
    public static final DeferredHolder<SoundEvent, SoundEvent> QUAKE_BOOM = sound("disaster.earthquake.boom");
    public static final DeferredHolder<SoundEvent, SoundEvent> COLLAPSE_CRASH = sound("disaster.earthquake.collapse");
    public static final DeferredHolder<SoundEvent, SoundEvent> WILDFIRE_CRACKLE = sound("disaster.wildfire.crackle");
    public static final DeferredHolder<SoundEvent, SoundEvent> WILDFIRE_ROAR = sound("disaster.wildfire.roar");
    public static final DeferredHolder<SoundEvent, SoundEvent> WILDFIRE_DISTANT = sound("disaster.wildfire.distant");
    public static final DeferredHolder<SoundEvent, SoundEvent> WILDFIRE_POP = sound("disaster.wildfire.pop");
    public static final DeferredHolder<SoundEvent, SoundEvent> WILDFIRE_WHOOSH = sound("disaster.wildfire.whoosh");
    public static final DeferredHolder<SoundEvent, SoundEvent> WILDFIRE_WHIRL = sound("disaster.wildfire.whirl");
    public static final DeferredHolder<SoundEvent, SoundEvent> VOLCANO_RUMBLE = sound("disaster.volcano.rumble");
    public static final DeferredHolder<SoundEvent, SoundEvent> VOLCANO_DISTANT = sound("disaster.volcano.distant");
    public static final DeferredHolder<SoundEvent, SoundEvent> VOLCANO_BLAST = sound("disaster.volcano.blast");
    public static final DeferredHolder<SoundEvent, SoundEvent> LAVA_BOMB_IMPACT = sound("disaster.volcano.bomb_impact");
    public static final DeferredHolder<SoundEvent, SoundEvent> METEOR_WHOOSH = sound("disaster.meteor.whoosh");
    public static final DeferredHolder<SoundEvent, SoundEvent> METEOR_WHOOSH_DISTANT = sound("disaster.meteor.whoosh_distant");
    public static final DeferredHolder<SoundEvent, SoundEvent> METEOR_IMPACT = sound("disaster.meteor.impact");
    public static final DeferredHolder<SoundEvent, SoundEvent> METEOR_IMPACT_DISTANT = sound("disaster.meteor.impact_distant");

    // Particles
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> FUNNEL_CLOUD =
            PARTICLES.register("funnel_cloud", () -> new SimpleParticleType(true));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> STORM_DEBRIS =
            PARTICLES.register("storm_debris", () -> new SimpleParticleType(true));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> WALL_CLOUD =
            PARTICLES.register("wall_cloud", () -> new SimpleParticleType(true));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> DUST_INFLOW =
            PARTICLES.register("dust_inflow", () -> new SimpleParticleType(true));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> FUNNEL_SURFACE =
            PARTICLES.register("funnel_surface", () -> new SimpleParticleType(true));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> CLOUD_SHEET =
            PARTICLES.register("cloud_sheet", () -> new SimpleParticleType(true));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> CLOUD_BAND =
            PARTICLES.register("cloud_band", () -> new SimpleParticleType(true));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> RAIN_SHAFT =
            PARTICLES.register("rain_shaft", () -> new SimpleParticleType(true));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> DUST_CLOUD =
            PARTICLES.register("dust_cloud", () -> new SimpleParticleType(true));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> QUAKE_DUST =
            PARTICLES.register("quake_dust", () -> new SimpleParticleType(true));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> COLLAPSE_DUST =
            PARTICLES.register("collapse_dust", () -> new SimpleParticleType(true));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> WILDFIRE_SMOKE =
            PARTICLES.register("wildfire_smoke", () -> new SimpleParticleType(true));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> ASH_FLAKE =
            PARTICLES.register("ash_flake", () -> new SimpleParticleType(false));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> EMBER =
            PARTICLES.register("ember", () -> new SimpleParticleType(false));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> SMOKE_BILLOW =
            PARTICLES.register("smoke_billow", () -> new SimpleParticleType(true));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> SMOKE_PALL =
            PARTICLES.register("smoke_pall", () -> new SimpleParticleType(true));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> SMOLDER_SMOKE =
            PARTICLES.register("smolder_smoke", () -> new SimpleParticleType(false));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> FIRE_TONGUE =
            PARTICLES.register("fire_tongue", () -> new SimpleParticleType(true));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> FIRE_WHIRL =
            PARTICLES.register("fire_whirl", () -> new SimpleParticleType(true));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> WHIRL_SMOKE =
            PARTICLES.register("whirl_smoke", () -> new SimpleParticleType(true));

    // Blocks
    /** Short-lived flame the wildfire places; burns out by itself and never spreads (the disaster drives spread). */
    public static final DeferredBlock<WildfireFlameBlock> WILDFIRE_FLAME = BLOCKS.registerBlock("wildfire_flame",
            WildfireFlameBlock::new, p -> p.mapColor(MapColor.FIRE).replaceable().noCollision().instabreak()
                    .lightLevel(s -> 15).sound(SoundType.WOOL).pushReaction(PushReaction.DESTROY).noLootTable());
    /** What a burnt tree trunk turns into. */
    public static final DeferredBlock<CharredLogBlock> CHARRED_LOG = BLOCKS.registerBlock("charred_log",
            CharredLogBlock::new, p -> p.mapColor(MapColor.COLOR_BLACK).instrument(NoteBlockInstrument.BASS)
                    .strength(1.5f).sound(SoundType.WOOD));
    public static final DeferredItem<BlockItem> CHARRED_LOG_ITEM = ITEMS.registerSimpleBlockItem(CHARRED_LOG);
    /** Burnt ground: char and ash over dirt. */
    public static final DeferredBlock<ScorchedEarthBlock> SCORCHED_EARTH = BLOCKS.registerBlock("scorched_earth",
            ScorchedEarthBlock::new, p -> p.mapColor(MapColor.COLOR_BLACK).strength(0.5f).sound(SoundType.GRAVEL));
    public static final DeferredItem<BlockItem> SCORCHED_EARTH_ITEM = ITEMS.registerSimpleBlockItem(SCORCHED_EARTH);
    /** Heat-killed, rust-brown foliage left on trees the fire only scorched. */
    public static final DeferredBlock<Block> SCORCHED_LEAVES = BLOCKS.registerSimpleBlock("scorched_leaves",
            p -> p.mapColor(MapColor.COLOR_BROWN).strength(0.2f).sound(SoundType.GRASS).noOcclusion()
                    .isValidSpawn((st, l, pos, type) -> false).isSuffocating((st, l, pos) -> false)
                    .isViewBlocking((st, l, pos) -> false).isRedstoneConductor((st, l, pos) -> false)
                    .pushReaction(PushReaction.DESTROY));
    public static final DeferredItem<BlockItem> SCORCHED_LEAVES_ITEM = ITEMS.registerSimpleBlockItem(SCORCHED_LEAVES);
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> ASH_CLOUD =
            PARTICLES.register("ash_cloud", () -> new SimpleParticleType(true));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> ASH_FALL =
            PARTICLES.register("ash_fall", () -> new SimpleParticleType(false));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> ASH_TRAIL =
            PARTICLES.register("ash_trail", () -> new SimpleParticleType(false));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> ASH_CURTAIN =
            PARTICLES.register("ash_curtain", () -> new SimpleParticleType(true));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> LAVA_SPARK =
            PARTICLES.register("lava_spark", () -> new SimpleParticleType(false));

    // Blocks
    // (shares the BLOCKS register declared above)
    public static final DeferredBlock<AshLayerBlock> VOLCANIC_ASH = BLOCKS.registerBlock("volcanic_ash", AshLayerBlock::new,
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GRAY).replaceable().forceSolidOff()
                    .strength(0.2f).sound(SoundType.SAND).pushReaction(PushReaction.DESTROY)
                    .isViewBlocking((st, l, p) -> st.getValue(SnowLayerBlock.LAYERS) >= 8));

    // Entities
    public static final DeferredRegister.Entities ENTITY_TYPES = DeferredRegister.createEntities(RealisticDisasters.MODID);
    public static final DeferredHolder<EntityType<?>, EntityType<LavaBombEntity>> LAVA_BOMB =
            ENTITY_TYPES.registerEntityType("lava_bomb", LavaBombEntity::new, MobCategory.MISC,
                    b -> b.sized(0.6f, 0.6f).clientTrackingRange(10).updateInterval(2).fireImmune().noSave().noLootTable());
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> METEOR_STREAK =
            PARTICLES.register("meteor_streak", () -> new SimpleParticleType(true));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> METEOR_FIREBALL =
            PARTICLES.register("meteor_fireball", () -> new SimpleParticleType(true));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> METEOR_EMBER =
            PARTICLES.register("meteor_ember", () -> new SimpleParticleType(true));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> METEOR_SMOKE =
            PARTICLES.register("meteor_smoke", () -> new SimpleParticleType(true));

    // Data-driven damage type (data/realisticdisasters/damage_type/tornado.json)
    public static final ResourceKey<DamageType> TORNADO_DAMAGE =
            ResourceKey.create(Registries.DAMAGE_TYPE, id("tornado"));
    public static final ResourceKey<DamageType> EARTHQUAKE_DAMAGE =
            ResourceKey.create(Registries.DAMAGE_TYPE, id("earthquake"));
    public static final ResourceKey<DamageType> WILDFIRE_DAMAGE =
            ResourceKey.create(Registries.DAMAGE_TYPE, id("wildfire"));
    public static final ResourceKey<DamageType> VOLCANO_DAMAGE =
            ResourceKey.create(Registries.DAMAGE_TYPE, id("volcano"));
    public static final ResourceKey<DamageType> METEOR_DAMAGE =
            ResourceKey.create(Registries.DAMAGE_TYPE, id("meteor"));

    private static DeferredHolder<SoundEvent, SoundEvent> sound(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(id(name)));
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(RealisticDisasters.MODID, path);
    }

    public static void register(IEventBus modBus) {
        SOUNDS.register(modBus);
        PARTICLES.register(modBus);
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        ENTITY_TYPES.register(modBus);
    }

    private ModRegistries() {}
}

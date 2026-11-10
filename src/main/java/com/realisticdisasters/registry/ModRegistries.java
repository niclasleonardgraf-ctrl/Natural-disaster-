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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModRegistries {
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, RealisticDisasters.MODID);
    public static final DeferredRegister<ParticleType<?>> PARTICLES = DeferredRegister.create(Registries.PARTICLE_TYPE, RealisticDisasters.MODID);

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
    public static final DeferredHolder<SoundEvent, SoundEvent> VOLCANO_RUMBLE = sound("disaster.volcano.rumble");
    public static final DeferredHolder<SoundEvent, SoundEvent> VOLCANO_DISTANT = sound("disaster.volcano.distant");
    public static final DeferredHolder<SoundEvent, SoundEvent> VOLCANO_BLAST = sound("disaster.volcano.blast");
    public static final DeferredHolder<SoundEvent, SoundEvent> LAVA_BOMB_IMPACT = sound("disaster.volcano.bomb_impact");

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
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(RealisticDisasters.MODID);
    public static final DeferredBlock<AshLayerBlock> VOLCANIC_ASH = BLOCKS.registerBlock("volcanic_ash", AshLayerBlock::new,
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GRAY).replaceable().forceSolidOff()
                    .strength(0.2f).sound(SoundType.SAND).pushReaction(PushReaction.DESTROY)
                    .isViewBlocking((st, l, p) -> st.getValue(SnowLayerBlock.LAYERS) >= 8));

    // Entities
    public static final DeferredRegister.Entities ENTITY_TYPES = DeferredRegister.createEntities(RealisticDisasters.MODID);
    public static final DeferredHolder<EntityType<?>, EntityType<LavaBombEntity>> LAVA_BOMB =
            ENTITY_TYPES.registerEntityType("lava_bomb", LavaBombEntity::new, MobCategory.MISC,
                    b -> b.sized(0.6f, 0.6f).clientTrackingRange(10).updateInterval(2).fireImmune().noSave().noLootTable());

    // Data-driven damage type (data/realisticdisasters/damage_type/tornado.json)
    public static final ResourceKey<DamageType> TORNADO_DAMAGE =
            ResourceKey.create(Registries.DAMAGE_TYPE, id("tornado"));
    public static final ResourceKey<DamageType> VOLCANO_DAMAGE =
            ResourceKey.create(Registries.DAMAGE_TYPE, id("volcano"));

    private static DeferredHolder<SoundEvent, SoundEvent> sound(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(id(name)));
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(RealisticDisasters.MODID, path);
    }

    public static void register(IEventBus modBus) {
        SOUNDS.register(modBus);
        PARTICLES.register(modBus);
        ENTITY_TYPES.register(modBus);
        BLOCKS.register(modBus);
    }

    private ModRegistries() {}
}

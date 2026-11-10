package com.realisticdisasters.registry;

import com.realisticdisasters.RealisticDisasters;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageType;
import net.neoforged.bus.api.IEventBus;
import com.realisticdisasters.block.SandLayerBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
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
    public static final DeferredHolder<SoundEvent, SoundEvent> BLIZZARD_HOWL = sound("disaster.blizzard.howl");
    public static final DeferredHolder<SoundEvent, SoundEvent> BLIZZARD_DISTANT = sound("disaster.blizzard.distant");
    public static final DeferredHolder<SoundEvent, SoundEvent> SANDSTORM_HOWL = sound("disaster.sandstorm.howl");
    public static final DeferredHolder<SoundEvent, SoundEvent> SANDSTORM_DISTANT = sound("disaster.sandstorm.distant");

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
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> BLIZZARD_SNOW =
            PARTICLES.register("blizzard_snow", () -> new SimpleParticleType(true));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> SNOW_HAZE =
            PARTICLES.register("snow_haze", () -> new SimpleParticleType(true));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> SAND_GRAIN =
            PARTICLES.register("sand_grain", () -> new SimpleParticleType(true));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> SAND_HAZE =
            PARTICLES.register("sand_haze", () -> new SimpleParticleType(true));

    // Blocks left behind by sandstorms
    public static final DeferredBlock<SandLayerBlock> SAND_LAYER = BLOCKS.registerBlock("sand_layer", SandLayerBlock::new,
            p -> sandLayerProps(p, MapColor.SAND));
    public static final DeferredBlock<SandLayerBlock> RED_SAND_LAYER = BLOCKS.registerBlock("red_sand_layer", SandLayerBlock::new,
            p -> sandLayerProps(p, MapColor.COLOR_ORANGE));
    public static final DeferredItem<BlockItem> SAND_LAYER_ITEM = ITEMS.registerSimpleBlockItem(SAND_LAYER);
    public static final DeferredItem<BlockItem> RED_SAND_LAYER_ITEM = ITEMS.registerSimpleBlockItem(RED_SAND_LAYER);

    // Data-driven damage type (data/realisticdisasters/damage_type/tornado.json)
    public static final ResourceKey<DamageType> TORNADO_DAMAGE =
            ResourceKey.create(Registries.DAMAGE_TYPE, id("tornado"));
    public static final ResourceKey<DamageType> SANDSTORM_DAMAGE =
            ResourceKey.create(Registries.DAMAGE_TYPE, id("sandstorm"));

    private static BlockBehaviour.Properties sandLayerProps(BlockBehaviour.Properties p, MapColor color) {
        return p.mapColor(color).replaceable().forceSolidOff().strength(0.2F).sound(SoundType.SAND)
                .isViewBlocking((state, level, pos) -> state.getValue(SandLayerBlock.LAYERS) >= 8)
                .pushReaction(PushReaction.DESTROY);
    }

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
        modBus.addListener(ModRegistries::addToCreativeTabs);
    }

    private static void addToCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.NATURAL_BLOCKS) {
            event.accept(SAND_LAYER_ITEM.get());
            event.accept(RED_SAND_LAYER_ITEM.get());
        }
    }

    private ModRegistries() {}
}

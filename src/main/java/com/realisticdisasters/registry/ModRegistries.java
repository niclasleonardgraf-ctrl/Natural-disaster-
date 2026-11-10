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
    public static final DeferredHolder<SoundEvent, SoundEvent> QUAKE_RUMBLE = sound("disaster.earthquake.rumble");
    public static final DeferredHolder<SoundEvent, SoundEvent> QUAKE_DISTANT = sound("disaster.earthquake.distant");
    public static final DeferredHolder<SoundEvent, SoundEvent> QUAKE_CRACK = sound("disaster.earthquake.crack");
    public static final DeferredHolder<SoundEvent, SoundEvent> QUAKE_BOOM = sound("disaster.earthquake.boom");
    public static final DeferredHolder<SoundEvent, SoundEvent> COLLAPSE_CRASH = sound("disaster.earthquake.collapse");

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

    // Data-driven damage type (data/realisticdisasters/damage_type/tornado.json)
    public static final ResourceKey<DamageType> TORNADO_DAMAGE =
            ResourceKey.create(Registries.DAMAGE_TYPE, id("tornado"));
    public static final ResourceKey<DamageType> EARTHQUAKE_DAMAGE =
            ResourceKey.create(Registries.DAMAGE_TYPE, id("earthquake"));

    private static DeferredHolder<SoundEvent, SoundEvent> sound(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(id(name)));
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(RealisticDisasters.MODID, path);
    }

    public static void register(IEventBus modBus) {
        SOUNDS.register(modBus);
        PARTICLES.register(modBus);
    }

    private ModRegistries() {}
}

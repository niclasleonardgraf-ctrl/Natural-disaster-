package com.realisticdisasters.control;

import com.realisticdisasters.RealisticDisasters;
import com.realisticdisasters.config.DisasterConfig;
import com.realisticdisasters.registry.ModRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** First join: hand the player a Disaster Controller and explain it in two lines. No commands needed. */
@EventBusSubscriber(modid = RealisticDisasters.MODID)
public final class Welcome {
    private static final String TAG = RealisticDisasters.MODID + ":received_controller";

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !DisasterConfig.GIVE_CONTROLLER.getAsBoolean()) return;
        var data = player.getPersistentData();
        if (data.getBooleanOr(TAG, false)) return;
        data.putBoolean(TAG, true);
        ItemStack stack = new ItemStack(ModRegistries.DISASTER_CONTROLLER.get());
        if (!player.getInventory().add(stack)) player.drop(stack, false);
        player.sendSystemMessage(Component.translatable("welcome.realisticdisasters.title").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        player.sendSystemMessage(Component.translatable("welcome.realisticdisasters.body").withStyle(ChatFormatting.GRAY));
    }

    private Welcome() {}
}

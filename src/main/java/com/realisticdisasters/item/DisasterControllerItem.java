package com.realisticdisasters.item;

import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

/**
 * The Disaster Controller: right-click to open the control menu (client-side screen). Starting and stopping happens
 * through validated network requests, so this class has no client code and is safe on dedicated servers.
 */
public class DisasterControllerItem extends Item {
    /** Set by the client entry point; a no-op on dedicated servers. */
    public static Runnable openScreen = () -> {};

    public DisasterControllerItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) openScreen.run();
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
        builder.accept(Component.translatable("item.realisticdisasters.disaster_controller.desc").withStyle(ChatFormatting.GRAY));
        builder.accept(Component.translatable("item.realisticdisasters.disaster_controller.use").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
    }
}

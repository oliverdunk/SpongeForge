/*
 * This file is part of Sponge, licensed under the MIT License (MIT).
 *
 * Copyright (c) SpongePowered <https://www.spongepowered.org>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.spongepowered.mod.mixin.core.network;

import com.google.common.collect.Sets;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C01PacketChatMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import org.apache.logging.log4j.Logger;
import org.spongepowered.api.event.command.MessageSinkEvent;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.Texts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;
import org.spongepowered.mod.event.SpongeForgeEventFactory;
import org.spongepowered.mod.interfaces.IMixinEventPlayerChat;
import org.spongepowered.mod.interfaces.IMixinNetPlayHandler;

import java.util.Set;

@Mixin(value = NetHandlerPlayServer.class, priority = 1001)
public abstract class MixinNetHandlerPlayServer implements IMixinNetPlayHandler {

    @Shadow public EntityPlayerMP playerEntity;
    @Shadow private int chatSpamThresholdCount;
    @Shadow private static Logger logger;
    @Shadow public NetworkManager netManager;
    @Shadow private MinecraftServer serverController;
    @Shadow private boolean hasMoved;

    private final Set<String> registeredChannels = Sets.newHashSet();

    @Shadow public abstract void sendPacket(final Packet packetIn);
    @Shadow public abstract void kickPlayerFromServer(String message);

    @Inject(method = "processChatMessage", at = @At(value = "INVOKE", target = "net.minecraftforge.common.ForgeHooks.onServerChatEvent"
            + "(Lnet/minecraft/network/NetHandlerPlayServer;Ljava/lang/String;Lnet/minecraft/util/ChatComponentTranslation;)"
            + "Lnet/minecraft/util/ChatComponentTranslation;", remap = false),
            cancellable = true, locals = LocalCapture.CAPTURE_FAILHARD)
    public void injectChatEvent(C01PacketChatMessage packetIn, CallbackInfo ci, String s, ChatComponentTranslation component) {
        final ServerChatEvent event = new ServerChatEvent(this.playerEntity, s, component);
        ((IMixinEventPlayerChat) event).setRawMessage(Texts.of(packetIn.getMessage()));

        if (!MinecraftForge.EVENT_BUS.post(event)) {
            MessageSinkEvent spongeEvent = (MessageSinkEvent) event;
            Text returned = Texts.format(spongeEvent.getMessage());
            spongeEvent.getSink().sendMessage(returned);

            // Chat spam suppression from MC
            this.chatSpamThresholdCount += 20;
            if (this.chatSpamThresholdCount > 200 && !MinecraftServer.getServer().getConfigurationManager()
                    .canSendCommands(this.playerEntity.getGameProfile())) {
                this.kickPlayerFromServer("disconnect.spam");
            }
        }

        ci.cancel();
    }

    @Override
    public Set<String> getRegisteredChannels() {
        return this.registeredChannels;
    }

    @Redirect(method = "processPlayerBlockPlacement", at = @At(value = "INVOKE", target="Lnet/minecraft/server/management/ItemInWorldManager;activateBlockOrUseItem(Lnet/minecraft/entity/player/EntityPlayer;Lnet/minecraft/world/World;Lnet/minecraft/item/ItemStack;Lnet/minecraft/util/BlockPos;Lnet/minecraft/util/EnumFacing;FFF)Z"))
    public boolean onActivateBlockOrUseItem(ItemInWorldManager itemManager, EntityPlayer player, net.minecraft.world.World worldIn, ItemStack stack, BlockPos pos, EnumFacing side, float hitX, float hitY, float hitZ) {
        boolean result = itemManager.activateBlockOrUseItem(player, worldIn, stack, pos, side, hitX, hitY, hitZ);
        if (stack != null && !result) {
            // Don't run item use part of hook if PlayerInteractEvent was cancelled.
            if (SpongeForgeEventFactory.lastForgeEvent instanceof PlayerInteractEvent && SpongeForgeEventFactory.lastForgeEvent.isCanceled()) {
                return false;
            }
            itemManager.tryUseItem(player, worldIn, stack);
        }
        return result;
    }
}

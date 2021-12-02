/*
 * Modern Transportation
 * Copyright (C) 2021 shartte & Technici4n
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package dev.technici4n.moderntransportation.util;

import dev.technici4n.moderntransportation.block.PipeBlock;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.tag.TagFactory;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tag.Tag;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.ItemScatterer;

/**
 * Helper to detect if items are wrenches, and to make wrench shift-clicking dismantle MT pipes.
 */
public class WrenchHelper {
    private static final Tag<Item> WRENCH_TAG = TagFactory.ITEM.create(new Identifier("c:wrenches"));

    public static boolean isWrench(ItemStack stack) {
        return WRENCH_TAG.contains(stack.getItem());
    }

    /**
     * Dismantle target pipe on shift right-click with a wrench.
     */
    public static void registerEvents() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (player.isSpectator() || !world.canPlayerModifyAt(player, hitResult.getBlockPos()) || !isWrench(player.getStackInHand(hand))) {
                return ActionResult.PASS;
            }

            var pos = hitResult.getBlockPos();
            if (world.getBlockState(pos).getBlock() instanceof PipeBlock block) {
                world.setBlockState(pos, Blocks.AIR.getDefaultState());
                ItemScatterer.spawn(world, pos.getX(), pos.getY(), pos.getZ(), new ItemStack(block.asItem()));
                // TODO: play a cool sound
                return ActionResult.success(world.isClient);
            }

            return ActionResult.PASS;
        });
    }
}

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
package dev.technici4n.moderntransportation.block;

import dev.technici4n.moderntransportation.MtBlockEntity;
import dev.technici4n.moderntransportation.model.PipeModelData;
import dev.technici4n.moderntransportation.network.NodeHost;
import dev.technici4n.moderntransportation.network.TickHelper;
import dev.technici4n.moderntransportation.util.ShapeHelper;
import dev.technici4n.moderntransportation.util.WrenchHelper;
import net.fabricmc.fabric.api.lookup.v1.block.BlockApiLookup;
import net.fabricmc.fabric.api.rendering.data.v1.RenderAttachmentBlockEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import org.jetbrains.annotations.Nullable;

/**
 * Abstract base BE class for all pipes.
 * Subclasses must have a static list of {@link NodeHost}s that will be used for all the registration and saving logic.
 */
public abstract class PipeBlockEntity extends MtBlockEntity implements RenderAttachmentBlockEntity {
    public PipeBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    private boolean hostsRegistered = false;
    public int connectionBlacklist = 0;
    private VoxelShape cachedShape = PipeBoundingBoxes.CORE_SHAPE;
    private PipeModelData modelData = null;
    private int clientSideConnections = 0;

    public abstract NodeHost[] getHosts();

    @Override
    public void sync() {
        super.sync();
        updateCachedShape(getPipeConnections(), getInventoryConnections());
    }

    @Override
    public void toClientTag(NbtCompound tag) {
        tag.putByte("connectionBlacklist", (byte) connectionBlacklist);
        tag.putByte("connections", (byte) getPipeConnections());
        tag.putByte("inventoryConnections", (byte) getInventoryConnections());
    }

    @Override
    public void fromClientTag(NbtCompound tag) {
        connectionBlacklist = tag.getByte("connectionBlacklist");
        byte connections = tag.getByte("connections");
        byte inventoryConnections = tag.getByte("inventoryConnections");

        updateCachedShape(connections, inventoryConnections);
        modelData = new PipeModelData(connections, inventoryConnections);
        clientSideConnections = connections | inventoryConnections;
        remesh();
    }

    @Override
    @Nullable
    public Object getRenderAttachmentData() {
        return modelData;
    }

    @Override
    public void toTag(NbtCompound nbt) {
        nbt.putByte("connectionBlacklist", (byte) connectionBlacklist);

        for (NodeHost host : getHosts()) {
            host.separateNetwork();
            host.writeNbt(nbt);
        }
    }

    @Override
    public void fromTag(NbtCompound nbt) {
        connectionBlacklist = nbt.getByte("connectionBlacklist");

        for (NodeHost host : getHosts()) {
            host.separateNetwork();
            host.readNbt(nbt);
        }
    }

    public void scheduleHostUpdates() {
        for (NodeHost host : getHosts()) {
            host.scheduleUpdate();
        }
    }

    @Override
    public void cancelRemoval() {
        super.cancelRemoval();

        if (!world.isClient()) {
            if (!hostsRegistered) {
                TickHelper.runLater(() -> {
                    if (!hostsRegistered && !isRemoved()) {
                        hostsRegistered = true;

                        for (NodeHost host : getHosts()) {
                            host.addSelf();
                        }
                    }
                });
            }
        }
    }

    @Override
    public void markRemoved() {
        super.markRemoved();

        if (!world.isClient()) {
            if (hostsRegistered) {
                hostsRegistered = false;

                for (NodeHost host : getHosts()) {
                    host.removeSelf();
                }
            }
        }
    }

    public void refreshHosts() {
        if (hostsRegistered) {
            for (NodeHost host : getHosts()) {
                host.refreshSelf();
            }
        }
    }

    @Nullable
    public Object getApiInstance(BlockApiLookup<?, Direction> direction, Direction side) {
        for (var host : getHosts()) {
            var api = host.getApiInstance(direction, side);
            if (api != null) {
                return api;
            }
        }
        return null;
    }

    protected int getPipeConnections() {
        int pipeConnections = 0;

        for (NodeHost host : getHosts()) {
            pipeConnections |= host.pipeConnections;
        }

        return pipeConnections;
    }

    protected int getInventoryConnections() {
        int inventoryConnections = 0;

        for (NodeHost host : getHosts()) {
            inventoryConnections |= host.inventoryConnections;
        }

        return inventoryConnections;
    }

    public VoxelShape getCachedShape() {
        return cachedShape;
    }

    public void updateCachedShape(int pipeConnections, int inventoryConnections) {
        int allConnections = pipeConnections | inventoryConnections;

        VoxelShape shape = PipeBoundingBoxes.CORE_SHAPE;

        for (int i = 0; i < 6; ++i) {
            if ((allConnections & (1 << i)) > 0) {
                shape = VoxelShapes.union(shape, PipeBoundingBoxes.PIPE_CONNECTIONS[i]);
            }

            if ((inventoryConnections & (1 << i)) > 0) {
                shape = VoxelShapes.union(shape, PipeBoundingBoxes.CONNECTOR_SHAPES[i]);
            }
        }

        cachedShape = shape.simplify();
    }

    /**
     * Update connection blacklist for a side, and schedule a node update, on the server side.
     */
    protected void updateConnection(Direction side, boolean addConnection) {
        if (world.isClient()) {
            throw new IllegalStateException("updateConnections() should not be called client-side.");
        }

        // Update mask
        if (addConnection) {
            connectionBlacklist &= ~(1 << side.getId());
        } else {
            connectionBlacklist |= 1 << side.getId();
        }

        // Update neighbor's mask as well
        BlockEntity be = world.getBlockEntity(pos.offset(side));

        if (be instanceof PipeBlockEntity) {
            PipeBlockEntity neighborPipe = (PipeBlockEntity) be;
            if (addConnection) {
                neighborPipe.connectionBlacklist &= ~(1 << side.getOpposite().getId());
            } else {
                neighborPipe.connectionBlacklist |= 1 << side.getOpposite().getId();
            }
            neighborPipe.markDirty();
        }

        // Schedule inventory and network updates.
        refreshHosts();
        // The call to getNode() causes a network rebuild, but that shouldn't be an issue. (?)
        scheduleHostUpdates();

        world.updateNeighbors(pos, getCachedState().getBlock());
        markDirty();
        // no need to sync(), that's already handled by the refresh or update if necessary
    }

    public ActionResult onUse(PlayerEntity player, Hand hand, BlockHitResult hitResult) {
        if (WrenchHelper.isWrench(player.getStackInHand(hand))) {
            Vec3d posInBlock = hitResult.getPos().subtract(pos.getX(), pos.getY(), pos.getZ());

            // If the core was hit, add back the pipe on the target side
            if (ShapeHelper.shapeContains(PipeBoundingBoxes.CORE_SHAPE, posInBlock)) {
                if ((connectionBlacklist & (1 << hitResult.getSide().getId())) > 0) {
                    if (!world.isClient()) {
                        updateConnection(hitResult.getSide(), true);
                    }

                    return ActionResult.success(world.isClient());
                }
            }

            for (int i = 0; i < 6; ++i) {
                // If a pipe or inventory connection was hit, add it to the blacklist
                // INVENTORY_CONNECTIONS contains both the pipe and the connector, so it will work in both cases
                if (ShapeHelper.shapeContains(PipeBoundingBoxes.INVENTORY_CONNECTIONS[i], posInBlock)) {
                    if (world.isClient()) {
                        if ((clientSideConnections & (1 << i)) > 0) {
                            return ActionResult.SUCCESS;
                        }
                    } else {
                        if ((getPipeConnections() & (1 << i)) > 0 || (getInventoryConnections() & (1 << i)) > 0) {
                            updateConnection(Direction.byId(i), false);
                            return ActionResult.CONSUME;
                        }
                    }
                }
            }
        }

        return ActionResult.PASS;
    }
}

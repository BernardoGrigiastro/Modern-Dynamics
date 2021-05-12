package dev.technici4n.moderntransportation.network;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * The manager of all networks for a given cache class.
 */
public class NetworkManager<H extends NodeHost, C extends NetworkCache<H, C>> {
    private static final Map<Class<?>, NetworkManager<?, ?>> MANAGERS = new IdentityHashMap<>();

    @SuppressWarnings("unchecked")
    public static synchronized <H extends NodeHost, C extends NetworkCache<H, C>> NetworkManager<H, C> get(Class<C> cacheClass) {
        NetworkManager<H, C> manager = (NetworkManager<H, C>) MANAGERS.get(cacheClass);

        if (manager == null) {
            throw new IllegalArgumentException("NetworkManager does not exist for cache class " + cacheClass.getCanonicalName());
        }

        return manager;
    }

    public static synchronized <H extends NodeHost, C extends NetworkCache<H, C>> void registerCacheClass(Class<C> cacheClass, NetworkCache.Factory<H, C> factory) {
        Objects.requireNonNull(cacheClass, "Cache class may not be null.");
        Objects.requireNonNull(factory, "Factory may not be null.");

        if (MANAGERS.put(cacheClass, new NetworkManager<>(cacheClass, factory)) != null) {
            throw new IllegalArgumentException("Duplicate registration of cache class " + cacheClass.getCanonicalName());
        }
    }

    public static synchronized void onServerStopped() {
        for (NetworkManager<?, ?> manager : MANAGERS.values()) {
            manager.nodes.clear();
            manager.pendingUpdates.clear();
            manager.networks.clear();
        }
    }

    public static synchronized void onEndTick() {
        for (NetworkManager<?, ?> manager : MANAGERS.values()) {
            manager.updateNetworks();

            for (Network<?, ?> network : manager.networks) {
                network.cache.tick();
            }
        }
    }

    // TODO: remove this?
    private final Class<C> cacheClass;
    private final NetworkCache.Factory<H, C> cacheFactory;
    private final IdentityHashMap<ServerWorld, Long2ObjectOpenHashMap<NetworkNode<H, C>>> nodes = new IdentityHashMap<>();
    private final Set<NetworkNode<H, C>> pendingUpdates = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Network<H, C>> networks = Collections.newSetFromMap(new IdentityHashMap<>());

    NetworkManager(Class<C> cacheClass, NetworkCache.Factory<H, C> cacheFactory) {
        this.cacheClass = cacheClass;
        this.cacheFactory = cacheFactory;
    }

    public void addNode(ServerWorld world, BlockPos pos, H host) {
        Long2ObjectOpenHashMap<NetworkNode<H, C>> worldNodes = nodes.computeIfAbsent(world, w -> new Long2ObjectOpenHashMap<>());

        NetworkNode<H, C> newNode = new NetworkNode<>(host);
        EnumSet<Direction> allowedConnections = host.getAllowedNodeConnections();

        if (worldNodes.put(pos.asLong(), newNode) != null) {
            throw new IllegalArgumentException("Node at position " + pos + " in world " + world + " already exists.");
        }

        pendingUpdates.add(newNode);

        for (Direction direction : allowedConnections) {
            BlockPos adjacentPos = pos.offset(direction);
            @Nullable
            NetworkNode<H, C> adjacentNode = worldNodes.get(adjacentPos.asLong());

            if (adjacentNode != null && adjacentNode.getHost().getAllowedNodeConnections().contains(direction.getOpposite())) {
                if (adjacentNode.network != null) {
                    // The network of the adjacent node may be null during loading.
                    adjacentNode.network.cache.separate();
                }

                newNode.addConnection(direction, adjacentNode);
                adjacentNode.addConnection(direction.getOpposite(), newNode);
            }
        }
    }

    public void removeNode(ServerWorld world, BlockPos pos, H host) {
        Long2ObjectOpenHashMap<NetworkNode<H, C>> worldNodes = nodes.computeIfAbsent(world, w -> new Long2ObjectOpenHashMap<>());

        NetworkNode<H, C> node = worldNodes.remove(pos.asLong());

        if (node == null) {
            throw new IllegalArgumentException("Node at position " + pos + " in world " + world + " can't be removed: it doesn't exist.");
        }

        if (node.getHost() != host) {
            throw new IllegalArgumentException("Node at position " + pos + " in world " + world + " can't be removed: the hosts don't match.");
        }

        if (node.network != null) {
            // The network might be null, for example if the node gets instantly removed.
            node.network.cache.separate();
        }

        for (NetworkNode.Connection<H, C> connection : node.getConnections()) {
            NetworkNode<H, C> target = connection.target;
            target.removeConnection(connection.direction.getOpposite(), node);
            pendingUpdates.add(target);
        }
    }

    @Nullable
    public NetworkNode<H, C> findNode(ServerWorld world, BlockPos pos) {
        updateNetworks();

        return nodes.computeIfAbsent(world, w -> new Long2ObjectOpenHashMap<>()).get(pos.asLong());
    }

    private void updateNetworks() {
        if (pendingUpdates.size() == 0) return;

        List<NetworkNode<H, C>> pendingUpdatesCopy = new ArrayList<>(pendingUpdates);
        pendingUpdates.clear();

        // pendingUpdates is now used as a visited flag while we rebuild the networks
        for (NetworkNode<H, C> node : pendingUpdatesCopy) {
            if (!pendingUpdates.contains(node)) {
                List<NetworkNode<H, C>> nodes = new ArrayList<>();
                Network<H, C> network = new Network<>(nodes);
                assignNetworkDfs(node, network);
                network.cache = cacheFactory.build(network.nodes);
                networks.add(network);
            }
        }

        // clear the visited flag
        pendingUpdates.clear();
    }

    private void assignNetworkDfs(NetworkNode<H, C> u, Network<H, C> network) {
        if (pendingUpdates.add(u)) {
            // Remove previous network
            if (u.network != null) networks.remove(u.network);

            // Link node to new network
            u.network = network;
            network.nodes.add(u);

            // Visit neighbors
            for (NetworkNode.Connection<H, C> connection : u.getConnections()) {
                assignNetworkDfs(connection.target, network);
            }
        }
    }
}
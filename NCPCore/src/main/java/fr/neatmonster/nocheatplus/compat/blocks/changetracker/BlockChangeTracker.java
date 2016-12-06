/*
 * This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package fr.neatmonster.nocheatplus.compat.blocks.changetracker;

import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import fr.neatmonster.nocheatplus.NCPAPIProvider;
import fr.neatmonster.nocheatplus.components.location.IGetPosition;
import fr.neatmonster.nocheatplus.components.registry.event.IGenericInstanceHandle;
import fr.neatmonster.nocheatplus.logging.Streams;
import fr.neatmonster.nocheatplus.utilities.TickTask;
import fr.neatmonster.nocheatplus.utilities.ds.map.CoordHashMap;
import fr.neatmonster.nocheatplus.utilities.ds.map.CoordMap;
import fr.neatmonster.nocheatplus.utilities.ds.map.LinkedCoordHashMap;
import fr.neatmonster.nocheatplus.utilities.ds.map.LinkedCoordHashMap.MoveOrder;
import fr.neatmonster.nocheatplus.utilities.map.BlockCache;
import fr.neatmonster.nocheatplus.utilities.map.BlockCache.IBlockCacheNode;
import fr.neatmonster.nocheatplus.utilities.map.BlockProperties;

/**
 * Keep track of block changes, to allow mitigation of false positives. Think of
 * pistons, falling blocks, digging, block placing, explosions, vegetables
 * growing, all sorts of doors, plugins changing blocks and so on. This is
 * needed not only for elevator and parkour designs, but also to prevent piston
 * based trap designs, which could lead to victims continuously violating moving
 * checks.
 * <hr>
 * In general we assume that at the time of adding a block change entry, the
 * block has not yet been changed, so we access the "old state" at that point of
 * time. If needed, a method allowing to specify the old state explicitly will
 * be added.
 * 
 * @author asofold
 *
 */
public class BlockChangeTracker {

    public static enum Direction {
        NONE,
        X_POS,
        X_NEG,
        Y_POS,
        Y_NEG,
        Z_POS,
        Z_NEG;

        public static Direction getDirection(final BlockFace blockFace) {
            final int x = blockFace.getModX();
            if (x == 1) {
                return X_POS;
            }
            else if (x == -1) {
                return X_NEG;
            }
            final int y = blockFace.getModY();
            if (y == 1) {
                return Y_POS;
            }
            else if (y == -1) {
                return Y_NEG;
            }
            final int z = blockFace.getModZ();
            if (z == 1) {
                return Z_POS;
            }
            else if (z == -1) {
                return Z_NEG;
            }
            return NONE;
        }

    }

    /**
     * Count active block changes per chunk/thing.
     * 
     * @author asofold
     *
     */
    public static class ActivityNode {
        public int count = 0;
    }

    public static class WorldNode {
        // TODO: private + access methods.
        /*
         * TODO: A coarse rectangle or cuboid based approach, Allowing fast
         * exclusion check for moves (needs access methods for everything then).
         * Could merge with the per-block map, similar to WorldGuard.
         */
        /*
         * TODO: Consider a filter mechanism for player activity by chunks or
         * chunk sections (some margin, only add if activity, let expire by
         * tick). Only add blocks if players could reach the location.
         */

        /**
         * Count active block change entries per chunk (chunk size and access
         * are handled elsewhere, except for clear).
         */
        public final CoordMap<ActivityNode> activityMap = new CoordHashMap<ActivityNode>();
        /** Directly map to individual blocks. */
        public final LinkedCoordHashMap<LinkedList<BlockChangeEntry>> blocks = new LinkedCoordHashMap<LinkedList<BlockChangeEntry>>();
        /** Tick of last change. */
        public int lastChangeTick = 0;

        /** Total number of BlockChangeEntry instances. */
        public int size = 0;

        public final UUID worldId;

        public WorldNode(UUID worldId) {
            this.worldId = worldId;
        }

        public void clear() {
            activityMap.clear();
            blocks.clear();
            size = 0;
        }

        /**
         * Get an activity node for the given block coordinates at the given
         * resolution. If no node is there, a new one will be created.
         * 
         * @param x
         * @param y
         * @param z
         * @param activityResolution
         * @return
         */
        public ActivityNode getActivityNode(int x, int y, int z, final int activityResolution) {
            x /= activityResolution;
            y /= activityResolution;
            z /= activityResolution;
            ActivityNode node = activityMap.get(x, y, z);
            if (node == null) {
                node = new ActivityNode();
                activityMap.put(x, y, z, node);
            }
            return node;
        }

        public void removeActivityNode(final int x, final int y, final int z, final int activityResolution) {
            activityMap.remove(x / activityResolution, y / activityResolution, z / activityResolution);
        }

    }

    /**
     * Record a state of a block.
     * 
     * @author asofold
     *
     */
    public static class BlockChangeEntry {

        // TODO: Might implement IBlockPosition.

        public final long id;
        public final int tick, x, y, z;
        public final Direction direction;
        public final IBlockCacheNode previousState;
        /**
         * The tick value of the next entry, allowing to determine an interval
         * of validity for this state.
         */
        public int nextEntryTick = -1;

        /**
         * A block change entry.
         * 
         * @param id
         * @param tick
         * @param x
         * @param y
         * @param z
         * @param direction
         *            Moving direction, NONE for none.
         * @param previousState
         *            State of the block before changes may have happened. Pass
         *            null to ignore.
         */
        public BlockChangeEntry(long id,  int tick, int x, int y, int z, 
                Direction direction, IBlockCacheNode previousState) {
            this.id = id;
            this.tick = tick;
            this.x = x;
            this.y = y;
            this.z = z;
            this.direction = direction;
            this.previousState = previousState;
        }

        @Override
        public boolean equals(final Object obj) {
            if (obj == null || !(obj instanceof BlockChangeEntry)) {
                return false;
            }
            final BlockChangeEntry other = (BlockChangeEntry) obj;
            return id == other.id && tick == other.tick 
                    && x == other.x && z == other.z && y == other.y 
                    && direction == other.direction;
        }

        /**
         * Test if two entries have overlapping intervals of validity (tick).
         * 
         * @param other
         * @return
         */
        public boolean overlapsIntervalOfValidity(final BlockChangeEntry other) {
            return nextEntryTick < 0 && other.nextEntryTick < 0
                    || nextEntryTick < 0 && tick <= other.nextEntryTick
                    || other.nextEntryTick < 0 && other.tick <= nextEntryTick
                    || tick <= other.nextEntryTick && other.tick <= nextEntryTick;
        }

    }

    /** Change id/count, increasing with each entry added internally. */
    private long maxChangeId = 0;

    /** Global maximum age for entries, in ticks. */
    private int expirationAgeTicks = 80;
    /** Size at which entries get skipped, per world node. */
    private int worldNodeSkipSize = 500;

    private int activityResolution = 32; // TODO: getter/setter/config.

    /**
     * Store the WorldNode instances by UUID, containing the block change
     * entries (and filters). Latest entries must be sorted to the end.
     */
    private final Map<UUID, WorldNode> worldMap = new LinkedHashMap<UUID, BlockChangeTracker.WorldNode>();

    /** Use to avoid duplicate entries with pistons. Always empty after processing. */
    private final Set<Block> processBlocks = new LinkedHashSet<Block>();

    /** Ensure to set from extern. */
    private IGenericInstanceHandle<BlockCache> blockCacheHandle = null;

    private final OnGroundReference onGroundReference = new OnGroundReference();

    /*
     * TODO: Consider tracking regions of player activity (chunk sections, with
     * a margin around the player) and filter.
     */

    /**
     * Process the data, as given by a BlockPistonExtendEvent or
     * BlockPistonRetractEvent.
     * 
     * @param pistonBlock
     *            This block is added directly, unless null.
     * @param blockFace
     * @param movedBlocks
     *            Unless null, each block and the relative block in the given
     *            direction (!) are added.
     */
    public void addPistonBlocks(final Block pistonBlock, final BlockFace blockFace, final List<Block> movedBlocks) {
        checkProcessBlocks(); // TODO: Remove, once sure, that processing never ever generates an exception.
        final int tick = TickTask.getTick();
        final World world = pistonBlock.getWorld();
        final WorldNode worldNode = getOrCreateWorldNode(world, tick);
        final long changeId = ++maxChangeId;
        // Avoid duplicates by adding to a set.
        if (pistonBlock != null) {
            processBlocks.add(pistonBlock);
        }
        if (movedBlocks != null) {
            for (final Block movedBlock : movedBlocks) {
                processBlocks.add(movedBlock);
                processBlocks.add(movedBlock.getRelative(blockFace));
            }
        }
        // Process queued blocks.
        final BlockCache blockCache = blockCacheHandle.getHandle();
        blockCache.setAccess(world); // Assume all users always clean up after use :).
        for (final Block block : processBlocks) {
            addPistonBlock(changeId, tick, worldNode, block.getX(), block.getY(), block.getZ(), 
                    blockFace, blockCache);
        }
        blockCache.cleanup();
        processBlocks.clear();
    }

    /**
     * Add a block moved by a piston (or the piston itself).
     * 
     * @param changeId
     * @param tick
     * @param worldNode
     * @param x
     * @param y
     * @param z
     * @param blockFace
     * @param blockCache
     *            For retrieving the current block state.
     */
    private void addPistonBlock(final long changeId, final int tick, final WorldNode worldNode, 
            final int x, final int y, final int z, final BlockFace blockFace, final BlockCache blockCache) {
        // TODO: A filter for regions of player activity.
        // TODO: Test which ones can actually move a player (/how).
        // Add this block.
        addBlockChange(changeId, tick, worldNode, x, y, z, Direction.getDirection(blockFace), 
                blockCache.getOrCreateBlockCacheNode(x, y, z, true));
    }

    /**
     * Add blocks as neutral past states (no moving direction). All blocks are
     * to be in the same world (no consistency checks!), the world of the first
     * block is used.
     * 
     * @param blocks
     *            Could be/have empty / null / null entries, duplicate blocks
     *            will be ignored.
     */
    public void addBlocks(final Block... blocks) {
        if (blocks == null || blocks.length == 0) {
            return;
        }
        addBlocks(Arrays.asList(blocks));
    }

    /**
     * Add blocks as neutral past states (no moving direction). All blocks are
     * to be in the same world (no consistency checks!), the world of the first
     * block is used.
     * 
     * @param blocks
     *            Could be/have empty / null / null entries, duplicate blocks
     *            will be ignored.
     */
    public void addBlocks(final Collection<Block> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return;
        }
        checkProcessBlocks(); // TODO: Remove, once sure, that processing never ever generates an exception.
        // Collect non null blocks first, set world.
        World world = null;
        for (final Block block : blocks) {
            if (block != null) {
                if (world == null) {
                    world = block.getWorld();
                }
                processBlocks.add(block);
            }
        }
        if (world == null || !processBlocks.isEmpty()) {
            processBlocks.clear(); // In case the world is null (unlikely).
            return;
        }
        // Add blocks.
        final int tick = TickTask.getTick();
        final WorldNode worldNode = getOrCreateWorldNode(world, tick);
        final long changeId = ++maxChangeId;
        // Process queued blocks.
        final BlockCache blockCache = blockCacheHandle.getHandle();
        blockCache.setAccess(world); // Assume all users always clean up after use :).
        for (final Block block : processBlocks) {
            addBlock(changeId, tick, worldNode, block.getX(), block.getY(), block.getZ(), blockCache);
        }
        blockCache.cleanup();
        processBlocks.clear();
    }

    /**
     * Neutral (no direction) adding of a block state.
     * 
     * @param changeId
     * @param tick
     * @param world
     * @param block
     * @param blockCache
     */
    private final void addBlock(final long changeId, final int tick, final WorldNode worldNode, 
            final int x, final int y, final int z, final BlockCache blockCache) {
        addBlockChange(changeId, tick, worldNode, x, y, z, Direction.NONE, 
                blockCache.getOrCreateBlockCacheNode(x, y, z, true));
    }

    private final WorldNode getOrCreateWorldNode(final World world, final int tick) {
        final UUID worldId = world.getUID();
        WorldNode worldNode = worldMap.get(worldId);
        if (worldNode == null) {
            // TODO: With activity tracking this should be a return.
            worldNode = new WorldNode(worldId);
            worldMap.put(worldId, worldNode);
        }
        // TODO: (else) With activity tracking still check if lastActivityTick is too old (lazily expire entire worlds).
        return worldNode;
    }

    /**
     * Add a block change.
     * 
     * @param x
     * @param y
     * @param z
     * @param direction
     *            If not NONE, moving the block into that direction is assumed.
     */
    private void addBlockChange(final long changeId, final int tick, final WorldNode worldNode, 
            final int x, final int y, final int z, final Direction direction, final IBlockCacheNode previousState) {
        worldNode.lastChangeTick = tick;
        final BlockChangeEntry entry = new BlockChangeEntry(changeId, tick, x, y, z, direction, previousState);
        LinkedList<BlockChangeEntry> entries = worldNode.blocks.get(x, y, z, MoveOrder.END);
        ActivityNode activityNode = worldNode.getActivityNode(x, y, z, activityResolution);
        if (entries == null) {
            entries = new LinkedList<BlockChangeTracker.BlockChangeEntry>();
            worldNode.blocks.put(x, y, z, entries, MoveOrder.END); // Add to end.
        }
        else {
            // Lazy expiration check for this block.
            if (!entries.isEmpty()) { 
                if (entries.getFirst().tick < tick - expirationAgeTicks) {
                    final int expired = expireEntries(tick - expirationAgeTicks, entries);
                    worldNode.size -= expired;
                    activityNode.count -= expired;
                }
                // Re-check in case of invalidation.
                if (!entries.isEmpty()) {
                    // Update the nextEntryTick for the last entry in the list.
                    entries.getLast().nextEntryTick = tick;
                }
            }
        }
        /*
         * TODO: Prevent too fast changing states (of similar nature) to cause
         * slowed down checking (isOnGround). Means could be to search for past
         * states that exactly match the current state, within some timing
         * margin, and then increase the duration of validity for that past
         * entry instead of adding a new entry. Needs some care, to not have
         * that entry invalidated by tick, but could be interesting for the case
         * of tick-clock-driven piston/door setups, such as survivalfly-traps
         * and derp-o-matic machines (those could be detected by other means,
         * however NCP shouldn't impose such restrictions on servers by
         * default). Thinking of having to use often redundant physics events
         * (falling blocks?), this will probably be necessary to add, and this
         * kind of compressing mechanism could still be controlled by further
         * parameters (both configuration + always for physics).
         */
        entries.add(entry); // Add latest to the end always.
        activityNode.count ++;
        worldNode.size ++;
        //DebugUtil.debug("Add block change: " + x + "," + y + "," + z + " " + direction + " " + changeId); // TODO: REMOVE
    }

    private int expireEntries(final int olderThanTick, final LinkedList<BlockChangeEntry> entries) {
        int removed = 0;
        final Iterator<BlockChangeEntry> it = entries.iterator();
        while (it.hasNext()) {
            if (it.next().tick < olderThanTick) {
                it.remove();
                removed ++;
            }
            else {
                return removed;
            }
        }
        return removed;
    }

    /**
     * Check expiration on tick.
     * 
     * @param currentTick
     */
    public void checkExpiration(final int currentTick) {
        final int olderThanTick = currentTick - expirationAgeTicks;
        final Iterator<Entry<UUID, WorldNode>> it = worldMap.entrySet().iterator();
        while (it.hasNext()) {
            final WorldNode worldNode = it.next().getValue();
            if (worldNode.lastChangeTick < olderThanTick) {
                worldNode.clear();
                it.remove();
            }
            else {
                // Check for expiration of individual blocks.
                if (worldNode.size < worldNodeSkipSize) {
                    continue;
                }
                final Iterator<fr.neatmonster.nocheatplus.utilities.ds.map.CoordMap.Entry<LinkedList<BlockChangeEntry>>> blockIt = worldNode.blocks.iterator();
                while (blockIt.hasNext()) {
                    final fr.neatmonster.nocheatplus.utilities.ds.map.CoordMap.Entry<LinkedList<BlockChangeEntry>> entry = blockIt.next();
                    final LinkedList<BlockChangeEntry> entries = entry.getValue();
                    final ActivityNode activityNode = worldNode.getActivityNode(entry.getX(), entry.getY(), entry.getZ(), activityResolution);
                    if (!entries.isEmpty()) {
                        if (entries.getFirst().tick < olderThanTick) {
                            final int expired = expireEntries(olderThanTick, entries);
                            worldNode.size -= expired;
                            activityNode.count -= expired;
                        }
                    }
                    if (entries.isEmpty()) {
                        blockIt.remove();
                        if (activityNode.count <= 0) { // Safety first.
                            worldNode.removeActivityNode(entry.getX(), entry.getY(), entry.getZ(), activityResolution);
                        }
                    }
                }
                if (worldNode.size <= 0) {
                    // TODO: With activity tracking, nodes get removed based on last activity only.
                    it.remove();
                }
            }
        }
    }

    /**
     * Query past block states and moved blocks, including direction of moving.
     * 
     * @param ref
     *            Reference for checking the validity of BlockChangeEntry
     *            instances. No changes are made to the passed instance,
     *            canUpdateWith is called. Pass null to skip further validation.
     * @param tick
     *            The current tick. Used for lazy expiration.
     * @param worldId
     * @param x
     *            Block Coordinates.
     * @param y
     * @param z
     * @param direction
     *            Desired direction of a moved block. Pass null to ignore
     *            direction.
     * @return The matching entry, or null if there is no matching entry.
     */
    public BlockChangeEntry getBlockChangeEntry(final BlockChangeReference ref, final int tick, final UUID worldId, 
            final int x, final int y, final int z, final Direction direction) {
        final WorldNode worldNode = getValidWorldNode(tick, worldId);
        if (worldNode == null) {
            return null;
        }
        // TODO: Might add some policy (start at age, oldest first, newest first).
        final LinkedList<BlockChangeEntry> entries = getValidBlockChangeEntries(tick, worldNode, x, y, z);
        if (entries != null) {
            for (final BlockChangeEntry entry : entries) {
                if (ref == null || ref.canUpdateWith(entry) && (direction == null || entry.direction == direction)) {
                    return entry;
                }
            }
        }
        return null;
    }

    /**
     * Determine if a past state can be found where the given bounds would have
     * been on ground. The span of the given BlockChangeReference instance is
     * only updated on success (pass a copy or store span/data otherwise for
     * checking multiple blocks). This method will only check a position, if at
     * least one stored node can be found. If no stored node exist for the
     * world+coordinates, false will be returned (assumes that you have already
     * checked with BlockProperties.isOnGround or similar).
     * 
     * @param blockCache
     * @param ref
     * @param tick
     * @param worldId
     * @param minX
     * @param minY
     * @param minZ
     * @param maxX
     * @param maxY
     * @param maxZ
     * @param ignoreFlags
     * @return
     */
    public boolean isOnGround(final BlockCache blockCache, 
            final BlockChangeReference ref, final int tick, final UUID worldId,
            final double minX, final double minY, final double minZ, 
            final double maxX, final double maxY, final double maxZ, 
            final long ignoreFlags) {
        // (The method has been put here for efficiency. Alternative: put specific stuff into OnGroundReference.)
        // TODO: Keep the outer iteration code in line with BlockProperties.isOnGround.
        final WorldNode worldNode = getValidWorldNode(tick, worldId);
        if (worldNode == null) {
            return false;
        }
        final int maxBlockY = blockCache.getMaxBlockY();
        final int iMinX = Location.locToBlock(minX);
        final int iMaxX = Location.locToBlock(maxX);
        final int iMinY = Location.locToBlock(minY - 0.5626);
        if (iMinY > maxBlockY) {
            return false;
        }
        final int iMaxY = Math.min(Location.locToBlock(maxY), maxBlockY);
        final int iMinZ = Location.locToBlock(minZ);
        final int iMaxZ = Location.locToBlock(maxZ);
        onGroundReference.init(blockCache, ref, ignoreFlags);
        for (int x = iMinX; x <= iMaxX; x++) {
            for (int z = iMinZ; z <= iMaxZ; z++) {
                onGroundReference.setEntriesAbove(getValidBlockChangeEntries(tick, worldNode, x, iMaxY + 1, z));
                for (int y = iMaxY; y >= iMinY; y --) {
                    onGroundReference.setEntries(getValidBlockChangeEntries(tick, worldNode, x, y, z));
                    if (!onGroundReference.hasAnyEntries() || !onGroundReference.initEntries(x, y, z)) {
                        // Don't break here.
                        continue;
                    }
                    boolean shouldBreak = true; // Indicate no better than abort-y-iteration found.
                    do {
                        switch(BlockProperties.isOnGround(blockCache, minX, minY, minZ, maxX, maxY, maxZ, 
                                ignoreFlags, x, y, z, 
                                onGroundReference.getNode(), onGroundReference.getNodeAbove())) {
                                    case YES:
                                        onGroundReference.updateSpan();
                                        onGroundReference.clear();
                                        return true;
                                    case MAYBE:
                                        shouldBreak = false;
                                    case NO:
                                        break;
                        }
                    } while (onGroundReference.advance());
                    // (End of y-loop.)
                    if (shouldBreak) {
                        break; // case NO for all, end y-iteration.
                    }
                    else {
                        onGroundReference.moveDown();
                    }
                }
            }
        }
        onGroundReference.clear();
        return false;
    }

    /**
     * Get a WorldNode instance, after lazy expiration. If no node is there, or
     * the node expired, null is returned.
     * 
     * @param tick
     * @param worldId
     * @return
     */
    private WorldNode getValidWorldNode(final int tick, final UUID worldId) {
        final WorldNode worldNode = worldMap.get(worldId);
        if (worldNode == null) {
            return null;
        }
        else {
            // Lazy expiration of entire world nodes.
            if (worldNode.lastChangeTick < tick - expirationAgeTicks) {
                worldNode.clear();
                worldMap.remove(worldNode.worldId);
                //DebugUtil.debug("EXPIRE WORLD"); // TODO: REMOVE
                return null;
            }
            else {
                return worldNode;
            }
        }
    }

    /**
     * Get the entries for the given coordinates, after lazy expiration. If no
     * entries are there, null is returned.
     * 
     * @param worldNode
     * @param x
     * @param y
     * @param z
     * @param tick
     * @return
     */
    private LinkedList<BlockChangeEntry> getValidBlockChangeEntries(final int tick, final WorldNode worldNode, 
            final int x, final int y, final int z) {
        // TODO: Consider return ListIterator (wind 1 backwards with an entry fetched).
        final int expireOlderThanTick = tick - expirationAgeTicks;
        // Check individual entries.
        final LinkedList<BlockChangeEntry> entries = worldNode.blocks.get(x, y, z);
        if (entries == null) {
            //DebugUtil.debug("NO ENTRIES: " + x + "," + y + "," + z);
            return null;
        }
        final ActivityNode activityNode = worldNode.getActivityNode(x, y, z, activityResolution);
        //DebugUtil.debug("Entries at: " + x + "," + y + "," + z);
        final Iterator<BlockChangeEntry> it = entries.iterator();
        while (it.hasNext()) {
            final BlockChangeEntry entry = it.next();
            if (entry.tick < expireOlderThanTick) {
                //DebugUtil.debug("Lazy expire: " + x + "," + y + "," + z + " " + entry.id);
                it.remove();
                activityNode.count --;
            }
            else {
                return entries;
            }
        }
        // Remove entries from map + remove world if empty.
        if (entries.isEmpty()) {
            worldNode.blocks.remove(x, y, z);
            if (worldNode.size == 0) {
                worldMap.remove(worldNode.worldId);
            }
            else if (activityNode.count <= 0) { // Safety.
                worldNode.removeActivityNode(x, y, z, activityResolution);
            }
            return null;
        }
        else {
            // TODO: ERROR
            return entries;
        }
    }

    /**
     * Test if there has been block change activity within the specified cuboid.
     * Mind that queries for larger regions than chunk size (default 32) may be
     * inefficient. The coordinates need not be ordered.
     * 
     * @param worldId
     * @param pos1
     * @param pos2
     * @param margin
     * @return
     */
    public boolean hasActivity(final UUID worldId,
            final IGetPosition pos1, final IGetPosition pos2, final double margin) {
        return hasActivityShuffled(worldId, pos1.getX(), pos1.getY(), pos1.getZ(), 
                pos2.getX(), pos2.getY(), pos2.getZ(), margin);
    }

    /**
     * Test if there has been block change activity within the specified cuboid.
     * Mind that queries for larger regions than chunk size (default 32) may be
     * inefficient. The coordinates need not be ordered.
     * 
     * @param worldId
     * @param x1
     * @param y1
     * @param z1
     * @param x2
     * @param y2
     * @param z2
     * @param margin Margin to add towards all sides.
     * @return
     */
    public boolean hasActivityShuffled(final UUID worldId, final double x1, final double y1, final double z1,
            final double x2, final double y2, final double z2, final double margin) {
        final double minX = Math.min(x1, x2) - margin;
        final double minY = Math.min(y1, y2) - margin;
        final double minZ = Math.min(z1, z2) - margin;
        final double maxX = Math.max(x1, x2) + margin;
        final double maxY = Math.max(y1, y2) + margin;
        final double maxZ = Math.max(z1, z2) + margin;
        return hasActivity(worldId, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Test if there has been block change activity within the specified cuboid.
     * Mind that queries for larger regions than chunk size (default 32) may be
     * inefficient. The coordinates need not be ordered.
     * 
     * @param worldId
     * @param x1
     * @param y1
     * @param z1
     * @param x2
     * @param y2
     * @param z2
     * @return
     */
    public boolean hasActivityShuffled(final UUID worldId, final double x1, final double y1, final double z1,
            final double x2, final double y2, final double z2) {
        return hasActivityShuffled(worldId, 
                Location.locToBlock(x1), Location.locToBlock(y1), Location.locToBlock(z1),
                Location.locToBlock(x2), Location.locToBlock(y2), Location.locToBlock(z2));
    }

    /**
     * Test if there has been block change activity within the specified cuboid.
     * Mind that queries for larger regions than chunk size (default 32) may be
     * inefficient. The coordinates have to be ordered.
     * 
     * @param worldId
     * @param minX
     * @param minY
     * @param z1
     * @param x2
     * @param y2
     * @param z2
     * @return
     */
    public boolean hasActivity(final UUID worldId, final double minX, final double minY, final double minZ,
            final double maxX, final double maxY, final double maxZ) {
        return hasActivityShuffled(worldId, 
                Location.locToBlock(minX), Location.locToBlock(minY), Location.locToBlock(minZ),
                Location.locToBlock(maxX), Location.locToBlock(maxY), Location.locToBlock(maxZ));
    }

    /**
     * Test if there has been block change activity within the specified cuboid.
     * Mind that queries for larger regions than chunk size (default 32) may be
     * inefficient. The coordinates need not be ordered.
     * 
     * @param worldId
     * @param x1
     * @param y1
     * @param z1
     * @param x2
     * @param y2
     * @param z2
     * @return
     */
    public boolean hasActivityShuffled(final UUID worldId, final int x1, final int y1, final int z1,
            final int x2, final int y2, final int z2) {
        final int minX = Math.min(x1, x2);
        final int minY = Math.min(y1, y2);
        final int minZ = Math.min(z1, z2);
        final int maxX = Math.max(x1, x2);
        final int maxY = Math.max(y1, y2);
        final int maxZ = Math.max(z1, z2);
        return hasActivity(worldId, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Test if there has been block change activity within the specified cuboid.
     * Mind that queries for larger regions than chunk size (default 32) may be
     * inefficient. The coordinates have to be ordered by 3xmin+3xmax.
     * 
     * @param worldId
     * @param minX
     * @param minY
     * @param minZ
     * @param maxX
     * @param maxY
     * @param maxZ
     * @return
     */
    public boolean hasActivity(final UUID worldId, final int minX, final int minY, final int minZ,
            final int maxX, final int maxY, final int maxZ) {
        final WorldNode worldNode = worldMap.get(worldId);
        if (worldNode == null) {
            return false;
        }
        /*
         *  TODO: After all a better data structure would allow an almost direct return (despite most of the time iterating one chunk).
         */
        for (int x = minX / activityResolution; x <= maxX / activityResolution; x++) {
            for (int z = minZ / activityResolution; z <= maxZ / activityResolution; z++) {
                for (int y = minY / activityResolution; y <= maxY / activityResolution; y++) {
                    if (worldNode.activityMap.contains(x, y, z)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public void clear() {
        for (final WorldNode worldNode : worldMap.values()) {
            worldNode.clear();
        }
        worldMap.clear();
    }

    public int size() {
        int size = 0;
        for (final WorldNode worldNode : worldMap.values()) {
            size += worldNode.size;
        }
        return size;
    }

    public int getExpirationAgeTicks() {
        return expirationAgeTicks;
    }

    public void setExpirationAgeTicks(int expirationAgeTicks) {
        this.expirationAgeTicks = expirationAgeTicks;
    }

    public int getWorldNodeSkipSize() {
        return worldNodeSkipSize;
    }

    public void setWorldNodeSkipSize(int worldNodeSkipSize) {
        this.worldNodeSkipSize = worldNodeSkipSize;
    }

    public void updateBlockCacheHandle() {
        if (this.blockCacheHandle != null) {
            this.blockCacheHandle.disableHandle();
        }
        this.blockCacheHandle = NCPAPIProvider.getNoCheatPlusAPI().getGenericInstanceHandle(BlockCache.class);
    }

    /**
     * On starting to adding blocks: processBlocks has to be empty. If not empty, warn and clear. 
     */
    private void checkProcessBlocks() {
        if (!processBlocks.isEmpty()) {
            processBlocks.clear();
            NCPAPIProvider.getNoCheatPlusAPI().getLogManager().warning(Streams.STATUS, "BlockChangeTracker: processBlocks is not empty on starting to add blocks.");
        }
    }

}

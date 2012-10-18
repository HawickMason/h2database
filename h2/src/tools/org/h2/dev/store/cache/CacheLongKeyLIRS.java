/*
 * Copyright 2004-2012 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.dev.store.cache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A scan resistant cache that uses keys of type long. It is meant to cache
 * objects that are relatively costly to acquire, for example file content.
 * <p>
 * This implementation is multi-threading safe and supports concurrent access.
 * Null keys or null values are not allowed. The map fill factor is at most 75%.
 * <p>
 * Each entry is assigned a distinct memory size, and the cache will try to use
 * at most the specified amount of memory. The memory unit is not relevant,
 * however it is suggested to use bytes as the unit.
 * <p>
 * This class implements an approximation of the the LIRS replacement algorithm
 * invented by Xiaodong Zhang and Song Jiang as described in
 * http://www.cse.ohio-state.edu/~zhang/lirs-sigmetrics-02.html with a few
 * smaller changes: An additional queue for non-resident entries is used, to
 * prevent unbound memory usage. The maximum size of this queue is at most the
 * size of the rest of the stack. About 6.25% of the mapped entries are cold.
 * <p>
 * Internally, the cache is split into a number of segments, and each segment is
 * an individual LIRS cache.
 * <p>
 * Accessed entries are only moved to the top of the stack if at least a number
 * of other entries have been moved to the front (1% by default). Write access
 * and moving entries to the top of the stack is synchronized per segment.
 *
 * @author Thomas Mueller
 * @param <V> the value type
 */
public class CacheLongKeyLIRS<V> {

    /**
     * The maximum memory this cache should use.
     */
    private long maxMemory;

    /**
     * The average memory used by one entry.
     */
    private int averageMemory;

    private Segment<V>[] segments;

    private int segmentCount;
    private int segmentShift;
    private int segmentMask;
    private final int stackMoveDistance;

    private CacheLongKeyLIRS(long maxMemory, int averageMemory, int segmentCount, int stackMoveDistance) {
        setMaxMemory(maxMemory);
        setAverageMemory(averageMemory);
        if (Integer.bitCount(segmentCount) != 1) {
            throw new IllegalArgumentException("The segment count must be a power of 2, is " + segmentCount);
        }
        this.segmentCount = segmentCount;
        this.stackMoveDistance = stackMoveDistance;
        clear();
    }

    @SuppressWarnings("unchecked")
    public void clear() {
        segmentMask = segmentCount - 1;
        segments = new Segment[segmentCount];
        for (int i = 0; i < segmentCount; i++) {
            long max = Math.max(1, maxMemory / segmentCount);
            segments[i] = new Segment<V>(
                    max, averageMemory, stackMoveDistance);
        }
        segmentShift = Integer.numberOfTrailingZeros(segments[0].sizeMapArray());
    }

    private Entry<V> find(long key) {
        int hash = getHash(key);
        return getSegment(hash).find(key, hash);
    }

    /**
     * Check whether there is a resident entry for the given key. This method
     * does not adjust the internal state of the cache.
     *
     * @param key the key (may not be null)
     * @return true if there is a resident entry
     */
    public boolean containsKey(long key) {
        int hash = getHash(key);
        return getSegment(hash).containsKey(key, hash);
    }

    /**
     * Get the value for the given key if the entry is cached. This method does
     * not modify the internal state.
     *
     * @param key the key (may not be null)
     * @return the value, or null if there is no resident entry
     */
    public V peek(long key) {
        Entry<V> e = find(key);
        return e == null ? null : e.value;
    }

    /**
     * Add an entry to the cache. The entry may or may not exist in the cache
     * yet. This method will usually mark unknown entries as cold and known
     * entries as hot.
     *
     * @param key the key (may not be null)
     * @param value the value (may not be null)
     * @param memory the memory used for the given entry
     * @return the old value, or null if there is no resident entry
     */
    public V put(long key, V value, int memory) {
        int hash = getHash(key);
        return getSegment(hash).put(key, hash, value, memory);
    }

    /**
     * Add an entry to the cache using the average memory size.
     *
     * @param key the key (may not be null)
     * @param value the value (may not be null)
     * @return the old value, or null if there is no resident entry
     */
    public V put(long key, V value) {
        return put(key, value, averageMemory);
    }

    /**
     * Remove an entry. Both resident and non-resident entries can be removed.
     *
     * @param key the key (may not be null)
     * @return the old value, or null if there is no resident entry
     */
    public synchronized V remove(long key) {
        int hash = getHash(key);
        return getSegment(hash).remove(key, hash);
    }

    /**
     * Get the memory used for the given key.
     *
     * @param key the key (may not be null)
     * @return the memory, or 0 if there is no resident entry
     */
    public int getMemory(long key) {
        int hash = getHash(key);
        return getSegment(hash).getMemory(key, hash);
    }

    /**
     * Get the value for the given key if the entry is cached. This method
     * adjusts the internal state of the cache, to ensure commonly used entries
     * stay in the cache.
     *
     * @param key the key (may not be null)
     * @return the value, or null if there is no resident entry
     */
    public V get(long key) {
        int hash = getHash(key);
        return getSegment(hash).get(key, hash);
    }

    private Segment<V> getSegment(int hash) {
        int segmentIndex = (hash >>> segmentShift) & segmentMask;
        return segments[segmentIndex];
    }

    static int getHash(long key) {
        int hash = (int) ((key >>> 32) ^ key);
        // Doug Lea's supplemental secondaryHash function (inlined)
        // to protect against hash codes that don't differ in low order bits
        hash ^= (hash >>> 20) ^ (hash >>> 12);
        hash ^= (hash >>> 7) ^ (hash >>> 4);
        return hash;
    }

    /**
     * Get the currently used memory.
     *
     * @return the used memory
     */
    public long getUsedMemory() {
        long x = 0;
        for (Segment<V> s : segments) {
            x += s.getUsedMemory();
        }
        return x;
    }

    /**
     * Set the maximum memory this cache should use. This will not immediately
     * cause entries to get removed however; it will only change the limit. To
     * resize the internal array, call the clear method.
     *
     * @param maxMemory the maximum size (1 or larger)
     */
    public void setMaxMemory(long maxMemory) {
        if (maxMemory <= 0) {
            throw new IllegalArgumentException("Max memory must be larger than 0");
        }
        this.maxMemory = maxMemory;
        if (segments != null) {
            long max = 1 + maxMemory / segments.length;
            for (Segment<V> s : segments) {
                s.setMaxMemory(max);
            }
        }
    }

    /**
     * Set the average memory used per entry. It is used to calculate the length
     * of the internal array.
     *
     * @param averageMemory the average memory used (1 or larger)
     */
    public void setAverageMemory(int averageMemory) {
        if (averageMemory <= 0) {
            throw new IllegalArgumentException("Average memory must be larger than 0");
        }
        this.averageMemory = averageMemory;
        if (segments != null) {
            for (Segment<V> s : segments) {
                s.setAverageMemory(averageMemory);
            }
        }
    }

    /**
     * Get the average memory used per entry.
     *
     * @return the average memory
     */
    public int getAverageMemory() {
        return averageMemory;
    }

    /**
     * Get the maximum memory to use.
     *
     * @return the maximum memory
     */
    public long getMaxMemory() {
        return maxMemory;
    }

    /**
     * Create a new cache with the given number of entries, and the default
     * settings (an average size of 1 per entry, 16 segments, and stack move
     * distance equals to the max entry size divided by 100).
     *
     * @param maxEntries the maximum number of entries
     * @return the cache
     */
    public static <K, V> CacheLongKeyLIRS<V> newInstance(int maxEntries) {
        return new CacheLongKeyLIRS<V>(maxEntries, 1, 16, maxEntries / 100);
    }

    /**
     * Create a new cache with the given memory size.
     *
     * @param maxMemory the maximum memory to use (1 or larger)
     * @param averageMemory the average memory (1 or larger)
     * @param segmentCount the number of cache segments (must be a power of 2)
     * @param stackMoveDistance how many other item are to be moved to the top
     *        of the stack before the current item is moved
     * @return the cache
     */
    public static <V> CacheLongKeyLIRS<V> newInstance(int maxMemory, int averageMemory,
            int segmentCount, int stackMoveDistance) {
        return new CacheLongKeyLIRS<V>(maxMemory, averageMemory, segmentCount, stackMoveDistance);
    }

    /**
     * Get the entry set for all resident entries.
     *
     * @return the entry set
     */
    public synchronized Set<Map.Entry<Long, V>> entrySet() {
        HashMap<Long, V> map = new HashMap<Long, V>();
        for (long k : keySet()) {
            map.put(k,  find(k).value);
        }
        return map.entrySet();
    }

    /**
     * Get the set of keys for resident entries.
     *
     * @return the set of keys
     */
    public synchronized Set<Long> keySet() {
        HashSet<Long> set = new HashSet<Long>();
        for (Segment<V> s : segments) {
            set.addAll(s.keySet());
        }
        return set;
    }

    /**
     * Get the number of non-resident entries in the cache.
     *
     * @return the number of non-resident entries
     */
    public int sizeNonResident() {
        int x = 0;
        for (Segment<V> s : segments) {
            x += s.sizeNonResident();
        }
        return x;
    }

    /**
     * Get the length of the internal map array.
     *
     * @return the size of the array
     */
    public int sizeMapArray() {
        int x = 0;
        for (Segment<V> s : segments) {
            x += s.sizeMapArray();
        }
        return x;
    }

    /**
     * Get the number of hot entries in the cache.
     *
     * @return the number of hot entries
     */
    public int sizeHot() {
        int x = 0;
        for (Segment<V> s : segments) {
            x += s.sizeHot();
        }
        return x;
    }

    /**
     * Get the number of resident entries.
     *
     * @return the number of entries
     */
    public int size() {
        int x = 0;
        for (Segment<V> s : segments) {
            x += s.size();
        }
        return x;
    }

    /**
     * Get the list of keys. This method allows to read the internal state of
     * the cache.
     *
     * @param cold if true, only keys for the cold entries are returned
     * @param nonResident true for non-resident entries
     * @return the key list
     */
    public synchronized List<Long> keys(boolean cold, boolean nonResident) {
        ArrayList<Long> keys = new ArrayList<Long>();
        for (Segment<V> s : segments) {
            keys.addAll(s.keys(cold, nonResident));
        }
        return keys;
    }

    /**
     * Get the values for all resident entries.
     *
     * @return the entry set
     */
    public List<V> values() {
        ArrayList<V> list = new ArrayList<V>();
        for (long k : keySet()) {
            V value = find(k).value;
            if (value != null) {
                list.add(value);
            }
        }
        return list;
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    public boolean containsValue(Object value) {
        return getMap().containsValue(value);
    }

    public Map<Long, V> getMap() {
        HashMap<Long, V> map = new HashMap<Long, V>();
        for (long k : keySet()) {
            V x = find(k).value;
            if (x != null) {
                map.put(k, x);
            }
        }
        return map;
    }

    public void putAll(Map<Long, ? extends V> m) {
        for (Map.Entry<Long, ? extends V> e : m.entrySet()) {
            // copy only non-null entries
            put(e.getKey(), e.getValue());
        }
    }

    /**
     * A cache segment
     *
     * @param <V> the value type
     */
    static class Segment<V> {

        /**
         * How many other item are to be moved to the top of the stack before
         * the current item is moved.
         */
        private final int stackMoveDistance;

        /**
         * The maximum memory this cache should use.
         */
        private long maxMemory;

        /**
         * The average memory used by one entry.
         */
        private int averageMemory;

        /**
         * The currently used memory.
         */
        private long usedMemory;

        /**
         * The number of (hot, cold, and non-resident) entries in the map.
         */
        private int mapSize;

        /**
         * The bit mask that is applied to the key hash code to get the index in the
         * map array. The mask is the length of the array minus one.
         */
        private int mask;

        /**
         * The LIRS stack size.
         */
        private int stackSize;

        /**
         * The size of the LIRS queue for resident cold entries.
         */
        private int queueSize;

        /**
         * The size of the LIRS queue for non-resident cold entries.
         */
        private int queue2Size;

        /**
         * The map array. The size is always a power of 2.
         */
        private Entry<V>[] entries;

        /**
         * The stack of recently referenced elements. This includes all hot entries,
         * the recently referenced cold entries, and all non-resident cold entries.
         */
        private Entry<V> stack;

        /**
         * The queue of resident cold entries.
         */
        private Entry<V> queue;

        /**
         * The queue of non-resident cold entries.
         */
        private Entry<V> queue2;

        /**
         * The number of times any item was moved to the top of the stack.
         */
        private int stackMoveCounter;

        /**
         * Create a new cache.
         *
         * @param maxMemory the maximum memory to use
         * @param averageMemory the average memory usage of an object
         * @param stackMoveDistance the number of other entries to be moved to
         *        the top of the stack before moving an entry to the top
         */
        Segment(long maxMemory, int averageMemory, int stackMoveDistance) {
            setMaxMemory(maxMemory);
            setAverageMemory(averageMemory);
            this.stackMoveDistance = stackMoveDistance;
            clear();
        }

        synchronized void clear() {

            // calculate the size of the map array
            // assume a fill factor of at most 80%
            long maxLen = (long) (maxMemory / averageMemory / 0.75);
            // the size needs to be a power of 2
            long l = 8;
            while (l < maxLen) {
                l += l;
            }
            // the array size is at most 2^31 elements
            int len = (int) Math.min(1L << 31, l);
            // the bit mask has all bits set
            mask = len - 1;

            // initialize the stack and queue heads
            stack = new Entry<V>();
            stack.stackPrev = stack.stackNext = stack;
            queue = new Entry<V>();
            queue.queuePrev = queue.queueNext = queue;
            queue2 = new Entry<V>();
            queue2.queuePrev = queue2.queueNext = queue2;

            // first set to null - avoiding out of memory
            entries = null;
            @SuppressWarnings("unchecked")
            Entry<V>[] e = new Entry[len];
            entries = e;

            mapSize = 0;
            usedMemory = 0;
            stackSize = queueSize = queue2Size = 0;
        }

        V peek(long key, int hash) {
            Entry<V> e = find(key, hash);
            return e == null ? null : e.value;
        }

        int getMemory(long key, int hash) {
            Entry<V> e = find(key, hash);
            return e == null ? 0 : e.memory;
        }

        V get(long key, int hash) {
            Entry<V> e = find(key, hash);
            if (e == null) {
                // the entry was not found
                return null;
            }
            V value = e.value;
            if (value == null) {
                // it was a non-resident entry
                return null;
            }
            if (e.isHot()) {
                if (e != stack.stackNext) {
                    if (stackMoveDistance == 0 || stackMoveCounter - e.topMove > stackMoveDistance) {
                        access(key, hash);
                    }
                }
            } else {
                access(key, hash);
            }
            return value;
        }

        /**
         * Access an item, moving the entry to the top of the stack or front of the
         * queue if found.
         *
         * @param key the key
         */
        private synchronized void access(long key, int hash) {
            Entry<V> e = find(key, hash);
            if (e == null || e.value == null) {
                return;
            }
            if (e.isHot()) {
                if (e != stack.stackNext) {
                    if (stackMoveDistance == 0 || stackMoveCounter - e.topMove > stackMoveDistance) {
                        // move a hot entry to the top of the stack
                        // unless it is already there
                        boolean wasEnd = e == stack.stackPrev;
                        removeFromStack(e);
                        if (wasEnd) {
                            // if moving the last entry, the last entry
                            // could not be cold, which is not allowed
                            pruneStack();
                        }
                        addToStack(e);
                    }
                }
            } else {
                removeFromQueue(e);
                if (e.stackNext != null) {
                    // resident cold entries become hot
                    // if they are on the stack
                    removeFromStack(e);
                    // which means a hot entry needs to become cold
                    convertOldestHotToCold();
                } else {
                    // cold entries that are not on the stack
                    // move to the front of the queue
                    addToQueue(queue, e);
                }
                // in any case, the cold entry is moved to the top of the stack
                addToStack(e);
            }
        }

        synchronized V put(long key, int hash, V value, int memory) {
            if (value == null) {
                throw new NullPointerException();
            }
            V old;
            Entry<V> e = find(key, hash);
            if (e == null) {
                old = null;
            } else {
                old = e.value;
                remove(key, hash);
            }
            e = new Entry<V>();
            e.key = key;
            e.value = value;
            e.memory = memory;
            int index = hash & mask;
            e.mapNext = entries[index];
            entries[index] = e;
            usedMemory += memory;
            if (usedMemory > maxMemory && mapSize > 0) {
                // an old entry needs to be removed
                evict(e);
            }
            mapSize++;
            // added entries are always added to the stack
            addToStack(e);
            return old;
        }

        synchronized V remove(long key, int hash) {
            int index = hash & mask;
            Entry<V> e = entries[index];
            if (e == null) {
                return null;
            }
            V old;
            if (e.key == key) {
                old = e.value;
                entries[index] = e.mapNext;
            } else {
                Entry<V> last;
                do {
                    last = e;
                    e = e.mapNext;
                    if (e == null) {
                        return null;
                    }
                } while (e.key != key);
                old = e.value;
                last.mapNext = e.mapNext;
            }
            mapSize--;
            usedMemory -= e.memory;
            if (e.stackNext != null) {
                removeFromStack(e);
            }
            if (e.isHot()) {
                // when removing a hot entry, the newest cold entry gets hot,
                // so the number of hot entries does not change
                e = queue.queueNext;
                if (e != queue) {
                    removeFromQueue(e);
                    if (e.stackNext == null) {
                        addToStackBottom(e);
                    }
                }
            } else {
                removeFromQueue(e);
            }
            pruneStack();
            return old;
        }

        /**
         * Evict cold entries (resident and non-resident) until the memory limit is
         * reached. The new entry is added as a cold entry, except if it is the only
         * entry.
         *
         * @param newCold a new cold entry
         */
        private void evict(Entry<V> newCold) {
            // ensure there are not too many hot entries:
            // left shift of 5 is multiplication by 32, that means if there are less
            // than 1/32 (3.125%) cold entries, a new hot entry needs to become cold
            while ((queueSize << 5) < mapSize) {
                convertOldestHotToCold();
            }
            if (stackSize > 0) {
                // the new cold entry is at the top of the queue
                addToQueue(queue, newCold);
            }
            // the oldest resident cold entries become non-resident
            // but at least one cold entry (the new one) must stay
            while (usedMemory > maxMemory && queueSize > 1) {
                Entry<V> e = queue.queuePrev;
                usedMemory -= e.memory;
                removeFromQueue(e);
                e.value = null;
                e.memory = 0;
                addToQueue(queue2, e);
                // the size of the non-resident-cold entries needs to be limited
                while (queue2Size + queue2Size > stackSize) {
                    e = queue2.queuePrev;
                    int hash = getHash(e.key);
                    remove(e.key, hash);
                }
            }
        }

        private void convertOldestHotToCold() {
            // the last entry of the stack is known to be hot
            Entry<V> last = stack.stackPrev;
            // remove from stack - which is done anyway in the stack pruning, but we
            // can do it here as well
            removeFromStack(last);
            // adding an entry to the queue will make it cold
            addToQueue(queue, last);
            pruneStack();
        }

        /**
         * Ensure the last entry of the stack is cold.
         */
        private void pruneStack() {
            while (true) {
                Entry<V> last = stack.stackPrev;
                if (last == stack || last.isHot()) {
                    break;
                }
                // the cold entry is still in the queue
                removeFromStack(last);
            }
        }

        /**
         * Try to find an entry in the map.
         *
         * @param key the key
         * @return the entry (might be a non-resident)
         */
        Entry<V> find(long key, int hash) {
            int index = hash & mask;
            Entry<V> e = entries[index];
            while (e != null && e.key != key) {
                e = e.mapNext;
            }
            return e;
        }

        private void addToStack(Entry<V> e) {
            e.stackPrev = stack;
            e.stackNext = stack.stackNext;
            e.stackNext.stackPrev = e;
            stack.stackNext = e;
            stackSize++;
            e.topMove = stackMoveCounter++;
        }

        private void addToStackBottom(Entry<V> e) {
            e.stackNext = stack;
            e.stackPrev = stack.stackPrev;
            e.stackPrev.stackNext = e;
            stack.stackPrev = e;
            stackSize++;
        }

        private void removeFromStack(Entry<V> e) {
            e.stackPrev.stackNext = e.stackNext;
            e.stackNext.stackPrev = e.stackPrev;
            e.stackPrev = e.stackNext = null;
            stackSize--;
        }

        private void addToQueue(Entry<V> q, Entry<V> e) {
            e.queuePrev = q;
            e.queueNext = q.queueNext;
            e.queueNext.queuePrev = e;
            q.queueNext = e;
            if (e.value != null) {
                queueSize++;
            } else {
                queue2Size++;
            }
        }

        private void removeFromQueue(Entry<V> e) {
            e.queuePrev.queueNext = e.queueNext;
            e.queueNext.queuePrev = e.queuePrev;
            e.queuePrev = e.queueNext = null;
            if (e.value != null) {
                queueSize--;
            } else {
                queue2Size--;
            }
        }

        synchronized List<Long> keys(boolean cold, boolean nonResident) {
            ArrayList<Long> keys = new ArrayList<Long>();
            if (cold) {
                Entry<V> start = nonResident ? queue2 : queue;
                for (Entry<V> e = start.queueNext; e != start; e = e.queueNext) {
                    keys.add(e.key);
                }
            } else {
                for (Entry<V> e = stack.stackNext; e != stack; e = e.stackNext) {
                    keys.add(e.key);
                }
            }
            return keys;
        }

        int size() {
            return mapSize - queue2Size;
        }

        boolean containsKey(long key, int hash) {
            Entry<V> e = find(key, hash);
            return e != null && e.value != null;
        }

        synchronized Set<Long> keySet() {
            HashSet<Long> set = new HashSet<Long>();
            for (Entry<V> e = stack.stackNext; e != stack; e = e.stackNext) {
                set.add(e.key);
            }
            for (Entry<V> e = queue.queueNext; e != queue; e = e.queueNext) {
                set.add(e.key);
            }
            return set;
        }

        int sizeHot() {
            return mapSize - queueSize - queue2Size;
        }

        int sizeNonResident() {
            return queue2Size;
        }

        int sizeMapArray() {
            return entries.length;
        }

        long getUsedMemory() {
            return usedMemory;
        }

        void setMaxMemory(long maxMemory) {
            if (maxMemory <= 0) {
                throw new IllegalArgumentException("Max memory must be larger than 0");
            }
            this.maxMemory = maxMemory;
        }

        void setAverageMemory(int averageMemory) {
            if (averageMemory <= 0) {
                throw new IllegalArgumentException("Average memory must be larger than 0");
            }
            this.averageMemory = averageMemory;
        }

    }

    /**
     * A cache entry. Each entry is either hot (low inter-reference recency;
     * LIR), cold (high inter-reference recency; HIR), or non-resident-cold. Hot
     * entries are in the stack only. Cold entries are in the queue, and may be
     * in the stack. Non-resident-cold entries have their value set to null and
     * are in the stack and in the non-resident queue.
     *
     * @param <K> the key type
     * @param <V> the value type
     */
    static class Entry<V> {

        /**
         * The key.
         */
        long key;

        /**
         * The value. Set to null for non-resident-cold entries.
         */
        V value;

        /**
         * The estimated memory used.
         */
        int memory;

        /**
         * When the item was last moved to the top of the stack.
         */
        int topMove;

        /**
         * The next entry in the stack.
         */
        Entry<V> stackNext;

        /**
         * The previous entry in the stack.
         */
        Entry<V> stackPrev;

        /**
         * The next entry in the queue (either the resident queue or the
         * non-resident queue).
         */
        Entry<V> queueNext;

        /**
         * The previous entry in the queue.
         */
        Entry<V> queuePrev;

        /**
         * The next entry in the map
         */
        Entry<V> mapNext;

        /**
         * Whether this entry is hot. Cold entries are in one of the two queues.
         *
         * @return whether the entry is hot
         */
        boolean isHot() {
            return queueNext == null;
        }

    }

}
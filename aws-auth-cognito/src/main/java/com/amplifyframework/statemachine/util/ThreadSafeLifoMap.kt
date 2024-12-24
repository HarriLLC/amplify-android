package com.amplifyframework.statemachine.util

/**
 * A thread-safe map that mimics the behavior of a stack (Last-In-First-Out, LIFO).
 *
 * @param K The type of keys maintained by this map.
 * @param V The type of values maintained by this map.
 * @property maxSize The maximum number of elements allowed in the map.
 *                   If null, the map has no size limit.
 */
class LifoMap<K, V>(private val maxSize: Int? = null) {

    // Internal map to maintain the key-value pairs with insertion order.
    private val map = LinkedHashMap<K, V>()

    /**
     * Adds a key-value pair to the map. If the map exceeds the maximum size,
     * the oldest entry (first inserted) is removed to maintain the size constraint.
     *
     * @param key The key to add to the map.
     * @param value The value associated with the key.
     */
    @Synchronized
    fun push(key: K, value: V) {
        if (maxSize != null && map.size >= maxSize) {
            map.remove(map.keys.first()) // Remove the oldest entry to maintain size
        }
        map[key] = value
    }

    /**
     * Removes and returns the value associated with the most recently added key.
     *
     * @return The value of the last inserted key, or null if the map is empty.
     */
    @Synchronized
    fun pop(): V? {
        val lastKey = map.keys.lastOrNull() ?: return null
        return map.remove(lastKey)
    }

    /**
     * Removes and returns the value associated with the specified key.
     */
    @Synchronized
    fun pop(key: K): V? {
        return map.remove(key)
    }

    /**
     * Returns the value associated with the most recently added key
     * without removing it from the map.
     *
     * @return The value of the last inserted key, or null if the map is empty.
     */
    @Synchronized
    fun peek(): V? {
        val lastKey = map.keys.lastOrNull() ?: return null
        return map[lastKey]
    }

    /**
     * Returns the key associated with the most recently added value
     * without removing it from the map.
     *
     * @return The key of the last inserted value, or null if the map is empty.
     */
    @Synchronized
    fun peekKey(): K? {
        return map.keys.lastOrNull()
    }

    /**
     * Returns the value associated with the specified key.
     */
    @Synchronized
    fun get(key: K): V? {
        return map[key]
    }

    /**
     * Checks if the map contains the specified key.
     */
    @Synchronized
    fun containsKey(key: K): Boolean {
        return map.containsKey(key)
    }

    /**
     * Checks if the map is empty.
     *
     * @return True if the map contains no elements, false otherwise.
     */
    fun isEmpty(): Boolean = map.isEmpty()

    /**
     * Returns the current number of elements in the map.
     *
     * @return The number of key-value pairs in the map.
     */
    fun size(): Int = map.size

    /**
     * Removes all key-value pairs from the map.
     */
    fun clear() {
        map.clear()
    }

    /**
     * Returns a string representation of the map.
     *
     * @return A string representation of the map in insertion order.
     */
    override fun toString(): String = map.toString()

    companion object {
        /**
         * Creates a new LifoMap with no size limit.
         *
         * @return A new LifoMap instance.
         */
        fun <K, V> empty(): LifoMap<K, V> = LifoMap()
    }
}

fun <K, V> LifoMap<K, V>.getOrDefault(key: K, defaultValue: V): V {
    return get(key) ?: defaultValue
}
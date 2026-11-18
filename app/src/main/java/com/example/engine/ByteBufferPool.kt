package com.example.engine

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lock-free reusable ByteArray pool for Extreme Speed Engine TUN packet processing.
 * Minimizes heap allocations, GC pauses, and redundant buffer copies on the packet hot path.
 */
class ByteBufferPool(
    val bufferSize: Int,
    private val maxPoolSize: Int = 128
) {
    private val pool = ConcurrentLinkedQueue<ByteArray>()
    private val pooledCount = AtomicInteger(0)

    fun acquire(): ByteArray {
        val recycled = pool.poll()
        if (recycled != null) {
            pooledCount.decrementAndGet()
            return recycled
        }
        return ByteArray(bufferSize)
    }

    fun release(buffer: ByteArray) {
        if (buffer.size != bufferSize) return
        if (pooledCount.get() < maxPoolSize) {
            if (pool.offer(buffer)) {
                pooledCount.incrementAndGet()
            }
        }
    }

    fun clear() {
        pool.clear()
        pooledCount.set(0)
    }

    fun currentPooledCount(): Int = pooledCount.get()
}

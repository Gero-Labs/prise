package tech.edgx.prise.indexer.service

import com.bloxbean.cardano.yaci.core.model.TransactionOutput
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory cache for UTXOs from recently processed blocks.
 * Reduces dependency on external services (Blockfrost, Koios, etc.) by caching
 * outputs from blocks we've already seen.
 *
 * **Thread Safety**:
 * - Uses ConcurrentHashMap for cache storage (thread-safe reads)
 * - Uses ArrayDeque for LRU tracking (NOT thread-safe)
 * - All mutations (addOutputs, removeSpentUtxos, clear) are synchronized on class instance
 * - Read operations (getUtxo, getUtxos) are lock-free for better performance
 * - The insertionOrder ArrayDeque is only accessed within synchronized blocks
 *
 * **Usage**: This class is designed for single-writer (EventDispatcher), multiple-reader scenarios.
 * All write operations MUST go through the synchronized methods to prevent corruption.
 */
class UtxoCache(private val maxSize: Int = 100000) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Key: txHash#outputIndex, Value: TransactionOutput (thread-safe for reads)
    private val cache = ConcurrentHashMap<String, TransactionOutput>()

    // Track insertion order for LRU eviction (NOT thread-safe - protected by synchronized blocks)
    private val insertionOrder = ArrayDeque<String>()

    /**
     * Add outputs from a transaction to the cache.
     * Thread-safe: entire operation is synchronized to prevent race conditions.
     */
    fun addOutputs(txHash: String, outputs: List<TransactionOutput>) {
        synchronized(this) {
            outputs.forEachIndexed { index, output ->
                val key = "$txHash#$index"
                if (!cache.containsKey(key)) {
                    // Evict oldest if at capacity
                    while (cache.size >= maxSize && insertionOrder.isNotEmpty()) {
                        val oldest = insertionOrder.removeFirst()
                        cache.remove(oldest)
                    }
                    cache[key] = output
                    insertionOrder.addLast(key)
                }
            }
        }
    }

    /**
     * Get a specific UTXO from cache
     * @return TransactionOutput if found, null otherwise
     */
    fun getUtxo(txHash: String, outputIndex: Int): TransactionOutput? {
        val key = "$txHash#$outputIndex"
        return cache[key]
    }

    /**
     * Get multiple UTXOs from cache
     * @return Map of found UTXOs (key: txHash#index)
     */
    fun getUtxos(references: List<Pair<String, Int>>): Map<String, TransactionOutput> {
        return references.mapNotNull { (txHash, index) ->
            val key = "$txHash#$index"
            cache[key]?.let { key to it }
        }.toMap()
    }

    /**
     * Remove UTXOs that have been spent (consumed as inputs)
     */
    fun removeSpentUtxos(txHash: String, outputIndex: Int) {
        val key = "$txHash#$outputIndex"
        synchronized(this) {
            cache.remove(key)
            insertionOrder.remove(key)
        }
    }

    /**
     * Get cache statistics
     */
    fun getStats(): CacheStats {
        synchronized(this) {
            return CacheStats(
                size = cache.size,
                maxSize = maxSize,
                utilizationPercent = (cache.size.toDouble() / maxSize * 100).toInt()
            )
        }
    }

    /**
     * Clear the cache
     */
    fun clear() {
        synchronized(this) {
            cache.clear()
            insertionOrder.clear()
        }
        log.info("UTXO cache cleared")
    }

    data class CacheStats(
        val size: Int,
        val maxSize: Int,
        val utilizationPercent: Int
    )
}
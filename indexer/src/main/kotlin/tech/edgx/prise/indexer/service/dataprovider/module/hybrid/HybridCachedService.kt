package tech.edgx.prise.indexer.service.dataprovider.module.hybrid

import com.bloxbean.cardano.yaci.core.model.TransactionInput
import com.bloxbean.cardano.yaci.core.model.TransactionOutput
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.parameter.parametersOf
import org.koin.core.qualifier.named
import org.slf4j.LoggerFactory
import tech.edgx.prise.indexer.config.Config
import tech.edgx.prise.indexer.domain.BlockView
import tech.edgx.prise.indexer.service.UtxoCache
import tech.edgx.prise.indexer.service.dataprovider.ChainDatabaseService

/**
 * Hybrid chain database service that uses UTXO cache first, then falls back
 * to the configured external service (Blockfrost, Koios, etc.)
 */
class HybridCachedService(private val config: Config) : KoinComponent, ChainDatabaseService {
    private val log = LoggerFactory.getLogger(javaClass)

    // The fallback service (configured via FALLBACK_CHAIN_DATABASE_SERVICE_MODULE env var)
    private val fallbackService: ChainDatabaseService by inject(named(config.fallbackChainDatabaseServiceModule)) { parametersOf(config) }

    // UTXO cache
    private val utxoCache: UtxoCache by inject()

    private var cacheHits = 0L
    private var cacheMisses = 0L
    private var totalQueries = 0L

    override fun getInputUtxos(txIns: Set<TransactionInput>): List<TransactionOutput> {
        totalQueries++

        // Try to get from cache first
        val references = txIns.map { it.transactionId to it.index }
        val cachedUtxos = utxoCache.getUtxos(references)

        val foundInCache = mutableListOf<TransactionOutput>()
        val missingFromCache = mutableSetOf<TransactionInput>()

        txIns.forEach { txIn ->
            val key = "${txIn.transactionId}#${txIn.index}"
            val cached = cachedUtxos[key]
            if (cached != null) {
                foundInCache.add(cached)
                cacheHits++
            } else {
                missingFromCache.add(txIn)
                cacheMisses++
            }
        }

        // Log cache performance periodically
        if (totalQueries % 100 == 0L) {
            val hitRate = if (cacheHits + cacheMisses > 0) {
                (cacheHits.toDouble() / (cacheHits + cacheMisses) * 100).toInt()
            } else 0
            val stats = utxoCache.getStats()
            log.info("UTXO Cache Stats: hit_rate={}%, hits={}, misses={}, cache_size={}/{} ({}%)",
                hitRate, cacheHits, cacheMisses, stats.size, stats.maxSize, stats.utilizationPercent)
        }

        // If all found in cache, return immediately
        if (missingFromCache.isEmpty()) {
            log.debug("All {} UTXOs found in cache (100% hit rate)", txIns.size)
            return foundInCache
        }

        // Fetch missing UTXOs from fallback service
        log.debug("Fetching {} UTXOs from fallback service (cache hit: {}/{})",
            missingFromCache.size, foundInCache.size, txIns.size)
        val fetchedUtxos = if (missingFromCache.isNotEmpty()) {
            fallbackService.getInputUtxos(missingFromCache)
        } else {
            emptyList()
        }

        // Build a map of fetched UTXOs for quick lookup
        // Note: Fallback service returns in order matching missingFromCache input
        val fetchedMap = mutableMapOf<String, TransactionOutput>()
        missingFromCache.forEachIndexed { index, txIn ->
            if (index < fetchedUtxos.size) {
                fetchedMap["${txIn.transactionId}#${txIn.index}"] = fetchedUtxos[index]
            }
        }

        // Return combined results (maintain original order)
        return txIns.mapNotNull { txIn ->
            val key = "${txIn.transactionId}#${txIn.index}"
            cachedUtxos[key] ?: fetchedMap[key]
        }
    }

    override fun getBlockNearestToSlot(slot: Long): BlockView? {
        // No caching for block queries, delegate to fallback
        return fallbackService.getBlockNearestToSlot(slot)
    }
}
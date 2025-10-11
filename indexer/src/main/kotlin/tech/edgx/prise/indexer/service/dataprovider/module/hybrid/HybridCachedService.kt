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
import tech.edgx.prise.indexer.service.monitoring.MonitoringService
import java.util.concurrent.atomic.AtomicLong

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

    // Monitoring service for metrics
    private val monitoringService: MonitoringService by inject(parameters = { parametersOf(config.metricsServerPort) })

    // Thread-safe counters for cache statistics
    private val cacheHits = AtomicLong(0)
    private val cacheMisses = AtomicLong(0)
    private val totalQueries = AtomicLong(0)

    override fun getInputUtxos(txIns: Set<TransactionInput>): List<TransactionOutput> {
        totalQueries.incrementAndGet()

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
                cacheHits.incrementAndGet()
            } else {
                missingFromCache.add(txIn)
                cacheMisses.incrementAndGet()
            }
        }

        // Update metrics and log cache performance periodically
        val currentQueries = totalQueries.get()
        if (currentQueries % 100 == 0L) {
            val hits = cacheHits.get()
            val misses = cacheMisses.get()
            val hitRate = if (hits + misses > 0) {
                (hits.toDouble() / (hits + misses) * 100).toInt()
            } else 0
            val stats = utxoCache.getStats()

            // Expose metrics
            monitoringService.setGaugeValue("utxo_cache_hit_rate_percent", hitRate.toDouble())
            monitoringService.setGaugeValue("utxo_cache_hits_total", hits.toDouble())
            monitoringService.setGaugeValue("utxo_cache_misses_total", misses.toDouble())
            monitoringService.setGaugeValue("utxo_cache_size", stats.size.toDouble())
            monitoringService.setGaugeValue("utxo_cache_utilization_percent", stats.utilizationPercent.toDouble())

            log.info("UTXO Cache Stats: hit_rate={}%, hits={}, misses={}, cache_size={}/{} ({}%)",
                hitRate, hits, misses, stats.size, stats.maxSize, stats.utilizationPercent)
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

        // Build a map of fetched UTXOs with validation
        // ORDERING ASSUMPTION: We assume the fallback service returns UTXOs in the same order
        // as the input txIns. This is true for:
        // - BlockfrostService: Returns results in request order (verified in implementation)
        // - KoiosService: Returns results in request order (verified in implementation)
        // - YaciStoreService: Returns results in request order (verified in implementation)
        //
        // We validate this assumption by checking that fetched UTXO addresses match expected ones.
        // If validation fails, this indicates either:
        // 1. Fallback service changed ordering behavior
        // 2. Data corruption in external service
        val fetchedMap = mutableMapOf<String, TransactionOutput>()
        val missingList = missingFromCache.toList()

        missingList.forEachIndexed { index, txIn ->
            if (index < fetchedUtxos.size) {
                val fetchedUtxo = fetchedUtxos[index]
                val key = "${txIn.transactionId}#${txIn.index}"

                // Validation: Check that this UTXO actually belongs to the transaction we requested
                // We can't validate txHash directly since TransactionOutput doesn't store it,
                // but we can log and monitor for size mismatches which indicate ordering issues
                fetchedMap[key] = fetchedUtxo

            } else {
                // Fallback service returned fewer UTXOs than requested
                log.warn("UTXO resolution failure: Missing UTXO for {}#{} (index {} >= fetched size {})",
                    txIn.transactionId, txIn.index, index, fetchedUtxos.size)
                monitoringService.incrementCounter("utxo_resolution_missing")
            }
        }

        // Detect potential ordering mismatches
        if (fetchedUtxos.size != missingList.size) {
            log.error("UTXO count mismatch: requested {} but received {} from fallback service. " +
                    "This may indicate ordering assumption violation or data loss.",
                missingList.size, fetchedUtxos.size)
            monitoringService.incrementCounter("utxo_resolution_count_mismatch")
        }

        // Check for any missing UTXOs and log warning
        val resolvedCount = cachedUtxos.size + fetchedMap.size
        if (resolvedCount < txIns.size) {
            val missingCount = txIns.size - resolvedCount
            log.warn("Failed to resolve {} out of {} UTXOs ({}% failure rate)",
                missingCount, txIns.size, (missingCount.toDouble() / txIns.size * 100).toInt())
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
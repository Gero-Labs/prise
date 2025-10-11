package tech.edgx.prise.indexer.service

import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import org.ktorm.database.Database
import org.slf4j.LoggerFactory
import tech.edgx.prise.indexer.model.dex.PoolReserveDTO
import java.sql.SQLException

class PoolReserveService : KoinComponent {
    private val log = LoggerFactory.getLogger(javaClass)
    private val database: Database by inject(named("appDatabase"))
    private val assetService: AssetService by inject()
    private val txService: TxService by inject()
    private val batchSize = 500

    fun batchInsertOrUpdateCombined(poolReserves: List<PoolReserveDTO>): Int {
        if (poolReserves.isEmpty()) return 0

        var total = 0
        val units = poolReserves.flatMap { listOf(it.asset1Unit, it.asset2Unit) }.toSet()
        val assetsMap = assetService.getAssetsByUnits(units)
        val uniqueTxHashes = poolReserves.map { it.txHash }.toSet()
        val txHashToId = txService.batchInsertTxs(uniqueTxHashes.map { tech.edgx.prise.indexer.util.Helpers.hexToBinary(it) })

        database.useTransaction {
            poolReserves.chunked(batchSize).forEach { chunk ->
                try {
                    total += database.useConnection { conn ->
                        val sql = """
                            WITH pool_data AS (
                                SELECT
                                    unnest(?::text[]) AS pool_id,
                                    unnest(?::bigint[]) AS asset1_id,
                                    unnest(?::bigint[]) AS asset2_id,
                                    unnest(?::int[]) AS provider,
                                    unnest(?::bigint[]) AS time,
                                    unnest(?::numeric[]) AS reserve1,
                                    unnest(?::numeric[]) AS reserve2,
                                    unnest(?::bigint[]) AS tx_id
                            ),
                            insert_reserves AS (
                                INSERT INTO pool_reserve (
                                    pool_id, asset1_id, asset2_id, provider, time, reserve1, reserve2, tx_id
                                )
                                SELECT
                                    pool_id, asset1_id, asset2_id, provider, time, reserve1, reserve2, tx_id
                                FROM pool_data
                                ON CONFLICT (pool_id, time) DO UPDATE
                                SET
                                    asset1_id = EXCLUDED.asset1_id,
                                    asset2_id = EXCLUDED.asset2_id,
                                    provider = EXCLUDED.provider,
                                    reserve1 = EXCLUDED.reserve1,
                                    reserve2 = EXCLUDED.reserve2,
                                    tx_id = EXCLUDED.tx_id
                                RETURNING *
                            ),
                            latest_reserves AS (
                                SELECT DISTINCT ON (pool_id)
                                    pool_id, asset1_id, asset2_id, provider, time, reserve1, reserve2, tx_id
                                FROM pool_data
                                ORDER BY pool_id, time DESC
                            )
                            INSERT INTO latest_pool_reserve (
                                pool_id, asset1_id, asset2_id, provider, time, reserve1, reserve2, tx_id
                            )
                            SELECT
                                pool_id, asset1_id, asset2_id, provider, time, reserve1, reserve2, tx_id
                            FROM latest_reserves
                            ON CONFLICT (pool_id) DO UPDATE
                            SET
                                asset1_id = EXCLUDED.asset1_id,
                                asset2_id = EXCLUDED.asset2_id,
                                provider = EXCLUDED.provider,
                                time = EXCLUDED.time,
                                reserve1 = EXCLUDED.reserve1,
                                reserve2 = EXCLUDED.reserve2,
                                tx_id = EXCLUDED.tx_id
                            RETURNING *
                        """.trimIndent()

                        conn.prepareStatement(sql).use { stmt ->
                            val processedReserves = chunk.mapNotNull { pr ->
                                val asset1 = assetsMap[pr.asset1Unit]
                                val asset2 = assetsMap[pr.asset2Unit]
                                val txId = txHashToId[pr.txHash]

                                if (asset1 == null || asset2 == null || txId == null) {
                                    log.warn("Missing asset or txId for pool reserve: asset1=${pr.asset1Unit}, asset2=${pr.asset2Unit}, tx=${pr.txHash}")
                                    return@mapNotNull null
                                }

                                val poolId = "${pr.asset1Unit}:${pr.asset2Unit}:${pr.dex}"
                                val time = pr.slot - tech.edgx.prise.indexer.util.Helpers.slotConversionOffset

                                ProcessedPoolReserve(poolId, asset1.id, asset2.id, pr.dex, time, pr.reserve1, pr.reserve2, txId)
                            }

                            // Deduplicate by (pool_id, time), keeping the last occurrence
                            val deduplicatedReserves = processedReserves
                                .groupBy { it.poolId to it.time }
                                .map { it.value.last() }

                            if (deduplicatedReserves.isEmpty()) {
                                return@useConnection 0
                            }

                            // Prepare arrays for each field
                            stmt.setArray(1, conn.createArrayOf("TEXT", deduplicatedReserves.map { it.poolId }.toTypedArray()))
                            stmt.setArray(2, conn.createArrayOf("BIGINT", deduplicatedReserves.map { it.asset1Id }.toTypedArray()))
                            stmt.setArray(3, conn.createArrayOf("BIGINT", deduplicatedReserves.map { it.asset2Id }.toTypedArray()))
                            stmt.setArray(4, conn.createArrayOf("INTEGER", deduplicatedReserves.map { it.provider }.toTypedArray()))
                            stmt.setArray(5, conn.createArrayOf("BIGINT", deduplicatedReserves.map { it.time }.toTypedArray()))
                            stmt.setArray(6, conn.createArrayOf("NUMERIC", deduplicatedReserves.map { it.reserve1 }.toTypedArray()))
                            stmt.setArray(7, conn.createArrayOf("NUMERIC", deduplicatedReserves.map { it.reserve2 }.toTypedArray()))
                            stmt.setArray(8, conn.createArrayOf("BIGINT", deduplicatedReserves.map { it.txId }.toTypedArray()))

                            // Execute query and count affected rows
                            stmt.executeQuery().use { rs ->
                                var count = 0
                                while (rs.next()) {
                                    count++
                                }
                                count
                            }
                        }
                    }
                    log.debug("Batch upserted combined pool reserves: chunk size: {}", chunk.size)
                } catch (e: SQLException) {
                    log.error("Failed to process combined batch of pool reserves of size ${chunk.size}", e)
                    throw e
                }
            }
        }
        log.debug("Total combined pool reserves inserted/updated: {}", total)
        return total
    }

    private data class ProcessedPoolReserve(
        val poolId: String,
        val asset1Id: Long,
        val asset2Id: Long,
        val provider: Int,
        val time: Long,
        val reserve1: java.math.BigDecimal,
        val reserve2: java.math.BigDecimal,
        val txId: Long
    )
}
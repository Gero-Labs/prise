package tech.edgx.prise.webserver.service

import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.edgx.prise.webserver.domain.Asset
import tech.edgx.prise.webserver.model.tokens.AssetResponse
import tech.edgx.prise.webserver.model.tokens.TopByVolumeRequest
import tech.edgx.prise.webserver.model.tokens.TopGainersRequest
import tech.edgx.prise.webserver.model.tokens.GainerAssetResponse
import tech.edgx.prise.webserver.model.tvl.TopTvlRequest
import tech.edgx.prise.webserver.model.tvl.PoolTvlResponse
import tech.edgx.prise.webserver.util.DexEnum
import java.sql.SQLException

interface AssetService {
    fun getAssetIdForUnit(unit: String): Long?
    fun getDistinctAssets(): Set<String>
    fun getAllSupportedPairs(): Set<Pair<String, String>>
    fun getTopByVolume(topByVolumeRequest: TopByVolumeRequest): Set<AssetResponse>
    fun getTopGainers(topGainersRequest: TopGainersRequest): Set<GainerAssetResponse>
    fun getTopLosers(topGainersRequest: TopGainersRequest): Set<GainerAssetResponse>
    fun getTopTvl(topTvlRequest: TopTvlRequest): List<PoolTvlResponse>
}

@Service("assetService")
@Transactional
class AssetServiceImpl(
    private val jdbcClient: JdbcClient
) : AssetService {

    @Cacheable(cacheNames = ["asset_ids"], key = "#unit")
    @CacheEvict(cacheNames = ["asset_ids"], key = "#unit", condition = "#result == null")
    override fun getAssetIdForUnit(unit: String): Long? {
        return try {
            jdbcClient
                .sql("SELECT id FROM asset WHERE unit = :unit")
                .param("unit", unit)
                .query(Long::class.java)
                .optional()
                .orElse(null)
        } catch (e: SQLException) {
            log.warn("Asset not found: $unit", e)
            null
        } catch (e: Exception) {
            log.error("Error querying asset: $unit", e)
            null
        }
    }

    override fun getAllSupportedPairs(): Set<Pair<String, String>> {
        return try {
            jdbcClient
                .sql("""
                    WITH distinct_pairs AS
                        (SELECT DISTINCT asset_id, quote_asset_id
                        FROM latest_price)
                    SELECT a1.unit AS asset, a2.unit AS quote
                    FROM distinct_pairs dp
                    JOIN asset a1 ON a1.id = dp.asset_id
                    JOIN asset a2 ON a2.id = dp.quote_asset_id
                """.trimIndent())
                .query { rs, _ ->
                    val asset = if (rs.getString("asset") == "lovelace") "ADA" else rs.getString("asset")
                    val quote = if (rs.getString("quote") == "lovelace") "ADA" else rs.getString("quote")
                    asset to quote
                }
                .list()
                .toSet()
        } catch (e: SQLException) {
            log.error("Error querying supported pairs", e)
            emptySet()
        }
    }

    override fun getDistinctAssets(): Set<String> {
        return try {
            jdbcClient
                .sql("""
                        SELECT DISTINCT unit FROM asset where unit != 'lovelace'
                    """.trimIndent())
                .query { rs, _ -> rs.getString("unit") }
                .set()
        } catch (e: SQLException) {
            log.error("Error querying distinct symbols", e)
            emptySet()
        }
    }

    // Query Cache: GET topByVolume::100
    @Cacheable(cacheNames = ["topByVolume"], key = "#topByVolumeRequest.limit")
    override fun getTopByVolume(topByVolumeRequest: TopByVolumeRequest): Set<AssetResponse> {
        return try {
            jdbcClient
                .sql("""
                SELECT
                    a1.unit,
                    a1.native_name,
                    a1.preferred_name,
                    SUM(amount1)/1000000 AS total_volume
                FROM
                    price
                JOIN asset a1 on a1.id = price.asset_id
                WHERE
                    outlier IS NULL
                    AND time >= EXTRACT(EPOCH FROM NOW() - INTERVAL '1 days')::bigint
                    AND time < EXTRACT(EPOCH FROM NOW())::bigint
                GROUP BY
                    a1.unit,
                    a1.native_name,
                    a1.preferred_name
                ORDER BY
                    total_volume DESC
                LIMIT :limit;
            """.trimIndent())
                .param("limit", topByVolumeRequest.limit)
                .query(AssetResponse::class.java)
                .set()
        } catch (e: SQLException) {
            log.error("Error querying top by volume", e)
            emptySet()
        }
    }

    @Cacheable(cacheNames = ["topGainers"], key = "#topGainersRequest.limit + '_' + #topGainersRequest.hours")
    override fun getTopGainers(topGainersRequest: TopGainersRequest): Set<GainerAssetResponse> {
        return try {
            val hoursInSeconds = topGainersRequest.hours * 3600L

            jdbcClient
                .sql("""
                WITH price_stats AS (
                    SELECT
                        a.id,
                        a.unit,
                        a.native_name,
                        a.preferred_name,
                        lp.price as current_price,
                        (
                            SELECT p2.price
                            FROM price p2
                            WHERE p2.asset_id = a.id
                                AND p2.quote_asset_id = lp.quote_asset_id
                                AND p2.outlier IS NULL
                                AND p2.time <= EXTRACT(EPOCH FROM NOW())::bigint - :hoursInSeconds
                            ORDER BY p2.time DESC
                            LIMIT 1
                        ) as previous_price
                    FROM asset a
                    JOIN latest_price lp ON lp.asset_id = a.id
                    WHERE lp.quote_asset_id = (SELECT id FROM asset WHERE unit = 'lovelace')
                )
                SELECT
                    unit,
                    native_name,
                    preferred_name,
                    ((current_price - previous_price) / previous_price * 100) as price_change,
                    current_price,
                    previous_price
                FROM price_stats
                WHERE previous_price > 0 AND previous_price IS NOT NULL
                ORDER BY price_change DESC
                LIMIT :limit;
                """.trimIndent())
                .param("limit", topGainersRequest.limit)
                .param("hoursInSeconds", hoursInSeconds)
                .query { rs, _ ->
                    GainerAssetResponse(
                        unit = rs.getString("unit"),
                        nativeName = rs.getString("native_name"),
                        preferredName = rs.getString("preferred_name"),
                        priceChange = rs.getDouble("price_change"),
                        currentPrice = rs.getDouble("current_price"),
                        previousPrice = rs.getDouble("previous_price")
                    )
                }
                .set()
        } catch (e: SQLException) {
            log.error("Error querying top gainers", e)
            emptySet()
        }
    }

    @Cacheable(cacheNames = ["topLosers"], key = "#topGainersRequest.limit + '_' + #topGainersRequest.hours")
    override fun getTopLosers(topGainersRequest: TopGainersRequest): Set<GainerAssetResponse> {
        return try {
            val hoursInSeconds = topGainersRequest.hours * 3600L

            jdbcClient
                .sql("""
                WITH price_stats AS (
                    SELECT
                        a.id,
                        a.unit,
                        a.native_name,
                        a.preferred_name,
                        lp.price as current_price,
                        (
                            SELECT p2.price
                            FROM price p2
                            WHERE p2.asset_id = a.id
                                AND p2.quote_asset_id = lp.quote_asset_id
                                AND p2.outlier IS NULL
                                AND p2.time <= EXTRACT(EPOCH FROM NOW())::bigint - :hoursInSeconds
                            ORDER BY p2.time DESC
                            LIMIT 1
                        ) as previous_price
                    FROM asset a
                    JOIN latest_price lp ON lp.asset_id = a.id
                    WHERE lp.quote_asset_id = (SELECT id FROM asset WHERE unit = 'lovelace')
                )
                SELECT
                    unit,
                    native_name,
                    preferred_name,
                    ((current_price - previous_price) / previous_price * 100) as price_change,
                    current_price,
                    previous_price
                FROM price_stats
                WHERE previous_price > 0 AND previous_price IS NOT NULL
                ORDER BY price_change ASC
                LIMIT :limit;
                """.trimIndent())
                .param("limit", topGainersRequest.limit)
                .param("hoursInSeconds", hoursInSeconds)
                .query { rs, _ ->
                    GainerAssetResponse(
                        unit = rs.getString("unit"),
                        nativeName = rs.getString("native_name"),
                        preferredName = rs.getString("preferred_name"),
                        priceChange = rs.getDouble("price_change"),
                        currentPrice = rs.getDouble("current_price"),
                        previousPrice = rs.getDouble("previous_price")
                    )
                }
                .set()
        } catch (e: SQLException) {
            log.error("Error querying top losers", e)
            emptySet()
        }
    }

    @Cacheable(cacheNames = ["topTvl"], key = "#topTvlRequest.limit")
    override fun getTopTvl(topTvlRequest: TopTvlRequest): List<PoolTvlResponse> {
        return try {
            jdbcClient
                .sql("""
                WITH pool_tvl AS (
                    SELECT
                        lpr.pool_id,
                        a1.unit AS asset1_unit,
                        COALESCE(a1.preferred_name, a1.native_name) AS asset1_name,
                        a2.unit AS asset2_unit,
                        COALESCE(a2.preferred_name, a2.native_name) AS asset2_name,
                        lpr.provider,
                        lpr.reserve1,
                        lpr.reserve2,
                        CASE
                            WHEN a1.unit = 'lovelace' THEN lpr.reserve1 / 1000000.0
                            ELSE (lpr.reserve1 / POWER(10, COALESCE(a1.decimals, 0))) * COALESCE(p1.price, 0)
                        END AS reserve1_in_ada,
                        CASE
                            WHEN a2.unit = 'lovelace' THEN lpr.reserve2 / 1000000.0
                            ELSE (lpr.reserve2 / POWER(10, COALESCE(a2.decimals, 0))) * COALESCE(p2.price, 0)
                        END AS reserve2_in_ada
                    FROM latest_pool_reserve lpr
                    JOIN asset a1 ON a1.id = lpr.asset1_id
                    JOIN asset a2 ON a2.id = lpr.asset2_id
                    LEFT JOIN latest_price p1 ON p1.asset_id = a1.id
                        AND p1.quote_asset_id = (SELECT id FROM asset WHERE unit = 'lovelace')
                    LEFT JOIN latest_price p2 ON p2.asset_id = a2.id
                        AND p2.quote_asset_id = (SELECT id FROM asset WHERE unit = 'lovelace')
                )
                SELECT
                    pool_id,
                    asset1_unit,
                    asset1_name,
                    asset2_unit,
                    asset2_name,
                    provider,
                    reserve1,
                    reserve2,
                    (reserve1_in_ada + reserve2_in_ada) AS tvl_in_ada,
                    (reserve1_in_ada + reserve2_in_ada) * COALESCE(
                        (SELECT price FROM latest_price
                         WHERE asset_id = (SELECT id FROM asset WHERE unit = 'lovelace')
                           AND quote_asset_id IN (SELECT id FROM asset WHERE unit LIKE '%USDT' OR unit LIKE '%USDC')
                         ORDER BY time DESC
                         LIMIT 1),
                        NULL
                    ) AS tvl_in_usd
                FROM pool_tvl
                ORDER BY tvl_in_ada DESC
                LIMIT :limit;
                """.trimIndent())
                .param("limit", topTvlRequest.limit)
                .query { rs, _ ->
                    val providerCode = rs.getInt("provider")
                    val providerName = try {
                        DexEnum.fromId(providerCode).friendlyName
                    } catch (e: IllegalArgumentException) {
                        log.warn("Unknown DEX provider code: $providerCode")
                        "Unknown"
                    }
                    PoolTvlResponse(
                        poolId = rs.getString("pool_id"),
                        asset1Unit = rs.getString("asset1_unit"),
                        asset1Name = rs.getString("asset1_name"),
                        asset2Unit = rs.getString("asset2_unit"),
                        asset2Name = rs.getString("asset2_name"),
                        provider = providerName,
                        reserve1 = rs.getDouble("reserve1"),
                        reserve2 = rs.getDouble("reserve2"),
                        tvlInAda = rs.getDouble("tvl_in_ada"),
                        tvlInUsd = rs.getDouble("tvl_in_usd").takeIf { !rs.wasNull() }
                    )
                }
                .list()
        } catch (e: SQLException) {
            log.error("Error querying top TVL", e)
            emptyList()
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(AssetServiceImpl::class.java)
    }
}
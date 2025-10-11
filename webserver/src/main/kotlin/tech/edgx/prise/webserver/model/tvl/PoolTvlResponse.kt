package tech.edgx.prise.webserver.model.tvl

data class PoolTvlResponse(
    val poolId: String,
    val asset1Unit: String,
    val asset1Name: String?,
    val asset2Unit: String,
    val asset2Name: String?,
    val provider: String,
    val reserve1: Double,
    val reserve2: Double,
    val tvlInAda: Double,
    val tvlInUsd: Double?
)
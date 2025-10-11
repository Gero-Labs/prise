package tech.edgx.prise.indexer.model.dex

import java.math.BigDecimal

data class PoolReserveDTO(
    val txHash: String,
    val slot: Long,
    val dex: Int,
    val asset1Unit: String,
    val asset2Unit: String,
    val reserve1: BigDecimal,
    val reserve2: BigDecimal
)
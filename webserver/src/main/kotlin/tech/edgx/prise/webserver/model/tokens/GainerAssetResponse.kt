package tech.edgx.prise.webserver.model.tokens

data class GainerAssetResponse(
    val unit: String,
    val nativeName: String?,
    val preferredName: String?,
    val priceChange: Double,
    val currentPrice: Double,
    val previousPrice: Double
)
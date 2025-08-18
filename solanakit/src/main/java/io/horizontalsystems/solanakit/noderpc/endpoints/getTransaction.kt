package io.horizontalsystems.solanakit.noderpc.endpoints

import com.solana.api.Api
import io.horizontalsystems.solanakit.models.Transaction
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Types

fun Api.getTransaction(
    signature: String,
    commitment: String? = null,
    maxSupportedTransactionVersion: Int? = null,
    encoding: String = "jsonParsed",
    onComplete: (Result<TransactionInfo?>) -> Unit
) {
    val params = mutableListOf<Any>(signature)

    val cfg = mutableMapOf<String, Any>()
    commitment?.let { cfg["commitment"] = it }
    maxSupportedTransactionVersion?.let { cfg["maxSupportedTransactionVersion"] = it }
    cfg["encoding"] = encoding
    params.add(cfg)

    router.request(
        "getTransaction", params,
        TransactionInfo::class.java
    ) { result ->
        result.onSuccess {
            onComplete(Result.success(it))
        }.onFailure {
            onComplete(Result.failure(it))
        }
    }
}


@JsonClass(generateAdapter = true)
data class TransactionInfo(
    val blockTime: Long?,
    val meta: Any?,
    val slot: Long?,
    val transaction: Any?,
    val version: Any?
)


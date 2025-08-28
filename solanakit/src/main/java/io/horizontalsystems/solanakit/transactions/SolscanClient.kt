package io.horizontalsystems.solanakit.transactions

import android.util.Log
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine


class SolscanClient(
    auth: String,
    debug: Boolean
) {
    val syncSourceName = "solscan.io"
    val solSyncSourceName = "solscan.io/solTransfers"
    val splSyncSourceName = "solscan.io/splTransfers"
    private val url = "https://pro-api.solscan.io/v2.0"
    private val SOL_TOKEN_ADDRESS: String = "So11111111111111111111111111111111111111111"

    private val httpClient = httpClient(auth, debug)

    suspend fun solTransfers(account: String, lastSolTransferHash: String?): List<SolscanTransaction> {
        val transactionsLimit = if (lastSolTransferHash != null) 5 else maxTransactionsLimit
        val solscanTxs = mutableListOf<SolscanTransaction>()
        var solscanTxsChunk: List<SolscanTransaction>

        var page = 1
        do {
            solscanTxsChunk = solTransfersChunk(account, transactionsLimit, page)

            val index = lastSolTransferHash?.let { solscanTxsChunk.indexOfFirst { it.hash == lastSolTransferHash } }
            if (lastSolTransferHash != null && index != null && index >= 0) {
                solscanTxs.addAll(solscanTxsChunk.subList(0, index))
                break
            } else {
                solscanTxs.addAll(solscanTxsChunk)
            }
            page += 1
        } while (solscanTxsChunk.size == transactionsLimit && page < maxPagesSynced)

        return solscanTxs
    }

    suspend fun allTransfers(account: String, lastSolTransferHash: String?): List<SolscanTransaction> {
        val transactionsLimit = if (lastSolTransferHash != null) 10 else maxTransactionsLimit
        val allTxs = mutableListOf<SolscanTransaction>()
        var allTxsChunk: List<SolscanTransaction>

        var page = 1
        do {
            allTxsChunk = allTransfersChunk(account, transactionsLimit, page)

            val index = lastSolTransferHash?.let { allTxsChunk.indexOfFirst { it.hash == lastSolTransferHash } }
            if (lastSolTransferHash != null && index != null && index >= 0) {
                allTxs.addAll(allTxsChunk.subList(0, index))
                break
            } else {
                allTxs.addAll(allTxsChunk)
            }
            page += 1
        } while (allTxsChunk.size == transactionsLimit && page < maxPagesSynced)

        return allTxs
    }

    suspend fun splTransfers(account: String, lastSplTransferHash: String?): List<SolscanTransaction> {
        val transactionsLimit = if (lastSplTransferHash != null) 5 else maxTransactionsLimit
        val solscanTxs = mutableListOf<SolscanTransaction>()
        var solscanTxsChunk: List<SolscanTransaction>

        var page = 1
        do {
            solscanTxsChunk = splTransfersChunk(account, transactionsLimit, page)

            val index = lastSplTransferHash?.let { solscanTxsChunk.indexOfFirst { it.hash == lastSplTransferHash } }
            if (lastSplTransferHash != null && index != null && index >= 0) {
                solscanTxs.addAll(solscanTxsChunk.subList(0, index))
                break
            } else {
                solscanTxs.addAll(solscanTxsChunk)
            }
            page += 1
        } while (solscanTxsChunk.size == transactionsLimit && page < maxPagesSynced)

        return solscanTxs
    }

    private suspend fun allTransfersChunk(account: String, limit: Int, page: Int): List<SolscanTransaction> {
        val path = "/account/transfer?address=$account&page=$page&page_size=$limit"
        val request: Request = Request.Builder().url(url + path).build()
        val balanceRequestPath = "/account/balance_change?address=$account&page=$page&page_size=$limit"
        val balanceRequest: Request = Request.Builder().url(url + balanceRequestPath).build()

        return suspendCoroutine { continuation ->
            try {
                val responseBody: ResponseBody = httpClient.newCall(request).execute().body
                val getBalanceInfoBody: ResponseBody = httpClient.newCall(balanceRequest).execute().body
                val resultObject = JSONObject(responseBody.string())
                val balanceResultObject = JSONObject(getBalanceInfoBody.string())
                val txObjects: JSONArray = resultObject.getJSONArray("data")
                val balanceTxObjects: JSONArray = balanceResultObject.getJSONArray("data")

                val transactions = mutableListOf<SolscanTransaction>()
                for (i in 0 until txObjects.length()) {
                    val txObject: JSONObject = txObjects.getJSONObject(i)
                    val transId = txObject.getString("trans_id")
                    val balanceObject: JSONObject? = (0 until balanceTxObjects.length())
                        .map { balanceTxObjects.getJSONObject(it) }
                        .firstOrNull { it.getString("trans_id") == transId }
                    var solAmount: Long? = null
                    if (txObject.getString("token_address") == SOL_TOKEN_ADDRESS) {
                        solAmount = txObject.getLong("amount")
                    }
                    transactions.add(
                        // SPL transfer
                        SolscanTransaction(
                            hash = txObject.getString("trans_id"),
                            blockTime = txObject.getLong("block_time"),
                            solTransferSource = txObject.getString("from_address"),
                            solTransferDestination = txObject.getString("to_address"),
                            fee = BigDecimal(balanceObject!!.getLong("fee")).movePointLeft(9).toString(),
                            tokenAccountAddress = txObject.getString("to_token_account"),
                            mintAccountAddress = txObject.getString("token_address"),
                            splBalanceChange = (balanceObject.getInt("post_balance") - balanceObject.getInt("pre_balance")).toString(),
                            solAmount = solAmount,
                        )
                    )
                }
                continuation.resume(transactions)
            } catch (e: IOException) {
                continuation.resumeWithException(RuntimeException(e))
            }
        }
    }

    private suspend fun solTransfersChunk(account: String, limit: Int, page: Int): List<SolscanTransaction> {
        val path = "/account/transfer?address=$account&page=$page&page_size=$limit&token=$SOL_TOKEN_ADDRESS"
        val request: Request = Request.Builder().url(url + path).build()

        val balanceRequestPath = "/account/balance_change?address=$account&page=$page&page_size=$limit"
        val balanceRequest: Request = Request.Builder().url(url + balanceRequestPath).build()

        return suspendCoroutine { continuation ->
            try {
                val responseBody: ResponseBody = httpClient.newCall(request).execute().body
                val resultObject = JSONObject(responseBody.string())
                val txObjects: JSONArray = resultObject.getJSONArray("data")

                val getBalanceInfoBody: ResponseBody = httpClient.newCall(balanceRequest).execute().body
                val balanceResultObject = JSONObject(getBalanceInfoBody.string())
                val balanceTxObjects: JSONArray = balanceResultObject.getJSONArray("data")

                val transactions = mutableListOf<SolscanTransaction>()
                for (i in 0 until txObjects.length()) {
                    val txObject: JSONObject = txObjects.getJSONObject(i)
                    val transId = txObject.getString("trans_id")
                    val balanceObject: JSONObject? = (0 until balanceTxObjects.length())
                        .map { balanceTxObjects.getJSONObject(it) }
                        .firstOrNull { it.getString("trans_id") == transId }
                    transactions.add(
                        SolscanTransaction(
                            hash = txObject.getString("trans_id"),
                            blockTime = txObject.getLong("block_time"),
                            fee = BigDecimal(balanceObject!!.getLong("fee")).movePointLeft(9).toString(),
                            solTransferSource = txObject.getString("from_address"),
                            solTransferDestination = txObject.getString("to_address"),
                            solAmount = txObject.getLong("amount")
                        )
                    )
                }

                continuation.resume(transactions)
            } catch (e: IOException) {
                continuation.resumeWithException(RuntimeException(e))
            }
        }
    }

    private suspend fun splTransfersChunk(account: String, limit: Int, page: Int): List<SolscanTransaction> {
        val path = "/account/transfer?address=$account&page=$page&page_size=$limit"
        val request: Request = Request.Builder().url(url + path).build()
        val balanceRequestPath = "/account/balance_change?address=$account&page=$page&page_size=$limit"
        val balanceRequest: Request = Request.Builder().url(url + balanceRequestPath).build()

        return suspendCoroutine { continuation ->
            try {
                val responseBody: ResponseBody = httpClient.newCall(request).execute().body
                val getBalanceInfoBody: ResponseBody = httpClient.newCall(balanceRequest).execute().body
                val resultObject = JSONObject(responseBody.string())
                val balanceResultObject = JSONObject(getBalanceInfoBody.string())
                val txObjects: JSONArray = resultObject.getJSONArray("data")
                val balanceTxObjects: JSONArray = balanceResultObject.getJSONArray("data")

                val transactions = mutableListOf<SolscanTransaction>()
                for (i in 0 until txObjects.length()) {
                    val txObject: JSONObject = txObjects.getJSONObject(i)
                    if (txObject.getString("token_address") == SOL_TOKEN_ADDRESS) {
                        continue
                    }
                    val transId = txObject.getString("trans_id")
                    val balanceObject: JSONObject? = (0 until balanceTxObjects.length())
                        .map { balanceTxObjects.getJSONObject(it) }
                        .firstOrNull { it.getString("trans_id") == transId }
                    transactions.add(
                        // SPL transfer
                        SolscanTransaction(
                            hash = txObject.getString("trans_id"),
                            blockTime = txObject.getLong("block_time"),
                            fee = BigDecimal(balanceObject!!.getLong("fee")).movePointLeft(9).toString(),
                            tokenAccountAddress = txObject.getString("to_token_account"),
                            mintAccountAddress = txObject.getString("token_address"),
                            splBalanceChange = (balanceObject.getInt("post_balance") - balanceObject.getInt("pre_balance")).toString()
                        )
                    )
                }
                continuation.resume(transactions)
            } catch (e: IOException) {
                continuation.resumeWithException(RuntimeException(e))
            }
        }
    }

    private fun httpClient(apiKey: String, debug: Boolean): OkHttpClient {
        require(apiKey.isNotBlank()) { "Api Key can not be empty" }

        val headersInterceptor = Interceptor { chain ->
            val requestBuilder = chain.request().newBuilder()
            requestBuilder.header("token", apiKey)
            chain.proceed(requestBuilder.build())
        }

        val client = OkHttpClient.Builder()
        client.addInterceptor(headersInterceptor)

        if (debug) {
            val logging = HttpLoggingInterceptor()
            logging.level = HttpLoggingInterceptor.Level.BODY
            client.addInterceptor(logging)
        }

        return client.build()
    }

//    This method uses `exportTransactions` endpoint of solscan.io API, which is not working for unknown reasons.
//
//    fun transactions(account: String, fromTime: Long?, onComplete: (Result<List<SolscanExportedTransaction>>) -> Unit) {
//        val path = "/account/exportTransactions?account=${account}&type=all&fromTime=${fromTime ?: 0}&toTime=10000000000"
//
//        val request: Request = Request.Builder().url(url + path).build()
//        httpClient.newCall(request).enqueue(object : Callback {
//            override fun onFailure(call: Call, e: IOException) {
//                onComplete(Result.failure(RuntimeException(e)))
//            }
//
//            override fun onResponse(call: Call, response: Response) {
//                response.body?.let { body ->
//                    val result = readCsv(body.byteStream())
//                    onComplete(Result.success(result))
//                } ?: run {
//                    onComplete(Result.failure(NetworkingError.invalidResponseNoData))
//                }
//
//            }
//        })
//    }
//
//    private fun readCsv(inputStream: InputStream): List<SolscanTransaction> {
//        val reader = inputStream.bufferedReader()
//        reader.readLine()
//
//        return reader.lineSequence()
//            .filter { it.isNotBlank() }
//            .map {
//                val fields = it.split(',', ignoreCase = false, limit = 16)
//                SolscanTransaction(
//                    toNotOptionalString(fields[0]),
//                    toNotOptionalString(fields[1]),
//                    toNotOptionalString(fields[2]).toLong(),
//                    toNotOptionalString(fields[4]),
//                    toOptionalString(fields[5]),
//                    toOptionalString(fields[6]),
//                    toOptionalString(fields[7]),
//                    toOptionalString(fields[8]),
//                    toOptionalString(fields[9]),
//                    toOptionalString(fields[10]),
//                    toNotOptionalString(fields[11]),
//                    toNotOptionalString(fields[12]),
//                    toOptionalString(fields[13]),
//                    toOptionalString(fields[14]),
//                    toOptionalString(fields[15])
//                )
//            }.toList()
//    }
//
//    private fun toNotOptionalString(value: String) = value.trim().removeSurrounding("\"")
//    private fun toOptionalString(value: String): String? = value.trim().removeSurrounding("\"").ifEmpty { null }

    companion object {
        const val maxTransactionsLimit = 60
        const val maxPagesSynced = 20
    }

}

data class SolscanTransaction(
    val hash: String,
    val blockTime: Long,
    val fee: String? = null,
    val tokenAccountAddress: String? = null,
    val splBalanceChange: String? = null,
    val mintAccountAddress: String? = null,
    val solTransferSource: String? = null,
    val solTransferDestination: String? = null,
    val solAmount: Long? = null
)

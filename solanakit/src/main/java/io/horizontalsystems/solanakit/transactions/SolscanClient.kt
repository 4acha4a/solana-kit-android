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


const val SOL_TOKEN_ADDRESS: String = "So11111111111111111111111111111111111111111"

class SolscanClient(
    auth: String,
    debug: Boolean
) {
    val solSyncSourceName = "solscan.io/solTransfers"
    val syncSourceName = "solscan.io/allTransfers"
    val splSyncSourceName = "solscan.io/splTransfers"
    private val url = "https://pro-api.solscan.io/v2.0"

    private val httpClient = httpClient(auth, debug)

//    suspend fun solTransfers(account: String, lastSolTransferHash: String?, lastBlockTime: Long): List<SolscanTransaction> {
//        val transactionsLimit = if (lastSolTransferHash != null) 10 else maxTransactionsLimit
//        val solscanTxs = mutableListOf<SolscanTransaction>()
//        var solscanTxsChunk: List<SolscanTransaction>
//
//        var page = 1
//        do {
//            solscanTxsChunk = solTransfersChunk(account, transactionsLimit, page, lastBlockTime)
//
//            // // Log.d("SolscanClient", "Fetched ${solscanTxsChunk.size} SOL transfer transactions on page $page")
//
//            val index = lastSolTransferHash?.let { solscanTxsChunk.indexOfFirst { it.hash == lastSolTransferHash } }
//
//            // // Log.d("SolscanClient", "Index of last known transaction ($lastSolTransferHash): $index")
//
//            if (lastSolTransferHash != null && index != null && index >= 0) {
//                // // Log.d("SolscanClient", "Found last known transaction. Stopping fetch.")
//                solscanTxs.addAll(solscanTxsChunk.subList(0, index))
//                break
//            } else {
//                // // Log.d("SolscanClient", "Last known transaction not found in this chunk. Continuing fetch.")
//                solscanTxs.addAll(solscanTxsChunk)
//            }
//            page += 1
//        } while (solscanTxsChunk.size == transactionsLimit && page < maxPagesSynced)
//
//        // // Log.d("SolscanClient", "Total SOL transfer transactions fetched: ${solscanTxs.size}")
//
//        return solscanTxs
//    }

    suspend fun allTransfers(account: String, lastTransactionHash: String?, lastBlockTime: Long): List<SolscanTransaction> {
        val transactionsLimit = maxTransactionsLimit
        val solscanTxs = mutableListOf<SolscanTransaction>()
        var solscanTxsChunk: List<SolscanTransaction>

        var page = 1
        do {
            solscanTxsChunk = allTransfersChunk(account, transactionsLimit, page, lastBlockTime)

            // // Log.d("SolscanClient", "Fetched ${solscanTxsChunk.size} SPL transfer transactions on page $page")

            val index = lastTransactionHash?.let { solscanTxsChunk.indexOfFirst { it.hash == lastTransactionHash } }

            // // Log.d("SolscanClient", "Index of last known transaction ($lastSplTransferHash): $index")
            if (lastTransactionHash != null && index != null && index >= 0) {
                // // Log.d("SolscanClient", "Found last known transaction. Stopping fetch.")
                solscanTxs.addAll(solscanTxsChunk.subList(0, index))
                break
            } else {
                // // Log.d("SolscanClient", "Last known transaction not found in this chunk. Continuing fetch.")
                solscanTxs.addAll(solscanTxsChunk)
            }
            page += 1
        } while (solscanTxsChunk.size == transactionsLimit && page < maxPagesSynced)

        return solscanTxs
    }

//
//    suspend fun splTransfers(account: String, lastSplTransferHash: String?, lastBlockTime: Long): List<SolscanTransaction> {
//        val transactionsLimit = if (lastSplTransferHash != null) 10 else maxTransactionsLimit
//        val solscanTxs = mutableListOf<SolscanTransaction>()
//        var solscanTxsChunk: List<SolscanTransaction>
//
//        var page = 1
//        do {
//            solscanTxsChunk = splTransfersChunk(account, transactionsLimit, page, lastBlockTime)
//
//            // // Log.d("SolscanClient", "Fetched ${solscanTxsChunk.size} SPL transfer transactions on page $page")
//
//            val index = lastSplTransferHash?.let { solscanTxsChunk.indexOfFirst { it.hash == lastSplTransferHash } }
//
//            // // Log.d("SolscanClient", "Index of last known transaction ($lastSplTransferHash): $index")
//            if (lastSplTransferHash != null && index != null && index >= 0) {
//                // // Log.d("SolscanClient", "Found last known transaction. Stopping fetch.")
//                solscanTxs.addAll(solscanTxsChunk.subList(0, index))
//                break
//            } else {
//                // // Log.d("SolscanClient", "Last known transaction not found in this chunk. Continuing fetch.")
//                solscanTxs.addAll(solscanTxsChunk)
//            }
//            page += 1
//        } while (solscanTxsChunk.size == transactionsLimit && page < maxPagesSynced)
//
//        return solscanTxs
//    }

    suspend fun allBalanceChangeActivities(account: String, lastTransferHash: String?, lastBlockTime: Long): List<BalanceChangeActivity> {
        val transactionsLimit = maxTransactionsLimit
        val balanceChangeActivities = mutableListOf<BalanceChangeActivity>()
        var balanceChangeActivitiesChunk: List<BalanceChangeActivity>

        var page = 1
        do {
            balanceChangeActivitiesChunk = allBalanceChangeActivitiesChunk(account, transactionsLimit, page, lastBlockTime)

            // Log.d("SolscanClient", "Fetched ${balanceChangeActivitiesChunk.size} balance change activities on page $page")

            val index = lastTransferHash?.let { balanceChangeActivitiesChunk.indexOfFirst { it.transId == lastTransferHash } }

            // Log.d("SolscanClient", "Index of last known balance change activity ($lastTransferHash): $index")

            if (lastTransferHash != null && index != null && index >= 0) {
                balanceChangeActivities.addAll(balanceChangeActivitiesChunk.subList(0, index))
                // Log.d("SolscanClient", "Found last known balance change activity. Stopping fetch.")
                break
            } else {
                balanceChangeActivities.addAll(balanceChangeActivitiesChunk)
                // Log.d("SolscanClient", "Last known balance change activity not found in this chunk. Continuing fetch.")
            }
            page += 1
        } while (balanceChangeActivitiesChunk.size == transactionsLimit && page < maxPagesSynced)

        // Log.d("SolscanClient", "Total balance change activities fetched: ${balanceChangeActivities.size}")

        return balanceChangeActivities
    }

    private suspend fun allBalanceChangeActivitiesChunk(account: String, limit: Int, page: Int, lastBlockTime: Long): List<BalanceChangeActivity> {
        val balanceRequestPath = "/account/balance_change?address=$account&page=$page&page_size=$limit&from_time=$lastBlockTime"
        val balanceRequest: Request = Request.Builder().url(url + balanceRequestPath).build()

        return suspendCoroutine { continuation ->
            try {
                val getBalanceInfoBody: ResponseBody = httpClient.newCall(balanceRequest).execute().body
                val balanceResultObject = JSONObject(getBalanceInfoBody.string())
                val balanceTxObjects: JSONArray = balanceResultObject.getJSONArray("data")

                val balanceChangeActivities = mutableListOf<BalanceChangeActivity>()

                // Log.d("SolscanClient", "Fetched ${balanceTxObjects.length()} balance change records")

                for (i in 0 until balanceTxObjects.length()) {
                    val balanceObject: JSONObject = balanceTxObjects.getJSONObject(i)
                    val transId = balanceObject.getString("trans_id")
                    val tokenAddress = balanceObject.getString("token_address")
                    val balanceChange = (balanceObject.getInt("post_balance") - balanceObject.getInt("pre_balance")).toString()
                    val fee = BigDecimal(balanceObject.getLong("fee")).movePointLeft(9).toString()
                    val blockTime = balanceObject.getLong("block_time")

                    balanceChangeActivities.add(
                        BalanceChangeActivity(
                            transId = transId,
                            blockTime = blockTime,
                            balanceChange = balanceChange,
                            fee = fee,
                            tokenAddress = tokenAddress
                        )
                    )
                    // Log.d("SolscanClient", "Added balance change activity: ${balanceChangeActivities.last()}")
                }
                continuation.resume(balanceChangeActivities)

            } catch (e: IOException) {
                continuation.resumeWithException(RuntimeException(e))
            }
        }
    }

    private suspend fun allTransfersChunk(account: String, limit: Int, page: Int, lastBlockTime: Long): List<SolscanTransaction> {
        val path = "/account/transfer?address=$account&activity_type[]=ACTIVITY_SPL_TRANSFER&page=$page&page_size=$limit&from_time=$lastBlockTime"
        val request: Request = Request.Builder().url(url + path).build()

        // // Log.d("SolscanClient", "Fetching SOL transfers: $path")

//        val balanceRequestPath = "/account/balance_change?address=$account&page=$page&page_size=$limit"
//        val balanceRequest: Request = Request.Builder().url(url + balanceRequestPath).build()

        // // Log.d("SolscanClient", "Fetching balance changes: $balanceRequestPath")

        return suspendCoroutine { continuation ->
            try {
                val responseBody: ResponseBody = httpClient.newCall(request).execute().body
                val resultObject = JSONObject(responseBody.string())
                val txObjects: JSONArray = resultObject.getJSONArray("data")
//
//                val getBalanceInfoBody: ResponseBody = httpClient.newCall(balanceRequest).execute().body
//                val balanceResultObject = JSONObject(getBalanceInfoBody.string())
//                val balanceTxObjects: JSONArray = balanceResultObject.getJSONArray("data")

                // // Log.d("SolscanClient", "Fetched ${txObjects.length()} SOL transfer records and ${balanceTxObjects.length()} balance change records")

                val transactions = mutableListOf<SolscanTransaction>()
                for (i in 0 until txObjects.length()) {
                    val txObject: JSONObject = txObjects.getJSONObject(i)
                    // Log.d("SolscanClient", "Processing transaction: $txObject")
                    val transId = txObject.getString("trans_id")
                    val tokenAddress = txObject.getString("token_address")

                    when (tokenAddress) {
                        SOL_TOKEN_ADDRESS -> {
                            transactions.add(
                                SolscanTransaction(
                                    hash = txObject.getString("trans_id"),
                                    blockTime = txObject.getLong("block_time"),
                                    //fee = BigDecimal(balanceObject!!.getLong("fee")).movePointLeft(9).toString(),
                                    solTransferSource = txObject.getString("from_address"),
                                    solTransferDestination = txObject.getString("to_address"),
                                    solAmount = txObject.getLong("amount")
                                )
                            )
                        }
                        else -> {
//                            val key = if (balanceChange > 0) {
//                                "to_token_account"
//                            } else {
//                                "from_token_account"
//                            }
                            // SPL transfer
                            transactions.add(
                                SolscanTransaction(
                                    hash = txObject.getString("trans_id"),
                                    blockTime = txObject.getLong("block_time"),
                                    //fee = BigDecimal(balanceObject.getLong("fee")).movePointLeft(9).toString(),
                                    solTransferSource = txObject.getString("from_address"),
                                    solTransferDestination = txObject.getString("to_address"),
                                    //tokenAccountAddress = txObject.getString(key),
                                    mintAccountAddress = txObject.getString("token_address"),
                                    //splBalanceChange = balanceChange.toString()
                                )
                            )
                        }
                    }
                    // // Log.d("SolscanClient", "Added SOL transfer transaction: ${transactions.last()}")
                }

                continuation.resume(transactions)
            } catch (e: IOException) {
                continuation.resumeWithException(RuntimeException(e))
            }
        }
    }


//    private suspend fun solTransfersChunk(account: String, limit: Int, page: Int, lastBlockTime: Long): List<SolscanTransaction> {
//        val path = "/account/transfer?address=$account&activity_type[]=ACTIVITY_SPL_TRANSFER&page=$page&page_size=$limit&token=$SOL_TOKEN_ADDRESS&from_time=$lastBlockTime"
//        val request: Request = Request.Builder().url(url + path).build()
//
//        // // Log.d("SolscanClient", "Fetching SOL transfers: $path")
//
//        val balanceRequestPath = "/account/balance_change?address=$account&page=$page&page_size=$limit&from_time=$lastBlockTime"
//        val balanceRequest: Request = Request.Builder().url(url + balanceRequestPath).build()
//
//        // // Log.d("SolscanClient", "Fetching balance changes: $balanceRequestPath")
//
//        return suspendCoroutine { continuation ->
//            try {
//                val responseBody: ResponseBody = httpClient.newCall(request).execute().body
//                val resultObject = JSONObject(responseBody.string())
//                val txObjects: JSONArray = resultObject.getJSONArray("data")
//
//                val getBalanceInfoBody: ResponseBody = httpClient.newCall(balanceRequest).execute().body
//                val balanceResultObject = JSONObject(getBalanceInfoBody.string())
//                val balanceTxObjects: JSONArray = balanceResultObject.getJSONArray("data")
//
//                // // Log.d("SolscanClient", "Fetched ${txObjects.length()} SOL transfer records and ${balanceTxObjects.length()} balance change records")
//
//                val transactions = mutableListOf<SolscanTransaction>()
//                for (i in 0 until txObjects.length()) {
//                    val txObject: JSONObject = txObjects.getJSONObject(i)
//                    val transId = txObject.getString("trans_id")
//                    val tokenAddress = txObject.getString("token_address")
//                    transactions.add(
//                        // SOL transfer
//                        SolscanTransaction(
//                            hash = txObject.getString("trans_id"),
//                            blockTime = txObject.getLong("block_time"),
//                            //fee = BigDecimal(balanceObject!!.getLong("fee")).movePointLeft(9).toString(),
//                            solTransferSource = txObject.getString("from_address"),
//                            solTransferDestination = txObject.getString("to_address"),
//                            solAmount = txObject.getLong("amount")
//                        )
//                    )
//                    // // Log.d("SolscanClient", "Added SOL transfer transaction: ${transactions.last()}")
//                }
//
//                continuation.resume(transactions)
//            } catch (e: IOException) {
//                continuation.resumeWithException(RuntimeException(e))
//            }
//        }
//    }
//
//    private suspend fun splTransfersChunk(account: String, limit: Int, page: Int, lastBlockTime: Long): List<SolscanTransaction> {
//        val path = "/account/transfer?address=$account&activity_type[]=ACTIVITY_SPL_TRANSFER&page=$page&page_size=$limit&from_time=$lastBlockTime"
//        val request: Request = Request.Builder().url(url + path).build()
//        val balanceRequestPath = "/account/balance_change?address=$account&page=$page&page_size=$limit&from_time=$lastBlockTime"
//        val balanceRequest: Request = Request.Builder().url(url + balanceRequestPath).build()
//
//        return suspendCoroutine { continuation ->
//            try {
//                val responseBody: ResponseBody = httpClient.newCall(request).execute().body
//                val getBalanceInfoBody: ResponseBody = httpClient.newCall(balanceRequest).execute().body
//                val resultObject = JSONObject(responseBody.string())
//                val balanceResultObject = JSONObject(getBalanceInfoBody.string())
//
//                val txObjects: JSONArray = resultObject.getJSONArray("data")
//                val balanceTxObjects: JSONArray = balanceResultObject.getJSONArray("data")
//
//                // // Log.d("SolscanClient", "Fetched ${txObjects.length()} SPL transfer records and ${balanceTxObjects.length()} balance change records")
//
//                val transactions = mutableListOf<SolscanTransaction>()
//                for (i in 0 until txObjects.length()) {
//                    val txObject: JSONObject = txObjects.getJSONObject(i)
//                    if (txObject.getString("token_address") == SOL_TOKEN_ADDRESS) {
//                        continue
//                    }
//                    // // Log.d("SolscanClient", "Processing transaction: $txObject")
//                    val transId = txObject.getString("trans_id")
//                    val tokenAddress = txObject.getString("token_address")
//
//                    val balanceChange = balanceObject!!.getInt("post_balance") - balanceObject.getInt("pre_balance")
//                    val key = if (balanceChange > 0) {
//                        "to_token_account"
//                    } else {
//                        "from_token_account"
//                    }
//                    // // Log.d("SolscanClient", "Using key '$key' for token account address")
//                    transactions.add(
//                        // SPL transfer
//                        SolscanTransaction(
//                            hash = txObject.getString("trans_id"),
//                            blockTime = txObject.getLong("block_time"),
//                            fee = BigDecimal(balanceObject.getLong("fee")).movePointLeft(9).toString(),
//                            solTransferSource = txObject.getString("from_address"),
//                            solTransferDestination = txObject.getString("to_address"),
//                            tokenAccountAddress = txObject.getString(key),
//                            mintAccountAddress = txObject.getString("token_address"),
//                            splBalanceChange = balanceChange.toString()
//                        )
//                    )
//                    // // Log.d("SolscanClient", "Added SPL transfer transaction: ${transactions.last()}")
//                }
//                // // Log.d("SolscanClient", "Total SPL transfer transactions parsed: ${transactions.size}")
//                continuation.resume(transactions)
//            } catch (e: IOException) {
//                continuation.resumeWithException(RuntimeException(e))
//            }
//        }
//    }

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
        const val maxTransactionsLimit = 100
        const val maxPagesSynced = 20
    }

}

data class BalanceChangeActivity(
    val transId: String,
    val tokenAddress: String,
    val blockTime: Long,
    val balanceChange: String,
    val fee: String
)

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

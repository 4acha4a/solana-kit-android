package io.horizontalsystems.solanakit.transactions

import android.util.Log
import com.metaplex.lib.programs.token_metadata.TokenMetadataProgram
import com.metaplex.lib.programs.token_metadata.accounts.MetadataAccount
import com.metaplex.lib.programs.token_metadata.accounts.MetaplexTokenStandard.FungibleAsset
import com.metaplex.lib.programs.token_metadata.accounts.MetaplexTokenStandard.NonFungible
import com.metaplex.lib.programs.token_metadata.accounts.MetaplexTokenStandard.NonFungibleEdition
import com.solana.api.Api
import com.solana.api.getMultipleAccounts
import com.solana.core.PublicKey
import com.solana.models.buffer.BufferInfo
import com.solana.models.buffer.Mint
import com.solana.programs.TokenProgram
import io.horizontalsystems.solanakit.SolanaKit
import io.horizontalsystems.solanakit.database.transaction.TransactionStorage
import io.horizontalsystems.solanakit.models.FullTokenTransfer
import io.horizontalsystems.solanakit.models.FullTransaction
import io.horizontalsystems.solanakit.models.LastSyncedTransaction
import io.horizontalsystems.solanakit.models.MintAccount
import io.horizontalsystems.solanakit.models.TokenAccount
import io.horizontalsystems.solanakit.models.TokenTransfer
import io.horizontalsystems.solanakit.models.Transaction
import io.horizontalsystems.solanakit.noderpc.NftClient
import io.horizontalsystems.solanakit.noderpc.endpoints.SignatureInfo
import io.horizontalsystems.solanakit.noderpc.endpoints.TransactionInfo
import io.horizontalsystems.solanakit.noderpc.endpoints.getSignaturesForAddress
import io.horizontalsystems.solanakit.noderpc.endpoints.getTransaction
import java.math.BigDecimal
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.random.Random

private const val INTER_REQUEST_DELAY_MS = 150L      // ~6–10 req/s
private const val MAX_ATTEMPTS_429 = 6
private const val BASE_DELAY_429_MS = 300L
private const val MAX_DELAY_429_MS = 10_000L

private fun is429(t: Throwable): Boolean =
    t.message?.contains("code=429", ignoreCase = true) == true ||
            t.toString().contains("code=429", ignoreCase = true)

private suspend fun <T> retry429(block: suspend () -> T): T {
    var attempt = 0
    var delayMs = BASE_DELAY_429_MS
    while (true) {
        try {
            return block()
        } catch (t: Throwable) {
            if (!is429(t) || ++attempt >= MAX_ATTEMPTS_429) throw t
            val jitter = Random.nextLong(0, delayMs / 2 + 1)
            delay(delayMs + jitter)
            delayMs = min(delayMs * 2, MAX_DELAY_429_MS)
        }
    }
}

interface ITransactionListener {
    fun onUpdateTransactionSyncState(syncState: SolanaKit.SyncState)
}

class TransactionSyncer(
    private val publicKey: PublicKey,
    private val rpcClient: Api,
    private val solscanClient: SolscanClient,
    private val nftClient: NftClient,
    private val storage: TransactionStorage,
    private val transactionManager: TransactionManager,
    private val pendingTransactionSyncer: PendingTransactionSyncer
) {
    var syncState: SolanaKit.SyncState = SolanaKit.SyncState.NotSynced(SolanaKit.SyncError.NotStarted())
        private set(value) {
            if (value != field) {
                field = value
                listener?.onUpdateTransactionSyncState(value)
            }
        }

    var listener: ITransactionListener? = null

    suspend fun sync() {
        if (syncState is SolanaKit.SyncState.Syncing) return

        syncState = SolanaKit.SyncState.Syncing()

        pendingTransactionSyncer.sync()

        syncState = SolanaKit.SyncState.Synced()

        val lastTransactionHash = storage.lastNonPendingTransaction()?.hash

        try {
            val rpcSignatureInfos = getSignaturesFromRpcNode(lastTransactionHash)
            Log.d("solana-kit", "rpcSignatureInfos: $rpcSignatureInfos")
            val transactionInfos = getTransactionsFromRpcSignatures(rpcSignatureInfos)
            Log.d("solana-kit", "transactionInfos: $transactionInfos")
            //val solTransfers = solscanClient.solTransfers(publicKey.toBase58(), storage.getSyncedBlockTime(solscanClient.solSyncSourceName)?.hash)
            val solTransfers: List<SolscanTransaction> = listOf()
            Log.d("solana-kit", "solTransfers: $solTransfers")
            //val splTransfers = solscanClient.splTransfers(publicKey.toBase58(), storage.getSyncedBlockTime(solscanClient.splSyncSourceName)?.hash)
            val splTransfers: List<SolscanTransaction> = listOf()
            Log.d("solana-kit", "splTransfers: $splTransfers")
            val solscanExportedTxs = (solTransfers + splTransfers).sortedByDescending { it.blockTime }
            Log.d("solana-kit", "solscanExportedTxs: $solscanExportedTxs")
            val mintAddresses = solscanExportedTxs.mapNotNull { it.mintAccountAddress }.toSet().toList()
            Log.d("solana-kit", "mintAddresses: $mintAddresses")
            val mintAccounts = getMintAccounts(mintAddresses)
            Log.d("solana-kit", "mintAccounts: $mintAccounts")
            val tokenAccounts = buildTokenAccounts(solscanExportedTxs, mintAccounts)
            Log.d("solana-kit", "tokenAccounts: $tokenAccounts")
            val transactions = merge(transactionInfos, solscanExportedTxs, mintAccounts)
            Log.d("solana-kit", "transactions: $transactions")

            transactionManager.handle(transactions, tokenAccounts)

            if (solTransfers.isNotEmpty()) {
                storage.setSyncedBlockTime(LastSyncedTransaction(solscanClient.solSyncSourceName, solTransfers.first().hash))
            }

            if (splTransfers.isNotEmpty()) {
                storage.setSyncedBlockTime(LastSyncedTransaction(solscanClient.splSyncSourceName, splTransfers.first().hash))
            }

            syncState = SolanaKit.SyncState.Synced()
        } catch (exception: Throwable) {
            Log.e("solana-kit", "TransactionSyncer: sync failed: ${exception.message}")
            syncState = SolanaKit.SyncState.NotSynced(exception)
        }
    }

    private suspend fun getTransactionsFromRpcSignatures(rpcSignatureInfos: List<SignatureInfo>): List<TransactionInfo> {
        val transactionObjects = mutableListOf<TransactionInfo>()

        for (info in rpcSignatureInfos) {
            if (info.err != null) continue

            val tx = try {
                retry429 { getTransactionChunk(info.signature) }
            } catch (t: Throwable) {
                if (!is429(t)) {
                    Log.w("solana-kit-tx", "Non-429 error for ${info.signature}: $t")
                }
                // Swallow on 429 (already retried) or any other error -> skip this sig
                null
            }

            if (tx != null) {
                Log.d("solana-kit-tx", "Transaction object: $tx")
                transactionObjects.add(tx)
            }

            // Fixed spacing between RPC calls to avoid throttling
            delay(INTER_REQUEST_DELAY_MS)
        }

        Log.d("solana-kit", "Total transactions fetched: ${transactionObjects.size}")

        return transactionObjects
    }

    private suspend fun getTransactionChunk(signature: String) = suspendCoroutine { continuation ->
        rpcClient.getTransaction(signature) { result ->
            result.onSuccess { transactionObject ->
                Log.d("solana-kit", "Transaction: $transactionObject")
                continuation.resume(transactionObject)
            }

            result.onFailure { exception ->
                Log.e("solana-kit", "Error fetching transaction", exception)
                continuation.resumeWithException(exception)
            }
        }
    }

    private fun merge(transactionInfos: List<TransactionInfo>,
                      solscanTxsMap: List<SolscanTransaction>,
                      mintAccounts: Map<String, MintAccount>): List<FullTransaction> {
        val transactions = mutableMapOf<String, FullTransaction>()

        Log.d("solana-kit", "transactionInfos: $transactionInfos")

        for (transactionInfo in transactionInfos) {
            val transactionData = transactionInfo.transaction ?: continue
            val transaction = mapTransactionInfoToEntity(transactionInfo)
            transactions[transaction.hash] = FullTransaction(transaction, listOf())
        }

        for ((hash, solscanTxs) in solscanTxsMap.groupBy { it.hash }) {
            try {
                val existingTransaction = transactions[hash]?.transaction
                val solscanTx = solscanTxs.first()
                val mergedTransaction = Transaction(
                    hash,
                    existingTransaction?.timestamp ?: solscanTx.blockTime,
                    solscanTx.fee?.toBigDecimalOrNull(),
                    solscanTx.solTransferSource,
                    solscanTx.solTransferDestination,
                    solscanTx.solAmount?.toBigDecimal(),
                    existingTransaction?.error
                )

                val tokenTransfers: List<FullTokenTransfer> = solscanTxs.mapNotNull { solscanTx ->
                    val mintAddress = solscanTx.mintAccountAddress ?: return@mapNotNull null
                    val mintAccount = mintAccounts[mintAddress] ?: return@mapNotNull null
                    val amount = solscanTx.splBalanceChange?.toBigDecimal() ?: return@mapNotNull null

                    FullTokenTransfer(
                        TokenTransfer(hash, mintAddress, amount > BigDecimal.ZERO, amount),
                        mintAccount
                    )
                }

                transactions[hash] = FullTransaction(mergedTransaction, tokenTransfers)
            } catch (e: Throwable) {
                continue
            }
        }

        return transactions.values.toList()
    }

    private suspend fun getSignaturesFromRpcNode(lastTransactionHash: String?): List<SignatureInfo> {
        val signatureObjects = mutableListOf<SignatureInfo>()
        var signatureObjectsChunk = listOf<SignatureInfo>()

        do {
            val lastSignature = signatureObjectsChunk.lastOrNull()?.signature
            signatureObjectsChunk = getSignaturesChunk(lastTransactionHash, lastSignature)
            signatureObjects.addAll(signatureObjectsChunk)

        } while (signatureObjectsChunk.size == rpcSignaturesCount)

        Log.d("solana-kit", "Total signatures fetched: ${signatureObjects.size}")

        return signatureObjects
    }

    private suspend fun getSignaturesChunk(lastTransactionHash: String?, before: String? = null) = suspendCoroutine<List<SignatureInfo>> { continuation ->
        rpcClient.getSignaturesForAddress(publicKey, until = lastTransactionHash, before = before, limit = rpcSignaturesCount) { result ->
            result.onSuccess { signatureObjects ->
                Log.d("solana-kit", "Signatures: $signatureObjects")
                continuation.resume(signatureObjects)
            }

            result.onFailure { exception ->
                Log.e("solana-kit", "Error fetching signatures", exception)
                continuation.resumeWithException(exception)
            }
        }
    }

    private suspend fun getMintAccounts(mintAddresses: List<String>) : Map<String, MintAccount> {
        if (mintAddresses.isEmpty()) {
            return mutableMapOf()
        }

        val publicKeys = mintAddresses.map { PublicKey.valueOf(it) }

        val mintAccountData = suspendCoroutine<List<BufferInfo<Mint>?>> { continuation ->
            rpcClient.getMultipleAccounts(publicKeys, Mint::class.java) { result ->
                result.onSuccess {
                    continuation.resume(it)
                }

                result.onFailure { exception ->
                    continuation.resumeWithException(exception)
                }
            }
        }

        val metadataAccountsMap = mutableMapOf<String, MetadataAccount>()
        nftClient.findAllByMintList(publicKeys).getOrThrow()
            .filterNotNull()
            .filter { it.owner == tokenMetadataProgramId }
            .forEach {
                val metadata = it.data?.value ?: return@forEach
                metadataAccountsMap[metadata.mint.toBase58()] = metadata
            }

        val mintAccounts = mutableMapOf<String, MintAccount>()

        for ((index, mintAddress) in mintAddresses.withIndex()) {
            val account = mintAccountData[index] ?: continue
            val owner = account.owner
            val mint = account.data?.value

            if (owner != tokenProgramId || mint == null) continue

            val metadataAccount = metadataAccountsMap[mintAddress]

            val isNft = when {
                mint.decimals != 0 -> false
                mint.supply == 1L && mint.mintAuthority == null -> true
                metadataAccount?.tokenStandard == NonFungible -> true
                metadataAccount?.tokenStandard == FungibleAsset -> true
                metadataAccount?.tokenStandard == NonFungibleEdition -> true
                else -> false
            }

            val collectionAddress = metadataAccount?.collection?.let {
                if (!it.verified) return@let null
                it.key.toBase58()
            }

            val mintAccount = MintAccount(
                mintAddress, mint.decimals, mint.supply,
                isNft,
                metadataAccount?.data?.name,
                metadataAccount?.data?.symbol,
                metadataAccount?.data?.uri,
                collectionAddress
            )

            mintAccounts[mintAddress] = mintAccount
        }

        return mintAccounts
    }

    private fun buildTokenAccounts(solscanExportedTxs: List<SolscanTransaction>, mintAccounts: Map<String, MintAccount>): List<TokenAccount> =
        solscanExportedTxs.mapNotNull { solscanTx ->
            val mintAccount = solscanTx.mintAccountAddress?.let { mintAccounts[it] } ?: return@mapNotNull null
            val tokenAccountAddress = solscanTx.tokenAccountAddress ?: return@mapNotNull null

            TokenAccount(tokenAccountAddress, mintAccount.address, BigDecimal.ZERO, mintAccount.decimals)
        }.toSet().toMutableList()

    companion object {
        val tokenProgramId = TokenProgram.PROGRAM_ID.toBase58()
        val tokenMetadataProgramId = TokenMetadataProgram.publicKey.toBase58()
        const val rpcSignaturesCount = 1000
    }

}

private fun Any?.asMap(): Map<String, Any?>? = this as? Map<String, Any?>
private fun Any?.asList(): List<Any?>? = this as? List<Any?>
private fun Any?.asString(): String? = this as? String
private fun numToLong(v: Any?): Long? = when (v) {
    is Number -> v.toLong()
    is String -> v.toLongOrNull()
    else -> null
}

private fun lamportsToSol(lamports: Long?): BigDecimal? =
    lamports?.let { BigDecimal(it).movePointLeft(9) } // 1 SOL = 1e9 lamports

fun mapTransactionInfoToEntity(info: TransactionInfo): Transaction {
    val meta = info.meta.asMap()
    val tx = info.transaction.asMap()
    val message = tx?.get("message").asMap()
    val instructions = message?.get("instructions").asList().orEmpty()

    val transferInstrParsedInfo: Map<String, Any?>? = instructions
        .firstOrNull { it.asMap()?.get("program") == "system" }
        ?.asMap()
        ?.get("parsed").asMap()
        ?.get("info").asMap()

    val from = transferInstrParsedInfo?.get("source").asString()
    val to = transferInstrParsedInfo?.get("destination").asString()
    val amountLamports = numToLong(transferInstrParsedInfo?.get("lamports"))

    val signatures = tx?.get("signatures").asList()
    val hash = signatures?.firstOrNull()?.asString().orEmpty()

    val feeLamports = numToLong(meta?.get("fee"))
    val errStr = meta?.get("err")?.toString()

    val recentBlockhash = message?.get("recentBlockhash").asString().orEmpty()

    return Transaction(
        hash = hash,
        timestamp = info.blockTime ?: 0L,
        fee = lamportsToSol(feeLamports),
        from = from,
        to = to,
        amount = BigDecimal(amountLamports!!),
        error = if (errStr == "null") null else errStr,
        pending = false,
        blockHash = recentBlockhash,
        lastValidBlockHeight = 0L,
        base64Encoded = "",
        retryCount = 0
    )
}


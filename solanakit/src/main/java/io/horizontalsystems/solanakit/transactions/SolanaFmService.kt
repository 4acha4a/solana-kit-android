package io.horizontalsystems.solanakit.transactions

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import io.horizontalsystems.solanakit.models.TokenAccount
import io.horizontalsystems.solanakit.models.TokenInfo
import io.reactivex.Single
import kotlinx.coroutines.rx2.await
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.math.BigDecimal
import java.util.logging.Logger

class SolanaFmService(auth: String) {

    private val baseUrl = "https://pro-api.solscan.io/v2.0/"
    private val logger = Logger.getLogger("SolanaFmService")

    private val api: SolanaFmApi
    private val gson: Gson

    init {
        val loggingInterceptor = HttpLoggingInterceptor { message -> logger.info(message) }
            .setLevel(HttpLoggingInterceptor.Level.BASIC)

        val httpClient = httpClient(auth).newBuilder()
            .addInterceptor(loggingInterceptor)

        gson = GsonBuilder()
            .setLenient()
            .create()

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
            .addConverterFactory(ScalarsConverterFactory.create())
            .addConverterFactory(GsonConverterFactory.create(gson))
            .client(httpClient.build())
            .build()

        api = retrofit.create(SolanaFmApi::class.java)
    }

    private inline fun <T> ApiResponse<T>.requireOk(): T {
        check(success) { "Solscan response.success = false" }
        return data
    }


    suspend fun tokenAccounts(address: String): List<TokenAccount> {
        val data = api.legacyTokenAccounts(address).await().requireOk()
        return data.map { row ->
            TokenAccount(
                address = row.ata,
                mintAddress = row.mint,
                balance = row.amount.movePointRight(row.tokenDecimals),
                decimals = row.tokenDecimals
            )
        }
    }

    suspend fun tokenInfo(mintAddress: String): TokenInfo {
        val m = api.tokenInfo(mintAddress).await().requireOk()
        return TokenInfo(
            name = m.name,
            symbol = m.symbol,
            decimals = m.decimals
        )
    }

    private fun httpClient(apiKey: String, debug: Boolean = false): OkHttpClient {
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

    private interface SolanaFmApi {
        // GET /v2.0/account/token-accounts?address=...&type=token&page=1&page_size=40&hide_zero=true
        @GET("account/token-accounts")
        fun legacyTokenAccounts(
            @Query("address") address: String,
            @Query("type") type: String = "token",
            @Query("page") page: Int = 1,
            @Query("page_size") pageSize: Int = 40,
            @Query("hide_zero") hideZero: Boolean = true
        ): Single<ApiResponse<List<TokenAccountRow>>>

        // GET /v2.0/token/meta?address=<mint>
        @GET("token/meta")
        fun tokenInfo(
            @Query("address") mintAddress: String
        ): Single<ApiResponse<TokenMeta>>
    }

    data class ApiResponse<T>(
        val success: Boolean,
        val data: T
    )

    data class TokenAccountRow(
        @SerializedName("token_account") val ata: String,
        @SerializedName("token_address") val mint: String,
        val amount: BigDecimal,
        @SerializedName("token_decimals") val tokenDecimals: Int,
        val owner: String
    )

    data class TokenMeta(
        val address: String,
        val name: String,
        val symbol: String,
        val decimals: Int
    )
}

package com.blockeq.stellarwallet.remote

import android.os.AsyncTask
import android.os.Handler
import android.os.Looper
import com.blockeq.stellarwallet.WalletApplication
import com.blockeq.stellarwallet.helpers.Constants
import com.blockeq.stellarwallet.interfaces.OnLoadAccount
import com.blockeq.stellarwallet.interfaces.SuccessErrorCallback
import com.blockeq.stellarwallet.models.AssetUtil
import com.blockeq.stellarwallet.models.DataAsset
import com.blockeq.stellarwallet.models.HorizonException
import com.blockeq.stellarwallet.mvvm.effects.remote.OnLoadEffects
import org.stellar.sdk.*
import org.stellar.sdk.Transaction.Builder.TIMEOUT_INFINITE
import org.stellar.sdk.requests.*
import org.stellar.sdk.responses.AccountResponse
import org.stellar.sdk.responses.OfferResponse
import org.stellar.sdk.responses.OrderBookResponse
import org.stellar.sdk.responses.Page
import org.stellar.sdk.responses.effects.EffectResponse
import shadow.okhttp3.OkHttpClient
import timber.log.Timber
import java.util.concurrent.TimeUnit
import com.blockeq.stellarwallet.remote.HorizonTasks.ServerType

object Horizon : HorizonTasks {
    private lateinit var SERVER : String

    override fun init(server: ServerType) {
       when(server) {
           ServerType.PROD -> {
               SERVER = "https://horizon.stellar.org"
               Network.usePublicNetwork()
           }
           ServerType.TEST_NET -> {
               SERVER = "https://horizon-testnet.stellar.org"
               Network.useTestNetwork()
           }
       }
    }

    override fun getLoadEffectsTask(cursor: String, limit: Int, listener: OnLoadEffects): AsyncTask<Void, Void, ArrayList<EffectResponse>?> {
        checkNotNull(SERVER)
        return LoadEffectsTask(cursor, limit, listener)
    }

    override fun getSendTask(listener: SuccessErrorCallback, destAddress: String, secretSeed: CharArray, memo: String, amount: String): AsyncTask<Void, Void, HorizonException> {
        checkNotNull(SERVER)
        return SendTask(listener, destAddress, secretSeed, memo, amount)
    }

    override fun getJoinInflationDestination(listener: SuccessErrorCallback, secretSeed: CharArray, inflationDest: String): AsyncTask<Void, Void, HorizonException> {
        checkNotNull(SERVER)
        return JoinInflationDestination(listener, secretSeed, inflationDest)
    }

    override fun getChangeTrust(listener: SuccessErrorCallback, asset: Asset, removeTrust: Boolean, secretSeed: CharArray): AsyncTask<Void, Void, HorizonException?> {
        checkNotNull(SERVER)
        return ChangeTrust(listener, asset, removeTrust, secretSeed)
    }

    override fun getLoadAccountTask(listener: OnLoadAccount): AsyncTask<Void, Void, AccountResponse> {
        checkNotNull(SERVER)
        return LoadAccountTask(listener)
    }

    override fun deleteOffer(id:Long, secretSeed : CharArray, selling: Asset, buying: Asset, price: String, listener: Horizon.OnMarketOfferListener) {
        checkNotNull(SERVER)
        AsyncTask.execute {
            val server = getServer()
            val offerOperation = ManageOfferOperation.Builder(selling, buying, "0", price).setOfferId(id).build()
            val sourceKeyPair = KeyPair.fromSecretSeed(secretSeed)

            try {
                val sourceAccount = server.accounts().account(sourceKeyPair)

                val transaction = Transaction.Builder(sourceAccount).setTimeout(TIMEOUT_INFINITE).addOperation(offerOperation).build()
                transaction.sign(sourceKeyPair)
                val response = server.submitTransaction(transaction)

                Handler(Looper.getMainLooper()).post {
                    if (response.isSuccess) {
                        listener.onExecuted()
                    } else {
                        listener.onFailed(response.extras.resultCodes.operationsResultCodes[0].toString())
                    }
                }
            } catch (error : java.lang.Exception) {
                if (error.message != null) {
                    listener.onFailed(error.message as String)
                } else {
                    listener.onFailed("Unknown error")
                }
            }
        }
    }

    override fun registerForEffects(cursor: String, listener: EventListener<EffectResponse>) : SSEStream<EffectResponse>? {
        checkNotNull(SERVER)
        val server = getServer()
        val sourceKeyPair = KeyPair.fromAccountId(WalletApplication.wallet.getStellarAccountId())
        try {
            //ATTENTION STREAM must work with order.ASC!
            return server.effects()
                    .cursor(cursor)
                    .order(RequestBuilder.Order.ASC)
                    .forAccount(sourceKeyPair).stream(listener)
        } catch (error : Exception) {
            Timber.e(error.message.toString())
        }
        return null
    }

    override fun getCreateMarketOffer(listener: OnMarketOfferListener, secretSeed: CharArray, sellingAsset: Asset, buyingAsset: Asset, amount: String, price: String) {
        checkNotNull(SERVER)
        AsyncTask.execute {
            val server = getServer()
            val managedOfferOperation = ManageOfferOperation.Builder(sellingAsset, buyingAsset, amount, price).build()
            val sourceKeyPair = KeyPair.fromSecretSeed(secretSeed)
            val sourceAccount = server.accounts().account(sourceKeyPair)

            val transaction = Transaction.Builder(sourceAccount).setTimeout(TIMEOUT_INFINITE).addOperation(managedOfferOperation).build()
            transaction.sign(sourceKeyPair)
            val response = server.submitTransaction(transaction)
            Handler(Looper.getMainLooper()).post {
                if (response.isSuccess) {
                    listener.onExecuted()
                } else {
                    val list = response.extras.resultCodes.operationsResultCodes
                    if (list != null && !list.isEmpty()) {
                        listener.onFailed(list[0].toString())
                    }
                }
            }
        }
    }

    override fun getOrderBook(listener: OnOrderBookListener, buyingAsset: DataAsset, sellingAsset: DataAsset) {
        checkNotNull(SERVER)
        AsyncTask.execute {
            val server = getServer()
            val buying : Asset = AssetUtil.toAssetFrom(buyingAsset)
            val selling : Asset = AssetUtil.toAssetFrom(sellingAsset)

            val response = server.orderBook().buyingAsset(buying).sellingAsset(selling).execute()

            Handler(Looper.getMainLooper()).post {
                listener.onOrderBook(response.asks, response.bids)
            }
        }
    }

    override fun getOffers(listener: OnOffersListener) {
        checkNotNull(SERVER)
        AsyncTask.execute {
            val server = getServer()
            try {
                val sourceKeyPair = KeyPair.fromAccountId(WalletApplication.wallet.getStellarAccountId())
                val response = server.offers().forAccount(sourceKeyPair).execute()
                Handler(Looper.getMainLooper()).post {
                    listener.onOffers(response.records)
                }
            } catch (error : ErrorResponse ) {
                Handler(Looper.getMainLooper()).post {
                    listener.onFailed(error.message!!)
                }
            }
        }
    }


    private class LoadAccountTask(private val listener: OnLoadAccount) : AsyncTask<Void, Void, AccountResponse>() {
        override fun doInBackground(vararg params: Void?) : AccountResponse? {
            val server = getServer()
            val sourceKeyPair = KeyPair.fromAccountId(WalletApplication.wallet.getStellarAccountId())
            var account : AccountResponse? = null
            try {
                account = server.accounts().account(sourceKeyPair)

            } catch (error : Exception) {
                Timber.d(error.message.toString())
                if (error is ErrorResponse) {
                    listener.onError(error)
                } else {
                    listener.onError(ErrorResponse(Constants.UNKNOWN_ERROR, error.message))
                }
            }

            return account
        }

        override fun onPostExecute(result: AccountResponse?) {
            listener.onLoadAccount(result)
        }
    }


    private class LoadEffectsTask(val cursor : String, val limit:Int, private val listener: OnLoadEffects) : AsyncTask<Void, Void, ArrayList<EffectResponse>?>() {
        override fun doInBackground(vararg params: Void?): ArrayList<EffectResponse>? {
            val server = getServer()
            val sourceKeyPair = KeyPair.fromAccountId(WalletApplication.wallet.getStellarAccountId())
            var effectResults : Page<EffectResponse>? = null
            try {
                effectResults = server.effects().order(RequestBuilder.Order.DESC)
                        .cursor(cursor)
                        .limit(limit)
                        .forAccount(sourceKeyPair).execute()
            } catch (error : Exception) {
                Timber.e(error.message.toString())
            }

            return effectResults?.records
        }

        override fun onPostExecute(result: ArrayList<EffectResponse>?) {
            listener.onLoadEffects(result)
        }

    }

    private class SendTask(private val listener: SuccessErrorCallback, private val destAddress: String,
                           private val secretSeed: CharArray, private val memo: String,
                           private val amount : String) : AsyncTask<Void, Void, HorizonException>() {

        override fun doInBackground(vararg params: Void?): HorizonException? {
            val server = getServer()
            val sourceKeyPair = KeyPair.fromSecretSeed(secretSeed)
            val destKeyPair = KeyPair.fromAccountId(destAddress)
            var isCreateAccount = false

            try {
                try {
                    server.accounts().account(destKeyPair)
                } catch (error : Exception) {
                    Timber.e(error.message.toString())
                    if (error is ErrorResponse && error.code == 404) {
                        isCreateAccount = true
                    } else {
                        return HorizonException(Constants.DEFAULT_TRANSACTION_FAILED_CODE,
                                arrayListOf(error.message),
                                HorizonException.HorizonExceptionType.SEND)
                    }
                }

                val sourceAccount = server.accounts().account(sourceKeyPair)

                val transactionBuilder = Transaction.Builder(sourceAccount).setTimeout(TIMEOUT_INFINITE)
                if (isCreateAccount) {
                    transactionBuilder.addOperation(CreateAccountOperation.Builder(destKeyPair, amount).build())
                } else {
                    transactionBuilder.addOperation(PaymentOperation.Builder(destKeyPair, getCurrentAsset(), amount).build())
                }

                if (memo.isNotEmpty()) {
                    transactionBuilder.addMemo(Memo.text(memo))
                }

                val transaction = transactionBuilder.build()
                transaction.sign(sourceKeyPair)

                val response = server.submitTransaction(transaction)
                if (!response.isSuccess) {
                    return HorizonException(response.extras.resultCodes.transactionResultCode,
                            response.extras.resultCodes.operationsResultCodes,
                            HorizonException.HorizonExceptionType.SEND)
                }
            } catch (error : Exception) {
                Timber.d(error.message.toString())
                return HorizonException(Constants.DEFAULT_TRANSACTION_FAILED_CODE,
                        arrayListOf(error.message.toString()),
                        HorizonException.HorizonExceptionType.SEND)
            }
            return null
        }

        override fun onPostExecute(result: HorizonException?) {
            if (result != null) {
                listener.onError(result)
            } else {
                listener.onSuccess()
            }
        }
    }

    private class JoinInflationDestination(private val listener: SuccessErrorCallback,
                                           private val secretSeed: CharArray,
                                           private val inflationDest : String)
        : AsyncTask<Void, Void, HorizonException>() {

        override fun doInBackground(vararg params: Void?): HorizonException? {

            val server = getServer()
            val sourceKeyPair = KeyPair.fromSecretSeed(secretSeed)
            val destKeyPair = KeyPair.fromAccountId(inflationDest)

            try {
                val sourceAccount = server.accounts().account(sourceKeyPair)

                val transaction = Transaction.Builder(sourceAccount).setTimeout(TIMEOUT_INFINITE)
                        .addOperation(SetOptionsOperation.Builder()
                                .setInflationDestination(destKeyPair)
                                .build())
                        .build()

                transaction.sign(sourceKeyPair)
                val response = server.submitTransaction(transaction)

                if (!response.isSuccess) {
                    return HorizonException(response.extras.resultCodes.transactionResultCode,
                            response.extras.resultCodes.operationsResultCodes,
                            HorizonException.HorizonExceptionType.INFLATION)
                }

            } catch (error : Exception) {
                Timber.e(error.message.toString())
                return HorizonException(Constants.DEFAULT_TRANSACTION_FAILED_CODE,
                        arrayListOf(error.message.toString()),
                        HorizonException.HorizonExceptionType.INFLATION)
            }
            return null
        }

        override fun onPostExecute(result: HorizonException?) {
            if (result != null) {
                listener.onError(result)
            } else {
                listener.onSuccess()
            }
        }
    }

    private class ChangeTrust(private val listener: SuccessErrorCallback, private val asset: Asset,
                              private val removeTrust: Boolean, private val secretSeed: CharArray)
        : AsyncTask<Void, Void, HorizonException?>() {

        override fun doInBackground(vararg params: Void?): HorizonException? {

            val server = getServer()
            val sourceKeyPair = KeyPair.fromSecretSeed(secretSeed)
            val limit = if (removeTrust) "0.0000000" else Constants.MAX_ASSET_STRING_VALUE

            try {
                val sourceAccount = server.accounts().account(sourceKeyPair)

                val transaction = Transaction.Builder(sourceAccount).setTimeout(TIMEOUT_INFINITE)
                        .addOperation(ChangeTrustOperation.Builder(asset, limit).build())
                        .build()

                transaction.sign(sourceKeyPair)
                val response = server.submitTransaction(transaction)

                if (!response.isSuccess) {
                    return HorizonException(response.extras.resultCodes.transactionResultCode,
                            response.extras.resultCodes.operationsResultCodes,
                            HorizonException.HorizonExceptionType.CHANGE_TRUST_LINE)
                }

            } catch (error : ErrorResponse) {
                Timber.e(error.body.toString())
                return HorizonException(Constants.DEFAULT_TRANSACTION_FAILED_CODE,
                        arrayListOf(error.body.toString()),
                        HorizonException.HorizonExceptionType.CHANGE_TRUST_LINE)
            }
            return null
        }

        override fun onPostExecute(result: HorizonException?) {
            if (result != null) {
                listener.onError(result)
            } else {
                listener.onSuccess()
            }
        }
    }

    interface OnMarketOfferListener {
      fun onExecuted()
      fun onFailed(errorMessage: String)
    }

    interface OnOrderBookListener {
        fun onOrderBook(asks : Array<OrderBookResponse.Row>, bids : Array<OrderBookResponse.Row>)
        fun onFailed(errorMessage: String)
    }

    interface OnOffersListener {
        fun onOffers(offers : ArrayList<OfferResponse>)
        fun onFailed(errorMessage: String)
    }

    private fun getCurrentAsset(): Asset {
        val assetCode = WalletApplication.userSession.getSessionAsset().assetCode
        val assetIssuer = WalletApplication.userSession.getSessionAsset().assetIssuer

        return if (assetCode == Constants.LUMENS_ASSET_TYPE) {
            AssetTypeNative()
        } else {
            Asset.createNonNativeAsset(assetCode, KeyPair.fromAccountId(assetIssuer))
        }
    }

    /**
     * HORIZON_SUBMIT_TIMEOUT is a time in seconds after Horizon sends a timeout response
     * after internal txsub timeout.
     */
    private const val HORIZON_SUBMIT_TIMEOUT = 60L

    private fun getServer() : Server {
        checkNotNull(SERVER)
        val server = Server(SERVER)
        // These two clients are a copy of the lines 32 and 42 of org.stellar.sdk.Server class with the stetho interceptor
        // REVIEW this once you upgrade stellar library
        val httpClient = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .addNetworkInterceptor(ShadowedStethoInterceptor())
                .build()

        val submitHttpClient = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(HORIZON_SUBMIT_TIMEOUT + 5, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .addNetworkInterceptor(ShadowedStethoInterceptor())
                .build()

        server.httpClient = httpClient
        server.submitHttpClient = submitHttpClient

        return server
    }
}
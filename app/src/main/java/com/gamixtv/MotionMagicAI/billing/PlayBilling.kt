package com.gamixtv.MotionMagicAI.billing

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/** Play only collects payment. The Django server verifies every token and grants credits. */
class PlayBilling(context: Context, private val onPurchased: (String, String) -> Unit, private val onMessage: (String) -> Unit) : PurchasesUpdatedListener {
    companion object {
        const val CREATOR = "motion_creator_monthly"
        const val LEGEND = "motion_legend_monthly"
        const val TOPUP_500 = "motion_credits_500_k7p4"
        const val PACK_2500 = "motion_pack_2500"
        const val PACK_5000 = "motion_pack_5000"
        const val PACK_10000 = "motion_pack_10000"
        const val PACK_20000 = "motion_pack_20000"
        val creditPacks = setOf(PACK_2500, PACK_5000, PACK_10000, PACK_20000)
        val recoverableCreditPacks = creditPacks + setOf(TOPUP_500, "motion_topup_500")
        private fun type(sku: String) = if (sku in creditPacks) BillingClient.ProductType.INAPP else BillingClient.ProductType.SUBS
    }

    private val main = Handler(Looper.getMainLooper())
    private var pendingPrices: Pair<Set<String>, (Map<String, String>) -> Unit>? = null
    private var connecting = true
    private val client = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    init {
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                main.post {
                    connecting = false
                    val pending = pendingPrices
                    pendingPrices = null
                    if(pending != null) {
                        if(result.responseCode == BillingClient.BillingResponseCode.OK) loadPrices(pending.first, pending.second)
                        else pending.second(emptyMap())
                    }
                }
            }
            override fun onBillingServiceDisconnected() = Unit // Auto reconnection handles the next request.
        })
    }

    /** Display-only prices; checkout still requests fresh, eligible product details. */
    fun loadPrices(products: Set<String>, loaded: (Map<String, String>) -> Unit) {
        val allowed = products.intersect(creditPacks)
        if(allowed.isEmpty()) { loaded(emptyMap()); return }
        if(!client.isReady && connecting) { pendingPrices = allowed to loaded; return }
        val request = QueryProductDetailsParams.newBuilder().setProductList(allowed.map {
            QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.INAPP).build()
        }).build()
        client.queryProductDetailsAsync(request) { result, details ->
            val prices = if(result.responseCode != BillingClient.BillingResponseCode.OK) emptyMap() else
                details.productDetailsList.filter { it.productId in allowed }.mapNotNull { item ->
                    val offer = buyOffer(item)
                    offer?.formattedPrice?.takeIf { it.isNotBlank() }?.let { item.productId to it }
                }.toMap()
            main.post { loaded(prices) }
        }
    }

    private fun buyOffer(item: ProductDetails) =
        item.oneTimePurchaseOfferDetailsList.orEmpty().firstOrNull { it.rentalDetails == null && it.offerId == null }
            ?: item.oneTimePurchaseOfferDetails?.takeIf { it.rentalDetails == null && it.offerId == null }

    fun buy(activity: Activity, sku: String, accountId: String, onFinished: () -> Unit) {
        if (sku !in creditPacks) {
            onMessage("This credit pack is not available.")
            onFinished()
            return
        }
        val request = QueryProductDetailsParams.newBuilder().setProductList(listOf(
            QueryProductDetailsParams.Product.newBuilder().setProductId(sku).setProductType(type(sku)).build()
        )).build()
        client.queryProductDetailsAsync(request) { result, details ->
            activity.runOnUiThread {
                try {
                    if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                        onMessage("Google Play is unavailable. Please try again.")
                        return@runOnUiThread
                    }
                    val item = details.productDetailsList.firstOrNull { it.productId == sku }
                    if (item == null) {
                        onMessage("This credit pack is not active in Google Play yet.")
                        return@runOnUiThread
                    }
                    // Credit packs use a standard outright buy, never a rental or pre-order.
                    val offer = buyOffer(item)
                    val offerToken = offer?.offerToken?.takeIf { it.isNotBlank() }
                    if(offerToken == null) {
                        onMessage("This credit pack has no available buy option in Google Play yet.")
                        return@runOnUiThread
                    }
                    val detail = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(item).setOfferToken(offerToken).build()
                    val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(detail))
                        .setObfuscatedAccountId(accountId).build()
                    val launched = client.launchBillingFlow(activity, params)
                    if (launched.responseCode != BillingClient.BillingResponseCode.OK) {
                        onMessage("Google Play could not open payment. Please try again.")
                    }
                } finally {
                    onFinished()
                }
            }
        }
    }

    /** Call after sign-in too: a completed purchase is recoverable after an app restart. */
    fun recover() {
        listOf(BillingClient.ProductType.INAPP).forEach { productType ->
            client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(productType).build()) { result, purchases ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) purchases.forEach(::deliver)
            }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases.orEmpty().forEach(::deliver)
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> recover()
            else -> onMessage("Google Play purchase did not finish. Please try again.")
        }
    }

    private fun deliver(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            purchase.products.forEach { onPurchased(it, purchase.purchaseToken) }
        } else if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
            onMessage("Payment is pending. Credits will arrive after Google Play confirms it.")
        }
    }

    fun close() {
        pendingPrices = null
        client.endConnection()
    }
}

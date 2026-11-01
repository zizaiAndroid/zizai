/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.ai

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import dev.patrickgold.florisboard.lib.devtools.flogError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Zizai Pro (ADR-9): the `zizai_pro` subscription, bought through Play Billing and granted by
 * zizai-server after it has asked Google. The app never decides on its own that someone is Pro: it shows
 * what the server last said.
 */
object ZizaiPro {
    const val PRODUCT_ID = "zizai_pro"
    const val BASE_PLAN_MONTHLY = "monthly"
    const val BASE_PLAN_YEARLY = "yearly"

    private val _status = MutableStateFlow<ZizaiAiApi.AccountStatus?>(null)
    /** The server's last word on this device's account; null until it has been asked. */
    val status: StateFlow<ZizaiAiApi.AccountStatus?> = _status

    internal fun update(status: ZizaiAiApi.AccountStatus) {
        _status.value = status
    }

    /** Asks the server for the current plan and usage, installing the account first if needed. */
    suspend fun refresh(): ZizaiAiApi.AccountStatus {
        val account = ZizaiAi.install()
        return try {
            ZizaiAiApi.account(account.apiKey).also(::update)
        } catch (e: ZizaiAiApi.ZizaiAiException) {
            if (e.status == 401) {
                // The account was deleted elsewhere, or the token was rotated out: start again.
                ZizaiAi.forgetToken()
                ZizaiAiApi.account(ZizaiAi.install().apiKey).also(::update)
            } else {
                throw e
            }
        }
    }

    /** One way to pay, as Play offers it here: localized price, period and any free trial. */
    data class Plan(
        val basePlanId: String,
        val formattedPrice: String,
        /** ISO 8601 period of the paid phase, e.g. P1M or P1Y. */
        val billingPeriod: String,
        /** ISO 8601 length of the free trial, or null when this plan has none. */
        val freeTrial: String?,
        internal val details: ProductDetails,
        internal val offerToken: String,
    )

    sealed interface Shop {
        data class Ready(val plans: List<Plan>) : Shop
        /** No Play Billing here: installed outside Play, or no store account. */
        data class Unavailable(val code: Int) : Shop
    }

    sealed interface PurchaseResult {
        data class Done(val status: ZizaiAiApi.AccountStatus) : PurchaseResult
        data object Cancelled : PurchaseResult
        data object Pending : PurchaseResult
        data class Failed(val message: String) : PurchaseResult
    }

    suspend fun shop(context: Context): Shop = Billing(context).use { billing ->
        val connected = billing.connect()
        if (connected != BillingResponseCode.OK) return@use Shop.Unavailable(connected)
        val details = billing.product() ?: return@use Shop.Unavailable(BillingResponseCode.ITEM_UNAVAILABLE)
        val plans = details.subscriptionOfferDetails.orEmpty()
            .groupBy { it.basePlanId }
            .mapNotNull { (basePlanId, offers) ->
                // Prefer an offer with a free trial (Play only shows it to people who are eligible).
                val offer = offers.firstOrNull { o -> o.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L } }
                    ?: offers.firstOrNull() ?: return@mapNotNull null
                val phases = offer.pricingPhases.pricingPhaseList
                val paid = phases.lastOrNull { it.priceAmountMicros > 0L } ?: return@mapNotNull null
                Plan(
                    basePlanId = basePlanId,
                    formattedPrice = paid.formattedPrice,
                    billingPeriod = paid.billingPeriod,
                    freeTrial = phases.firstOrNull { it.priceAmountMicros == 0L }?.billingPeriod,
                    details = details,
                    offerToken = offer.offerToken,
                )
            }
            .sortedBy { if (it.basePlanId == BASE_PLAN_YEARLY) 0 else 1 }
        if (plans.isEmpty()) Shop.Unavailable(BillingResponseCode.ITEM_UNAVAILABLE) else Shop.Ready(plans)
    }

    /** Opens Play's purchase sheet for [plan], then has the server verify and attach what comes back. */
    suspend fun purchase(activity: Activity, plan: Plan): PurchaseResult = Billing(activity).use { billing ->
        if (billing.connect() != BillingResponseCode.OK) return@use PurchaseResult.Failed("Google Play is not available.")
        val account = ZizaiAi.install()
        val launched = withContext(Dispatchers.Main) { billing.launch(activity, plan, account.walletId) }
        if (launched != BillingResponseCode.OK) return@use PurchaseResult.Failed("Google Play could not open ($launched).")
        when (val outcome = billing.awaitOutcome()) {
            Outcome.Cancelled -> PurchaseResult.Cancelled
            is Outcome.Failed -> PurchaseResult.Failed(outcome.message.ifBlank { "Purchase failed (${outcome.code})." })
            is Outcome.Purchased -> {
                val ours = outcome.purchases.filter { PRODUCT_ID in it.products }
                if (ours.any { it.purchaseState == Purchase.PurchaseState.PENDING }) return@use PurchaseResult.Pending
                var last: ZizaiAiApi.AccountStatus? = null
                for (purchase in ours) last = settle(billing, purchase) ?: last
                last?.let { PurchaseResult.Done(it) } ?: PurchaseResult.Failed("The purchase could not be confirmed. It will be retried.")
            }
        }
    }

    /**
     * Sends every subscription Play lists for this Google account to the server. Run on the Pro screen
     * and at app start: it's how Pro reaches a new phone, and how a purchase that was paid for but never
     * confirmed (a crash, no network) gets confirmed later.
     */
    suspend fun restore(context: Context): ZizaiAiApi.AccountStatus? = Billing(context).use { billing ->
        if (billing.connect() != BillingResponseCode.OK) return@use null
        var last: ZizaiAiApi.AccountStatus? = null
        for (purchase in billing.purchases().filter { PRODUCT_ID in it.products }) last = settle(billing, purchase) ?: last
        last
    }

    /** Deletes this device's account on the server and forgets it here. Does not cancel a subscription. */
    suspend fun deleteAccount() {
        val account = ZizaiAi.account()
        if (account.apiKey.isNotBlank()) ZizaiAiApi.deleteAccount(account.apiKey)
        ZizaiAi.forgetToken()
        _status.value = null
    }

    private suspend fun settle(billing: Billing, purchase: Purchase): ZizaiAiApi.AccountStatus? {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return null
        return try {
            val status = ZizaiAiApi.attachSubscription(ZizaiAi.install().apiKey, purchase.purchaseToken)
            // The server acknowledges too; doing it here as well means a slow server can't let Play's
            // three-day refund window run out.
            if (!purchase.isAcknowledged) billing.acknowledge(purchase.purchaseToken)
            update(status)
            status
        } catch (e: ZizaiAiApi.ZizaiAiException) {
            flogError { "Zizai Pro: purchase not confirmed (${e.code}): ${e.message}" }
            null
        }
    }

    private sealed interface Outcome {
        data class Purchased(val purchases: List<Purchase>) : Outcome
        data object Cancelled : Outcome
        data class Failed(val code: Int, val message: String) : Outcome
    }

    /** A short-lived Play Billing connection for subscriptions; one errand, then closed. */
    private class Billing(context: Context) : AutoCloseable {
        private val outcomes = Channel<Outcome>(Channel.BUFFERED)
        private val client = BillingClient.newBuilder(context.applicationContext)
            .setListener { result, purchases ->
                outcomes.trySend(
                    when (result.responseCode) {
                        BillingResponseCode.OK -> Outcome.Purchased(purchases.orEmpty())
                        BillingResponseCode.USER_CANCELED -> Outcome.Cancelled
                        else -> Outcome.Failed(result.responseCode, result.debugMessage)
                    },
                )
            }
            .enableAutoServiceReconnection()
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().enablePrepaidPlans().build())
            .build()

        suspend fun connect(): Int = suspendCancellableCoroutine { cont ->
            if (client.isReady) {
                cont.resume(BillingResponseCode.OK)
                return@suspendCancellableCoroutine
            }
            var settled = false
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (!settled) { settled = true; cont.resume(result.responseCode) }
                }

                override fun onBillingServiceDisconnected() {
                    if (!settled) { settled = true; cont.resume(BillingResponseCode.SERVICE_DISCONNECTED) }
                }
            })
        }

        suspend fun product(): ProductDetails? = suspendCancellableCoroutine { cont ->
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(
                    listOf(
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(PRODUCT_ID)
                            .setProductType(BillingClient.ProductType.SUBS)
                            .build(),
                    ),
                )
                .build()
            client.queryProductDetailsAsync(params) { _, result ->
                cont.resume(result.productDetailsList.orEmpty().firstOrNull())
            }
        }

        suspend fun purchases(): List<Purchase> = suspendCancellableCoroutine { cont ->
            val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
            client.queryPurchasesAsync(params) { result, purchases ->
                cont.resume(if (result.responseCode == BillingResponseCode.OK) purchases else emptyList())
            }
        }

        /** Must run on the main thread. [accountId] lets the server tie the order to this device's account. */
        fun launch(activity: Activity, plan: Plan, accountId: String): Int {
            val product = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(plan.details)
                .setOfferToken(plan.offerToken)
                .build()
            val flow = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(product))
                .apply { if (accountId.isNotBlank()) setObfuscatedAccountId(accountId) }
                .build()
            return client.launchBillingFlow(activity, flow).responseCode
        }

        suspend fun awaitOutcome(): Outcome = outcomes.receive()

        suspend fun acknowledge(purchaseToken: String): Int = suspendCancellableCoroutine { cont ->
            val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchaseToken).build()
            client.acknowledgePurchase(params) { result -> cont.resume(result.responseCode) }
        }

        override fun close() {
            outcomes.close()
            client.endConnection()
        }
    }
}

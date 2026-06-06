package com.aifacestudio.data.billing

import com.aifacestudio.data.model.SubscriptionPlan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class SubscriptionState(
    val plan: SubscriptionPlan = SubscriptionPlan.FREE,
    val monthlyLimit: Int = 10,
    val usedThisMonth: Int = 0,
    val priorityQueue: Boolean = false,
    val cloudBackup: Boolean = false
)

@Singleton
class BillingRepository @Inject constructor() {
    private val _subscriptionState = MutableStateFlow(SubscriptionState())
    val subscriptionState: StateFlow<SubscriptionState> = _subscriptionState

    fun markPremiumForPreview() {
        _subscriptionState.value = SubscriptionState(
            plan = SubscriptionPlan.PREMIUM,
            monthlyLimit = Int.MAX_VALUE,
            usedThisMonth = 0,
            priorityQueue = true,
            cloudBackup = true
        )
    }
}

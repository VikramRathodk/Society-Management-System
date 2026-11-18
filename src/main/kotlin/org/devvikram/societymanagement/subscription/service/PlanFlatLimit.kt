package org.devvikram.societymanagement.subscription.service

import org.devvikram.societymanagement.society.service.FlatLimit
import org.springframework.stereotype.Component

// The plan's flat limit, checked whenever flats are added (one at a time or in bulk from the setup wizard).
@Component
class PlanFlatLimit(private val subscriptionService: SubscriptionService) : FlatLimit {
    override fun checkRoom(societyId: Long, adding: Int) = subscriptionService.checkRoomForFlats(societyId, adding)
}

package com.dynamictransport.driver.testing

import com.dynamictransport.driver.core.push.MessageNotification
import com.dynamictransport.driver.core.push.MessageNotifier

class FakeMessageNotifier : MessageNotifier {
    var enabled = true
    val shown = mutableListOf<MessageNotification>()
    val cancelled = mutableListOf<String?>()
    var cancelledAll = 0

    override fun ensureChannel() = Unit

    override fun canNotify(): Boolean = enabled

    override fun show(notification: MessageNotification) {
        if (enabled) shown += notification
    }

    override fun cancel(tripId: String?) {
        cancelled += tripId
    }

    override fun cancelAll() {
        cancelledAll++
    }
}

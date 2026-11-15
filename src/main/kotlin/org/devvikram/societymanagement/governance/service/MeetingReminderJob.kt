package org.devvikram.societymanagement.governance.service

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

// Every few minutes (`app.governance.reminder-interval`, default 10 minutes), pushes the reminder for
// meetings coming up within their society's `meetingReminderHours`. Each meeting is reminded once.
@Component
class MeetingReminderJob(
    private val meetingService: MeetingService,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.governance.reminder-interval:PT10M}", initialDelayString = "PT1M")
    fun run() {
        try {
            val sent = meetingService.sendReminders(clock.instant())
            if (sent > 0) log.info("Sent {} meeting reminder(s)", sent)
        } catch (e: Exception) {
            log.warn("Meeting reminder run failed", e)
        }
    }
}

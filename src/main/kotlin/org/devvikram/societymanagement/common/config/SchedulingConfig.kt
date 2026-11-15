package org.devvikram.societymanagement.common.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

// Turns on @Scheduled jobs (meeting reminders, later billing runs). One application instance is assumed:
// with several, set `app.scheduling.enabled=false` on all but one. The jobs are safe to run again (they
// mark what they have done), so a missed or repeated run does no harm.
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = ["app.scheduling.enabled"], havingValue = "true", matchIfMissing = true)
class SchedulingConfig

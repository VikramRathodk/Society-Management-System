package org.devvikram.societymanagement.common.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

// The current time for code that stores or compares instants (e.g. invitation expiry), so it can be fixed in tests.
@Configuration
class ClockConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

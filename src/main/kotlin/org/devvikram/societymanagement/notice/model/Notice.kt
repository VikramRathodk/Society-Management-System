package org.devvikram.societymanagement.notice.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import org.devvikram.societymanagement.society.model.Society
import org.devvikram.societymanagement.user.model.User
import java.time.Instant

// A society-wide announcement on the notice board.
@Entity
@Table(name = "notice")
class Notice(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "society_id", nullable = false)
    var society: Society,

    @Column(nullable = false)
    var title: String,

    var description: String? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "posted_by", nullable = false)
    var postedBy: User,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    // Last edit; updatedBy differs from postedBy when a moderator changed someone else's notice.
    @Column(name = "updated_at")
    var updatedAt: Instant? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    var updatedBy: User? = null

    @PrePersist
    fun onCreate() {
        if (createdAt == null) createdAt = Instant.now()
    }
}

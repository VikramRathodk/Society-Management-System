package org.devvikram.societymanagement.common.storage

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Component
import java.time.Instant

@Entity
@Table(name = "stored_photo")
class StoredPhoto(
    @Column(name = "content_type", nullable = false)
    var contentType: String,

    @Column(name = "size_bytes", nullable = false)
    var sizeBytes: Int,

    @Column(nullable = false)
    var data: ByteArray,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "created_at")
    var createdAt: Instant? = null

    @PrePersist
    fun onCreate() {
        if (createdAt == null) createdAt = Instant.now()
    }
}

interface StoredPhotoJpaRepository : JpaRepository<StoredPhoto, Long>

// Interim store: photo bytes in a Postgres BYTEA column, keyed by row id. Replace with an
// object-storage implementation once one is chosen.
@Component
class DatabasePhotoStorage(private val repository: StoredPhotoJpaRepository) : PhotoStorage {

    override fun save(data: ByteArray, contentType: String): String =
        repository.save(StoredPhoto(contentType = contentType, sizeBytes = data.size, data = data)).id.toString()

    override fun load(key: String): Photo? =
        key.toLongOrNull()?.let { repository.findById(it).orElse(null) }?.let { Photo(it.data, it.contentType) }

    override fun delete(key: String) {
        key.toLongOrNull()?.let { repository.deleteById(it) }
    }
}

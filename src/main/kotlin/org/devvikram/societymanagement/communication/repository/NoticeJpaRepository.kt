package org.devvikram.societymanagement.communication.repository

import org.devvikram.societymanagement.communication.model.Notice
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface NoticeJpaRepository : JpaRepository<Notice, Long> {
    @Query(
        "select n from Notice n join fetch n.society join fetch n.postedBy left join fetch n.updatedBy " +
            "where n.id = :id"
    )
    fun findWithDetailsById(@Param("id") id: Long): Notice?

    // id breaks ties between notices posted in the same instant.
    @Query(
        "select n from Notice n join fetch n.society join fetch n.postedBy left join fetch n.updatedBy " +
            "where n.society.id = :societyId order by n.createdAt desc, n.id desc"
    )
    fun findWithDetailsBySocietyId(@Param("societyId") societyId: Long, pageable: Pageable): List<Notice>
}

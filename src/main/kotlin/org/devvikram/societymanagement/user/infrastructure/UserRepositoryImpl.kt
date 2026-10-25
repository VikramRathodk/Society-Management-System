package org.devvikram.societymanagement.user.infrastructure

import org.devvikram.societymanagement.user.domain.User
import org.devvikram.societymanagement.user.domain.UserRepository
import org.springframework.stereotype.Repository

@Repository
class UserRepositoryImpl(
    private val userJpaRepository: UserJpaRepository,
) : UserRepository {
    override fun save(user: User): User = userJpaRepository.save(user)

    override fun findById(id: Long): User? = userJpaRepository.findById(id).orElse(null)

    override fun findAll(): List<User> = userJpaRepository.findAll()

    override fun findAllById(ids: Collection<Long>): List<User> =
        if (ids.isEmpty()) emptyList() else userJpaRepository.findAllById(ids)

    override fun findByPhone(phone: String): User? = userJpaRepository.findByPhone(phone)

    override fun delete(user: User) {
        userJpaRepository.deleteDirectly(user.id!!)
    }
}

package org.devvikram.societymanagement.user.repository

import org.devvikram.societymanagement.user.model.User
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

    override fun clearFcmTokens(tokens: Collection<String>, exceptUserId: Long?): Int = when {
        tokens.isEmpty() -> 0
        exceptUserId == null -> userJpaRepository.clearFcmTokens(tokens)
        else -> userJpaRepository.clearFcmTokensExcept(tokens, exceptUserId)
    }

    override fun delete(user: User) {
        userJpaRepository.deleteDirectly(user.id!!)
    }
}

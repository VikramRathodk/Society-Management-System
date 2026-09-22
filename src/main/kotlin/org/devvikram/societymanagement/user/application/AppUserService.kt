package org.devvikram.societymanagement.user.application

import org.devvikram.societymanagement.society.domain.ComplexRepository
import org.devvikram.societymanagement.society.domain.FlatRepository
import org.devvikram.societymanagement.society.domain.SocietyRepository
import org.devvikram.societymanagement.user.domain.AppUser
import org.devvikram.societymanagement.user.domain.AppUserRepository
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class AppUserService(
    private val appUserRepository: AppUserRepository,
    private val societyRepository: SocietyRepository,
    private val complexRepository: ComplexRepository,
    private val flatRepository: FlatRepository,
    private val passwordEncoder: PasswordEncoder,
) {
    @Transactional
    fun createUser(command: CreateAppUserCommand): AppUser {
        require((command.societyId == null) != (command.complexId == null)) {
            "Exactly one of societyId or complexId must be set"
        }
        require(appUserRepository.findByPhone(command.phone) == null) {
            "Phone number ${command.phone} is already in use"
        }

        val society = command.societyId?.let {
            societyRepository.findById(it) ?: throw NoSuchElementException("Society $it not found")
        }
        val complex = command.complexId?.let {
            complexRepository.findById(it) ?: throw NoSuchElementException("Complex $it not found")
        }
        val flat = command.flatId?.let {
            flatRepository.findById(it) ?: throw NoSuchElementException("Flat $it not found")
        }

        val appUser = AppUser(
            name = command.name,
            phone = command.phone,
            email = command.email,
            passwordHash = passwordEncoder.encode(command.rawPassword)!!,
            role = command.role,
            flat = flat,
            residentType = command.residentType,
            society = society,
            complex = complex,
        )
        return appUserRepository.save(appUser)
    }

    fun getById(id: UUID): AppUser =
        appUserRepository.findById(id) ?: throw NoSuchElementException("User $id not found")

    fun getByPhone(phone: String): AppUser? = appUserRepository.findByPhone(phone)

    fun getAll(): List<AppUser> = appUserRepository.findAll()

    @Transactional
    fun updateProfile(id: UUID, command: UpdateProfileCommand): AppUser {
        val user = getById(id)
        command.name?.let { user.name = it }
        command.email?.let { user.email = it }
        command.fcmToken?.let { user.fcmToken = it }
        return appUserRepository.save(user)
    }

    @Transactional
    fun deactivate(id: UUID): AppUser {
        val user = getById(id)
        user.isActive = false
        return appUserRepository.save(user)
    }
}

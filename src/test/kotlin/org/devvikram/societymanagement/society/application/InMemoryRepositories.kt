package org.devvikram.societymanagement.society.application

import org.devvikram.societymanagement.society.domain.Complex
import org.devvikram.societymanagement.society.domain.ComplexRepository
import org.devvikram.societymanagement.society.domain.Flat
import org.devvikram.societymanagement.society.domain.FlatRepository
import org.devvikram.societymanagement.society.domain.Society
import org.devvikram.societymanagement.society.domain.SocietyRepository
import org.devvikram.societymanagement.society.domain.Wing
import org.devvikram.societymanagement.society.domain.WingRepository
import java.util.UUID

// Shared test fixtures for society/application service tests — plain in-memory
// implementations of the framework-free domain repository interfaces.

class InMemoryComplexRepository : ComplexRepository {
    private val store = mutableMapOf<UUID, Complex>()

    override fun save(complex: Complex): Complex {
        if (complex.id == null) complex.id = UUID.randomUUID()
        store[complex.id!!] = complex
        return complex
    }

    override fun findById(id: UUID): Complex? = store[id]
    override fun findAll(): List<Complex> = store.values.toList()
}

class InMemorySocietyRepository : SocietyRepository {
    private val store = mutableMapOf<UUID, Society>()

    override fun save(society: Society): Society {
        if (society.id == null) society.id = UUID.randomUUID()
        store[society.id!!] = society
        return society
    }

    override fun findById(id: UUID): Society? = store[id]
    override fun findAll(): List<Society> = store.values.toList()
}

class InMemoryWingRepository : WingRepository {
    private val store = mutableMapOf<UUID, Wing>()

    override fun save(wing: Wing): Wing {
        if (wing.id == null) wing.id = UUID.randomUUID()
        store[wing.id!!] = wing
        return wing
    }

    override fun findById(id: UUID): Wing? = store[id]
    override fun findAll(): List<Wing> = store.values.toList()
    override fun findBySocietyId(societyId: UUID): List<Wing> = store.values.filter { it.society.id == societyId }
    override fun deleteById(id: UUID) {
        store.remove(id)
    }
}

class InMemoryFlatRepository : FlatRepository {
    private val store = mutableMapOf<UUID, Flat>()

    override fun save(flat: Flat): Flat {
        if (flat.id == null) flat.id = UUID.randomUUID()
        store[flat.id!!] = flat
        return flat
    }

    override fun findById(id: UUID): Flat? = store[id]
    override fun findAll(): List<Flat> = store.values.toList()
    override fun findByWingId(wingId: UUID): List<Flat> = store.values.filter { it.wing.id == wingId }
    override fun deleteById(id: UUID) {
        store.remove(id)
    }
}

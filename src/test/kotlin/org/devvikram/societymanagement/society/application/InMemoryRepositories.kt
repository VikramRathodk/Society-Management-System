package org.devvikram.societymanagement.society.application

import org.devvikram.societymanagement.society.domain.Complex
import org.devvikram.societymanagement.society.domain.ComplexRepository
import org.devvikram.societymanagement.society.domain.Flat
import org.devvikram.societymanagement.society.domain.FlatRepository
import org.devvikram.societymanagement.society.domain.Society
import org.devvikram.societymanagement.society.domain.SocietyRepository
import org.devvikram.societymanagement.society.domain.Wing
import org.devvikram.societymanagement.society.domain.WingRepository

// Shared test fixtures for society/application service tests — plain in-memory
// implementations of the framework-free domain repository interfaces.

class InMemoryComplexRepository : ComplexRepository {
    private val store = mutableMapOf<Long, Complex>()
    private var nextId = 1L

    override fun save(complex: Complex): Complex {
        if (complex.id == null) complex.id = nextId++
        store[complex.id!!] = complex
        return complex
    }

    override fun findById(id: Long): Complex? = store[id]
    override fun findAll(): List<Complex> = store.values.toList()
}

class InMemorySocietyRepository : SocietyRepository {
    private val store = mutableMapOf<Long, Society>()
    private var nextId = 1L

    override fun save(society: Society): Society {
        if (society.id == null) society.id = nextId++
        store[society.id!!] = society
        return society
    }

    override fun findById(id: Long): Society? = store[id]
    override fun findAll(): List<Society> = store.values.toList()
}

class InMemoryWingRepository : WingRepository {
    private val store = mutableMapOf<Long, Wing>()
    private var nextId = 1L

    override fun save(wing: Wing): Wing {
        if (wing.id == null) wing.id = nextId++
        store[wing.id!!] = wing
        return wing
    }

    override fun findById(id: Long): Wing? = store[id]
    override fun findAll(): List<Wing> = store.values.toList()
    override fun findBySocietyId(societyId: Long): List<Wing> = store.values.filter { it.society.id == societyId }
    override fun deleteById(id: Long) {
        store.remove(id)
    }
}

class InMemoryFlatRepository : FlatRepository {
    private val store = mutableMapOf<Long, Flat>()
    private var nextId = 1L

    override fun save(flat: Flat): Flat {
        if (flat.id == null) flat.id = nextId++
        store[flat.id!!] = flat
        return flat
    }

    override fun findById(id: Long): Flat? = store[id]
    override fun findAll(): List<Flat> = store.values.toList()
    override fun findByWingId(wingId: Long): List<Flat> = store.values.filter { it.wing.id == wingId }
    override fun deleteById(id: Long) {
        store.remove(id)
    }
}

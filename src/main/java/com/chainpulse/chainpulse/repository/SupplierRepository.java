package com.chainpulse.chainpulse.repository;

import com.chainpulse.chainpulse.entity.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * SupplierRepository — handles all database operations for Supplier.
 * By extending JpaRepository, we get these methods for FREE:
 * - save(supplier)       → insert or update a supplier
 * - findById(id)         → get supplier by ID
 * - findAll()            → get all suppliers
 * - deleteById(id)       → delete a supplier
 * - count()              → how many suppliers exist
 * No need to write any SQL — Spring Data JPA handles it all!
 */
@Repository
public interface SupplierRepository extends JpaRepository<Supplier, Long> {

    // Custom query — find supplier by name
    // Spring auto-generates SQL: SELECT * FROM suppliers WHERE name = ?
    boolean existsByName(String name);
}
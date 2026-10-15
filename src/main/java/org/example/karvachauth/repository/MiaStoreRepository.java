package org.example.karvachauth.repository;

import org.example.karvachauth.entity.MiaStore;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MiaStoreRepository extends JpaRepository<MiaStore, Long> {

    /** "Active" stores only (case-insensitive). */
    List<MiaStore> findByStatusIgnoreCase(String status);
}
package org.example.karvachauth.repository;

import org.example.karvachauth.entity.DashboardUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public interface DashboardUserRepository extends JpaRepository<DashboardUser, Long> {

    @Query(value = "SELECT * FROM dashboard_user WHERE username = :username LIMIT 1", nativeQuery = true)
    Optional<DashboardUser> findByUsername(@Param("username") String username);

    @Query(value = "SELECT COUNT(*) FROM dashboard_user WHERE username = :username", nativeQuery = true)
    long countByUsername(@Param("username") String username);

    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO dashboard_user (username, password_hash, role, enabled)
            VALUES (:username, :passwordHash, :role, 1)
            """, nativeQuery = true)
    int insertUser(@Param("username") String username,
                   @Param("passwordHash") String passwordHash,
                   @Param("role") String role);

    @Modifying
    @Transactional
    @Query(value = "UPDATE dashboard_user SET last_login_at = NOW() WHERE username = :username", nativeQuery = true)
    int updateLastLogin(@Param("username") String username);
}
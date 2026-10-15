package org.example.karvachauth.repository;

import org.example.karvachauth.entity.Booking;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    /** Used when generating a MIA-xxxx reference — regenerate if it already exists. */
    boolean existsByBookingRef(String bookingRef);

    Optional<Booking> findByBookingRef(String bookingRef);

    List<Booking> findByPhoneOrderByCreatedAtDesc(String phone);

    /** A store's visits for a day — for the store notification / dashboard. */
    List<Booking> findByStoreCodeAndVisitDateOrderByTimeSlotAsc(String storeCode, LocalDate visitDate);

    long countByCreatedAtBetween(LocalDateTime from, LocalDateTime to);

    Optional<Booking> findFirstBySessionIdAndStatusOrderByIdDesc(Long sessionId, String status);
}
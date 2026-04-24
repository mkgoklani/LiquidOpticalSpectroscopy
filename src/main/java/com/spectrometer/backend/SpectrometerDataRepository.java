package com.spectrometer.backend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SpectrometerDataRepository extends JpaRepository<SpectrometerData, Long> {

    // Latest record regardless of type
    Optional<SpectrometerData> findTopByOrderByTimestampDesc();

    // Last 100 records (all, including simulated) — for display/dashboard
    List<SpectrometerData> findTop100ByOrderByTimestampDesc();

    // Last 500 REAL records only — used for AI training and CSV export
    List<SpectrometerData> findTop500ByIsSimulatedFalseOrderByTimestampDesc();

    // Count of real records — for stat cards and auto-import guard
    long countByIsSimulatedFalse();
}

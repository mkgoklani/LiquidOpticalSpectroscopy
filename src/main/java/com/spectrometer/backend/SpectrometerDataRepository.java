package com.spectrometer.backend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;

@Repository
public interface SpectrometerDataRepository extends JpaRepository<SpectrometerData, Long> {

    // Find the latest record based on timestamp
    Optional<SpectrometerData> findTopByOrderByTimestampDesc();
    
    // Get past X records
    List<SpectrometerData> findTop100ByOrderByTimestampDesc();
}

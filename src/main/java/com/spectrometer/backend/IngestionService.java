package com.spectrometer.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class IngestionService {
    
    private static final Logger logger = LoggerFactory.getLogger(IngestionService.class);
    
    @Autowired
    private SpectrometerDataRepository repository;
    
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void processMessage(String payload) {
        try {
            SpectrometerDataDto dto = objectMapper.readValue(payload, SpectrometerDataDto.class);
            processManual(dto);
        } catch (Exception e) {
            logger.error("Failed to process MQTT message", e);
        }
    }

    public void processManual(SpectrometerDataDto dto) {
        try {
            SpectrometerData data = new SpectrometerData();
            data.setDeviceId(dto.deviceId != null ? dto.deviceId : "manual-input");
            data.setTimestamp(dto.timestamp != null ? dto.timestamp : System.currentTimeMillis());
            data.setOpticalR(dto.opticalR);
            data.setOpticalG(dto.opticalG);
            data.setOpticalB(dto.opticalB);
            data.setConductivityMv(dto.conductivityMv);
            
            // Simulate purity calculation for MVP since AI module isn't connected yet
            // If all 0, it's a sensor fault
            if (data.getOpticalR() == 0 && data.getOpticalG() == 0 && data.getOpticalB() == 0) {
                 data.setPurityPercentage(0.0);
                 logger.warn("Sensor Fault detected: optical values are all 0");
            } else {
                 double purity = Math.max(0, 100 - (data.getConductivityMv() * 0.1) - (Math.abs(data.getOpticalR() - 150) * 0.1));
                 data.setPurityPercentage(Math.round(purity * 100.0) / 100.0);
            }
            
            repository.save(data);
            logger.info("Saved data points to DB - Device: {}", data.getDeviceId());
        } catch (Exception e) {
            logger.error("Failed to process data", e);
        }
    }
}

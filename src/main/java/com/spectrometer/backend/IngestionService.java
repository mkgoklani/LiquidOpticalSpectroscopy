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
    private volatile long lastHardwareTimestamp = 0; // Hardware Lockout Tracker

    public void processMessage(String payload) {
        try {
            lastHardwareTimestamp = System.currentTimeMillis(); // Hardware is active!
            SpectrometerDataDto dto = objectMapper.readValue(payload, SpectrometerDataDto.class);
            dto.deviceId = "hardware-mqtt-node"; // Hardcode real hardware ID
            processManual(dto);
        } catch (Exception e) {
            logger.error("Failed to process MQTT message", e);
        }
    }

    public void processManual(SpectrometerDataDto dto) {
        String reqDeviceId = dto.deviceId != null ? dto.deviceId : "manual-input";
        
        // HARDWARE LOCKOUT MECHANISM: Prevent dummy data from interfering with real physical tests
        boolean isSimulation = reqDeviceId.contains("manual") || reqDeviceId.contains("simulated") || reqDeviceId.contains("colorimeter");
        if (isSimulation && (System.currentTimeMillis() - lastHardwareTimestamp < 10000)) {
            logger.warn("Simulated data rejected! Active hardware stream detected.");
            throw new IllegalStateException("HARDWARE_ACTIVE_LOCKOUT");
        }

        try {
            SpectrometerData data = new SpectrometerData();
            data.setDeviceId(reqDeviceId);
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
            // Base-16 Transpiler for Digital Eyedropper
            data.setHexCode(rgbToHex(data.getOpticalR(), data.getOpticalG(), data.getOpticalB()));
            
            repository.save(data);
            logger.info("Saved data points to DB - Device: {} | HEX: {}", data.getDeviceId(), data.getHexCode());
        } catch (Exception e) {
            logger.error("Failed to process data", e);
        }
    }

    private String rgbToHex(int r, int g, int b) {
        // Data Normalization (ensuring 0-255 standard)
        int normR = Math.max(0, Math.min(255, r));
        int normG = Math.max(0, Math.min(255, g));
        int normB = Math.max(0, Math.min(255, b));
        return String.format("#%02X%02X%02X", normR, normG, normB);
    }
}

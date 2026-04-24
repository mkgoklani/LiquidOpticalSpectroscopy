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

    // Hardware Lockout: tracks the last time real hardware sent data (epoch ms)
    private volatile long lastHardwareTimestamp = 0;

    // How long (ms) to hold the lockout after the last real hardware message
    private static final long HARDWARE_LOCKOUT_WINDOW_MS = 10_000;

    /**
     * Called by MQTT inbound. Tags the payload as real hardware and processes it.
     */
    public void processMessage(String payload) {
        if (payload == null || payload.isBlank()) {
            logger.warn("Received empty MQTT payload. Ignoring.");
            return;
        }
        try {
            lastHardwareTimestamp = System.currentTimeMillis();
            SpectrometerDataDto dto = objectMapper.readValue(payload, SpectrometerDataDto.class);
            dto.deviceId = "hardware-mqtt-node";
            processInternal(dto, false);
        } catch (com.fasterxml.jackson.core.JsonParseException e) {
            logger.error("Malformed MQTT JSON payload: {}", payload, e);
        } catch (Exception e) {
            logger.error("Failed to process MQTT message", e);
        }
    }

    /**
     * Called by REST controller for manual/simulated scans.
     * Throws IllegalStateException with code HARDWARE_ACTIVE_LOCKOUT if real hardware is streaming.
     */
    public void processManual(SpectrometerDataDto dto) {
        String reqDeviceId = (dto.deviceId != null) ? dto.deviceId : "manual-input";

        boolean isSimulation = reqDeviceId.contains("manual")
                || reqDeviceId.contains("simulated")
                || reqDeviceId.contains("colorimeter");

        if (isSimulation && (System.currentTimeMillis() - lastHardwareTimestamp < HARDWARE_LOCKOUT_WINDOW_MS)) {
            logger.warn("Hardware lockout active. Rejecting simulated payload from device: {}", reqDeviceId);
            throw new IllegalStateException("HARDWARE_ACTIVE_LOCKOUT");
        }

        processInternal(dto, true);
    }

    /**
     * Core processing logic shared by both MQTT and manual ingestion paths.
     */
    private void processInternal(SpectrometerDataDto dto, boolean isManual) {
        // Input validation — reject clearly invalid payloads
        if (dto.opticalR == null) dto.opticalR = 0;
        if (dto.opticalG == null) dto.opticalG = 0;
        if (dto.opticalB == null) dto.opticalB = 0;
        if (dto.conductivityMv == null) dto.conductivityMv = 0;

        // Clamp to 0–255 for optical values
        dto.opticalR = clamp(dto.opticalR, 0, 255);
        dto.opticalG = clamp(dto.opticalG, 0, 255);
        dto.opticalB = clamp(dto.opticalB, 0, 255);
        // Clamp conductivity to a realistic range (0–2000 mV)
        dto.conductivityMv = clamp(dto.conductivityMv, 0, 2000);

        try {
            SpectrometerData data = new SpectrometerData();
            data.setDeviceId((dto.deviceId != null) ? dto.deviceId : "unknown-device");
            data.setTimestamp(dto.timestamp != null ? dto.timestamp : System.currentTimeMillis());
            data.setOpticalR(dto.opticalR);
            data.setOpticalG(dto.opticalG);
            data.setOpticalB(dto.opticalB);
            data.setConductivityMv(dto.conductivityMv);

            // Purity calculation: sensor fault = 0%, otherwise physics-based estimate
            if (dto.opticalR == 0 && dto.opticalG == 0 && dto.opticalB == 0) {
                data.setPurityPercentage(0.0);
                logger.warn("Sensor fault detected: all optical values are zero. Device: {}", data.getDeviceId());
            } else {
                double purity = 100
                        - (dto.conductivityMv * 0.1)
                        - (Math.abs(dto.opticalR - 150) * 0.1);
                data.setPurityPercentage(Math.round(Math.max(0, Math.min(100, purity)) * 100.0) / 100.0);
            }

            // HEX transpiler for the Digital Eyedropper module
            data.setHexCode(rgbToHex(data.getOpticalR(), data.getOpticalG(), data.getOpticalB()));

            repository.save(data);
            logger.info("[{}] Saved scan — Device: {} | HEX: {} | Purity: {}%",
                    isManual ? "MANUAL" : "MQTT",
                    data.getDeviceId(), data.getHexCode(), data.getPurityPercentage());

        } catch (Exception e) {
            logger.error("Failed to persist scan data", e);
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String rgbToHex(int r, int g, int b) {
        return String.format("#%02X%02X%02X",
                clamp(r, 0, 255),
                clamp(g, 0, 255),
                clamp(b, 0, 255));
    }
}

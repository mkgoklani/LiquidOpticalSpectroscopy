package com.spectrometer.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestTemplate;

@Service
public class IngestionService {

    private static final Logger logger = LoggerFactory.getLogger(IngestionService.class);

    @Autowired
    private SpectrometerDataRepository repository;

    @Autowired
    private RestTemplate restTemplate;

    @Value("${ai.server.url:http://localhost:5001}")
    private String aiServerUrl;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Hardware Lockout: timestamp of last real MQTT hardware message
    private volatile long lastHardwareTimestamp = 0;
    private static final long HARDWARE_LOCKOUT_WINDOW_MS = 10_000;

    // Device IDs that are always treated as simulated, regardless of isSimulated flag
    private static final Set<String> SIMULATED_DEVICE_PATTERNS = Set.of(
        "manual-input", "simulated", "colorimeter-input", "demo", "test"
    );

    // Active dataset collection session state
    private volatile boolean sessionActive = false;
    private volatile String sessionLiquidName = "";
    private volatile Double sessionExpectedPurity = 100.0;
    private volatile SpectrometerData latestScan = null;

    public void startSession(String liquidName, Double expectedPurity) {
        this.sessionLiquidName = liquidName;
        this.sessionExpectedPurity = expectedPurity;
        this.sessionActive = true;
    }

    public void stopSession() {
        this.sessionActive = false;
    }

    public SpectrometerData getLatestScan() {
        return this.latestScan;
    }

    // ------------------------------------------------------------------
    // Public API — called by MqttConfig (real hardware)
    // ------------------------------------------------------------------
    public void processMessage(String payload) {
        if (payload == null || payload.isBlank()) {
            logger.warn("Received empty MQTT payload. Ignoring.");
            return;
        }
        try {
            lastHardwareTimestamp = System.currentTimeMillis();
            SpectrometerDataDto dto = objectMapper.readValue(payload, SpectrometerDataDto.class);
            dto.deviceId   = "hardware-mqtt-node";
            dto.isSimulated = false; // Real hardware data is NEVER simulated
            processInternal(dto);
        } catch (com.fasterxml.jackson.core.JsonParseException e) {
            logger.error("Malformed MQTT JSON payload: {}", payload, e);
        } catch (Exception e) {
            logger.error("Failed to process MQTT message", e);
        }
    }

    // ------------------------------------------------------------------
    // Public API — called by REST controller (manual / simulated)
    // ------------------------------------------------------------------
    public void processManual(SpectrometerDataDto dto) {
        String deviceId = (dto.deviceId != null) ? dto.deviceId : "manual-input";

        // Check if this is a simulated device by name pattern
        boolean isSimDevice = SIMULATED_DEVICE_PATTERNS.stream()
                .anyMatch(pattern -> deviceId.toLowerCase().contains(pattern));

        // Explicit flag OR device pattern match → simulated
        boolean isSimulated = Boolean.TRUE.equals(dto.isSimulated) || isSimDevice;

        // Hardware lockout: simulated devices are rejected if real hardware has reported in the last 10 seconds
        if (isSimulated && (System.currentTimeMillis() - lastHardwareTimestamp < HARDWARE_LOCKOUT_WINDOW_MS)) {
            logger.warn("Hardware lockout active. Rejecting simulated payload from device: {}", deviceId);
            throw new IllegalStateException("HARDWARE_ACTIVE_LOCKOUT");
        }

        dto.isSimulated = isSimulated;
        processInternal(dto);
    }

    // ------------------------------------------------------------------
    // Core processing logic — shared by both paths
    // ------------------------------------------------------------------
    private void processInternal(SpectrometerDataDto dto) {
        // Null safety + clamping
        if (dto.opticalR == null) dto.opticalR = 0;
        if (dto.opticalG == null) dto.opticalG = 0;
        if (dto.opticalB == null) dto.opticalB = 0;
        if (dto.conductivityMv == null) dto.conductivityMv = 0;
        dto.opticalR       = clamp(dto.opticalR, 0, 255);
        dto.opticalG       = clamp(dto.opticalG, 0, 255);
        dto.opticalB       = clamp(dto.opticalB, 0, 255);
        dto.conductivityMv = clamp(dto.conductivityMv, 0, 2000);

        boolean isSimulated = Boolean.TRUE.equals(dto.isSimulated);

        try {
            SpectrometerData data = new SpectrometerData();
            data.setDeviceId((dto.deviceId != null) ? dto.deviceId : "unknown-device");
            data.setTimestamp(dto.timestamp != null ? dto.timestamp : System.currentTimeMillis());
            data.setOpticalR(dto.opticalR);
            data.setOpticalG(dto.opticalG);
            data.setOpticalB(dto.opticalB);
            data.setConductivityMv(dto.conductivityMv);
            data.setIsSimulated(isSimulated);

            // Purity prediction: first try AI model, fall back to calibrated formula
            double purity = 0.0;
            if (dto.opticalR == 0 && dto.opticalG == 0 && dto.opticalB == 0) {
                purity = 0.0;
            } else {
                boolean aiSuccess = false;
                try {
                    Map<String, Object> reqBody = new HashMap<>();
                    reqBody.put("opticalR", dto.opticalR);
                    reqBody.put("opticalG", dto.opticalG);
                    reqBody.put("opticalB", dto.opticalB);
                    reqBody.put("conductivityMv", dto.conductivityMv);

                    // Call AI server
                    Map<String, Object> resp = restTemplate.postForObject(
                        aiServerUrl + "/api/ai/predict", reqBody, Map.class);
                    
                    if (resp != null && "success".equals(resp.get("status"))) {
                        Object predObj = resp.get("prediction");
                        if (predObj != null) {
                            purity = ((Number) predObj).doubleValue();
                            aiSuccess = true;
                        }
                    }
                } catch (Exception e) {
                    // Fall back quietly
                }

                if (!aiSuccess) {
                    // Calibrated mathematical formula fallback based on conductivity:
                    // 100% -> 1930 mV
                    // 90%  -> 1835 mV
                    // 70%  -> 1620 mV
                    // 10%  -> 1335 mV
                    // 0%   -> ~800 mV
                    double cond = dto.conductivityMv;
                    if (cond >= 1930) {
                        purity = 100.0;
                    } else if (cond >= 1835) {
                        purity = 90.0 + (cond - 1835) * (10.0 / 95.0);
                    } else if (cond >= 1620) {
                        purity = 70.0 + (cond - 1620) * (20.0 / 215.0);
                    } else if (cond >= 1335) {
                        purity = 10.0 + (cond - 1335) * (60.0 / 285.0);
                    } else if (cond >= 800) {
                        purity = 0.0 + (cond - 800) * (10.0 / 535.0);
                    } else {
                        purity = 0.0;
                    }
                }
            }
            data.setPurityPercentage(Math.round(Math.max(0.0, Math.min(100.0, purity)) * 100.0) / 100.0);

            data.setHexCode(rgbToHex(data.getOpticalR(), data.getOpticalG(), data.getOpticalB()));

            // Always update the volatile latestScan for UI real-time polling
            this.latestScan = data;

            // Only persist to database during an active session
            if (isSimulated) {
                if (sessionActive) {
                    data.setDeviceId(sessionLiquidName);
                    data.setPurityPercentage(sessionExpectedPurity);
                }
                repository.save(data);
                logger.info("[SIM ⚠] Device: {} | HEX: {} | Purity: {}%", data.getDeviceId(), data.getHexCode(), data.getPurityPercentage());
            } else if (sessionActive) {
                data.setDeviceId(sessionLiquidName);
                data.setPurityPercentage(sessionExpectedPurity);
                repository.save(data);
                logger.info("[REAL ✓] Session: {} | HEX: {} | Purity: {}%", data.getDeviceId(), data.getHexCode(), data.getPurityPercentage());
            } else {
                logger.debug("[REAL DISCARDED] Not in active session. HEX: {}", data.getHexCode());
            }

        } catch (Exception e) {
            logger.error("Failed to persist scan data", e);
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String rgbToHex(int r, int g, int b) {
        return String.format("#%02X%02X%02X",
                clamp(r, 0, 255), clamp(g, 0, 255), clamp(b, 0, 255));
    }
}

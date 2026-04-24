package com.spectrometer.backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/scan")
@CrossOrigin(origins = "*") // Allow all origins for local dev
public class SpectrometerController {

    private static final Logger logger = LoggerFactory.getLogger(SpectrometerController.class);

    @Autowired
    private SpectrometerDataRepository repository;

    @Autowired
    private IngestionService ingestionService;

    @Autowired
    private RestTemplate restTemplate;

    @Value("${ai.server.url:http://localhost:5001}")
    private String aiServerUrl;

    /** GET /api/v1/scan — API health/discovery endpoint */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getEndpoints() {
        return ResponseEntity.ok(Map.of(
            "status", "online",
            "service", "Spectrometer API v1",
            "endpoints", List.of(
                "GET  /api/v1/scan/latest",
                "GET  /api/v1/scan/history",
                "POST /api/v1/scan/manual",
                "POST /api/v1/scan/train"
            )
        ));
    }

    /** POST /api/v1/scan/manual — Accept a manual or simulated scan payload */
    @PostMapping("/manual")
    public ResponseEntity<String> saveManualScan(@RequestBody SpectrometerDataDto dto) {
        if (dto == null) {
            return ResponseEntity.badRequest().body("Request body is required.");
        }
        try {
            ingestionService.processManual(dto);
            return ResponseEntity.ok("Manual scan saved.");
        } catch (IllegalStateException e) {
            if ("HARDWARE_ACTIVE_LOCKOUT".equals(e.getMessage())) {
                return ResponseEntity.status(423).body("HARDWARE_ACTIVE_LOCKOUT: Real hardware stream detected. Simulated data rejected.");
            }
            logger.error("Unexpected state during manual scan ingestion", e);
            return ResponseEntity.internalServerError().body("Ingestion failed: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Unhandled exception during manual scan ingestion", e);
            return ResponseEntity.internalServerError().body("Internal server error.");
        }
    }

    /** GET /api/v1/scan/latest — Return the most recent scan record */
    @GetMapping("/latest")
    public ResponseEntity<SpectrometerData> getLatestScan() {
        return repository.findTopByOrderByTimestampDesc()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    /** GET /api/v1/scan/history — Return last 100 scan records */
    @GetMapping("/history")
    public ResponseEntity<List<SpectrometerData>> getHistory() {
        List<SpectrometerData> history = repository.findTop100ByOrderByTimestampDesc();
        if (history.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(history);
    }

    /** POST /api/v1/scan/train — Proxy trigger to the Python AI training server */
    @PostMapping("/train")
    public ResponseEntity<String> triggerTraining() {
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                aiServerUrl + "/api/ai/train", null, String.class
            );
            logger.info("AI training triggered. AI server responded with status: {}", response.getStatusCode());
            return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
        } catch (ResourceAccessException e) {
            logger.warn("AI server is unreachable: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("AI Server is offline. Start the Python server at " + aiServerUrl);
        } catch (Exception e) {
            logger.error("Failed to trigger AI training", e);
            return ResponseEntity.internalServerError().body("Training proxy failed: " + e.getMessage());
        }
    }
}

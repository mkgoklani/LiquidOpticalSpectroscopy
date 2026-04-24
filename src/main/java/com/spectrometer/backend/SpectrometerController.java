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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/scan")
// CORS handled globally via AppConfig.corsConfigurer()
public class SpectrometerController {

    private static final Logger logger = LoggerFactory.getLogger(SpectrometerController.class);
    public  static final Path DATASET_CSV = Paths.get("dataset", "training_data.csv");
    private static final String CSV_HEADER = "deviceId,timestamp,opticalR,opticalG,opticalB,conductivityMv,purityPercentage,hexCode\n";

    @Autowired private SpectrometerDataRepository repository;
    @Autowired private IngestionService ingestionService;
    @Autowired private RestTemplate restTemplate;

    @Value("${ai.server.url:http://localhost:5001}")
    private String aiServerUrl;

    // ── Discovery ────────────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<Map<String, Object>> getEndpoints() {
        return ResponseEntity.ok(Map.of(
            "status", "online",
            "service", "Spectrometer API v1",
            "real_scan_count", repository.countByIsSimulatedFalse(),
            "endpoints", List.of(
                "GET  /api/v1/scan/latest",
                "GET  /api/v1/scan/history",
                "GET  /api/v1/scan/history/real",
                "GET  /api/v1/scan/export",
                "POST /api/v1/scan/manual",
                "POST /api/v1/scan/train"
            )
        ));
    }

    // ── Write ─────────────────────────────────────────────────────────────────────

    @PostMapping("/manual")
    public ResponseEntity<String> saveManualScan(@RequestBody SpectrometerDataDto dto) {
        if (dto == null) return ResponseEntity.badRequest().body("Request body is required.");
        try {
            ingestionService.processManual(dto);
            return ResponseEntity.ok("Scan saved.");
        } catch (IllegalStateException e) {
            if ("HARDWARE_ACTIVE_LOCKOUT".equals(e.getMessage())) {
                return ResponseEntity.status(423).body("HARDWARE_ACTIVE_LOCKOUT");
            }
            logger.error("Unexpected state during manual ingestion", e);
            return ResponseEntity.internalServerError().body("Ingestion failed: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Unhandled exception during manual ingestion", e);
            return ResponseEntity.internalServerError().body("Internal server error.");
        }
    }

    // ── Read ──────────────────────────────────────────────────────────────────────

    @GetMapping("/latest")
    public ResponseEntity<SpectrometerData> getLatestScan() {
        return repository.findTopByOrderByTimestampDesc()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    @GetMapping("/history")
    public ResponseEntity<List<SpectrometerData>> getHistory() {
        List<SpectrometerData> all = repository.findTop100ByOrderByTimestampDesc();
        return all.isEmpty() ? ResponseEntity.noContent().build() : ResponseEntity.ok(all);
    }

    /** Returns only REAL (non-simulated) scan records — used by the Python AI server for training. */
    @GetMapping("/history/real")
    public ResponseEntity<List<SpectrometerData>> getRealHistory() {
        List<SpectrometerData> real = repository.findTop500ByIsSimulatedFalseOrderByTimestampDesc();
        return real.isEmpty() ? ResponseEntity.noContent().build() : ResponseEntity.ok(real);
    }

    /** Count of real scans — for dashboard stat cards. */
    @GetMapping("/count/real")
    public ResponseEntity<Map<String, Long>> getRealCount() {
        return ResponseEntity.ok(Map.of("count", repository.countByIsSimulatedFalse()));
    }

    // ── Dataset CSV Export ────────────────────────────────────────────────────────

    /**
     * Writes all real scans to dataset/training_data.csv in the project root.
     * Called by the Python AI server before doing a Git push of the dataset.
     */
    @GetMapping("/export")
    public ResponseEntity<Map<String, Object>> exportDataset() {
        List<SpectrometerData> realData = repository.findTop500ByIsSimulatedFalseOrderByTimestampDesc();

        if (realData.isEmpty()) {
            return ResponseEntity.ok(Map.of("status", "empty", "count", 0,
                "message", "No real scan data to export."));
        }

        try {
            Files.createDirectories(DATASET_CSV.getParent());
            StringBuilder sb = new StringBuilder(CSV_HEADER);
            for (SpectrometerData d : realData) {
                sb.append(String.format("%s,%d,%d,%d,%d,%d,%.2f,%s\n",
                    safe(d.getDeviceId()), d.getTimestamp(),
                    d.getOpticalR(), d.getOpticalG(), d.getOpticalB(),
                    d.getConductivityMv(),
                    d.getPurityPercentage() != null ? d.getPurityPercentage() : 0.0,
                    safe(d.getHexCode())));
            }
            Files.writeString(DATASET_CSV, sb.toString());
            logger.info("Exported {} real scans to {}", realData.size(), DATASET_CSV);

            return ResponseEntity.ok(Map.of(
                "status", "success",
                "count", realData.size(),
                "path", DATASET_CSV.toString()
            ));
        } catch (IOException e) {
            logger.error("CSV export failed", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "status", "error", "message", e.getMessage()));
        }
    }

    // ── AI Training Proxy ─────────────────────────────────────────────────────────

    @PostMapping("/train")
    public ResponseEntity<String> triggerTraining() {
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                aiServerUrl + "/api/ai/train", null, String.class);
            logger.info("AI training triggered. Status: {}", response.getStatusCode());
            return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
        } catch (ResourceAccessException e) {
            logger.warn("AI server unreachable: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("AI Server offline. Start: python3 ai_training/ai_server.py");
        } catch (Exception e) {
            logger.error("Training proxy failed", e);
            return ResponseEntity.internalServerError().body("Proxy error: " + e.getMessage());
        }
    }

    private String safe(String s) { return s != null ? s.replace(",", ";") : ""; }
}

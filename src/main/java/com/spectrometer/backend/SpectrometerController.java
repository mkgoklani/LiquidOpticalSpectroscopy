package com.spectrometer.backend;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/scan")
public class SpectrometerController {

    @Autowired
    private SpectrometerDataRepository repository;

    @Autowired
    private IngestionService ingestionService;

    @PostMapping("/manual")
    public ResponseEntity<String> saveManualScan(@RequestBody SpectrometerDataDto dto) {
        // We reuse the process logic but directly pass the DTO
        ingestionService.processManual(dto);
        return ResponseEntity.ok("Manual scan saved");
    }

    @GetMapping("/latest")
    public ResponseEntity<SpectrometerData> getLatestScan() {
        return repository.findTopByOrderByTimestampDesc()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    @GetMapping("/history")
    public ResponseEntity<List<SpectrometerData>> getHistory() {
        List<SpectrometerData> history = repository.findTop100ByOrderByTimestampDesc();
        return ResponseEntity.ok(history);
    }
}

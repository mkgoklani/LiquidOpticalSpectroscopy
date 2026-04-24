package com.spectrometer.backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * On application startup, if the H2 database has NO real scan data
 * AND dataset/training_data.csv exists (e.g. after a git pull on a new machine),
 * this automatically bulk-imports the CSV so the database is ready immediately.
 *
 * This means: train once → sync dataset to Git → git pull on any machine → data is there.
 */
@Component
public class DatasetInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DatasetInitializer.class);

    @Autowired
    private SpectrometerDataRepository repository;

    @Override
    public void run(String... args) {
        // Only auto-import when the DB has no real data at all
        if (repository.countByIsSimulatedFalse() > 0) {
            logger.info("[DATASET] {} real scans already in database. Skipping auto-import.",
                    repository.countByIsSimulatedFalse());
            return;
        }

        Path csvPath = SpectrometerController.DATASET_CSV;
        if (!Files.exists(csvPath)) {
            logger.info("[DATASET] No dataset/training_data.csv found. Starting with empty database.");
            return;
        }

        logger.info("[DATASET] Empty database detected. Auto-importing from {}...", csvPath);

        try {
            List<String> lines = Files.readAllLines(csvPath);
            if (lines.size() <= 1) {
                logger.info("[DATASET] CSV has no data rows (header only). Nothing to import.");
                return;
            }

            int imported = 0, skipped = 0;
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;
                try {
                    // Format: deviceId,timestamp,opticalR,opticalG,opticalB,conductivityMv,purityPercentage,hexCode
                    String[] p = line.split(",", 8);
                    if (p.length < 8) { skipped++; continue; }

                    SpectrometerData data = new SpectrometerData();
                    data.setDeviceId(p[0]);
                    data.setTimestamp(Long.parseLong(p[1].trim()));
                    data.setOpticalR(Integer.parseInt(p[2].trim()));
                    data.setOpticalG(Integer.parseInt(p[3].trim()));
                    data.setOpticalB(Integer.parseInt(p[4].trim()));
                    data.setConductivityMv(Integer.parseInt(p[5].trim()));
                    data.setPurityPercentage(Double.parseDouble(p[6].trim()));
                    data.setHexCode(p[7].trim());
                    data.setIsSimulated(false); // CSV only contains real scans

                    repository.save(data);
                    imported++;
                } catch (Exception e) {
                    logger.warn("[DATASET] Skipping malformed row {}: {} ({})", i, line, e.getMessage());
                    skipped++;
                }
            }
            logger.info("[DATASET] ✓ Auto-import complete. Imported: {} | Skipped: {}", imported, skipped);

        } catch (Exception e) {
            logger.error("[DATASET] Failed to read/import CSV: {}", e.getMessage(), e);
        }
    }
}

package com.spectrometer.backend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for IngestionService — pure Mockito, no Spring context required.
 */
@ExtendWith(MockitoExtension.class)
class IngestionServiceTest {

    @Mock
    private SpectrometerDataRepository repository;

    @InjectMocks
    private IngestionService ingestionService;

    // ── helpers ────────────────────────────────────────────────────────────────

    private SpectrometerDataDto validDto() {
        SpectrometerDataDto dto = new SpectrometerDataDto();
        dto.deviceId       = "manual-input";
        dto.opticalR       = 200;
        dto.opticalG       = 50;
        dto.opticalB       = 50;
        dto.conductivityMv = 300;
        dto.timestamp      = System.currentTimeMillis();
        return dto;
    }

    /** Stubs repository.save() to capture and return the entity passed in. */
    private SpectrometerData captureAndReturn() {
        final SpectrometerData[] captured = {null};
        doAnswer(inv -> {
            captured[0] = inv.getArgument(0);
            return captured[0];
        }).when(repository).save(any(SpectrometerData.class));
        // Return a holder that lets tests access the saved entity after the call
        return captured[0]; // will be null here; tests read it after invoke
    }

    // ── happy-path tests ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Valid manual scan is persisted to repository once")
    void validManualScan_isSaved() {
        doAnswer(inv -> inv.getArgument(0)).when(repository).save(any());
        ingestionService.processManual(validDto());
        verify(repository, times(1)).save(any(SpectrometerData.class));
    }

    @Test
    @DisplayName("HEX code is correctly transpiled: R=255,G=0,B=0 → #FF0000")
    void hexCode_correctlyTranspiled() {
        SpectrometerDataDto dto = validDto();
        dto.opticalR = 255; dto.opticalG = 0; dto.opticalB = 0;

        final SpectrometerData[] saved = {null};
        doAnswer(inv -> { saved[0] = inv.getArgument(0); return saved[0]; })
                .when(repository).save(any());

        ingestionService.processManual(dto);

        assertNotNull(saved[0], "Entity should have been saved");
        assertEquals("#FF0000", saved[0].getHexCode());
    }

    @Test
    @DisplayName("Sensor fault (0,0,0 RGB) produces purityPercentage = 0.0")
    void sensorFault_purityIsZero() {
        SpectrometerDataDto dto = validDto();
        dto.opticalR = 0; dto.opticalG = 0; dto.opticalB = 0;

        final SpectrometerData[] saved = {null};
        doAnswer(inv -> { saved[0] = inv.getArgument(0); return saved[0]; })
                .when(repository).save(any());

        ingestionService.processManual(dto);

        assertNotNull(saved[0]);
        assertEquals(0.0, saved[0].getPurityPercentage(), 0.001);
    }

    @Test
    @DisplayName("Out-of-range RGB values are clamped to [0, 255]")
    void outOfRangeRgb_isClamped() {
        SpectrometerDataDto dto = validDto();
        dto.opticalR = 999; dto.opticalG = -50; dto.opticalB = 300;

        final SpectrometerData[] saved = {null};
        doAnswer(inv -> { saved[0] = inv.getArgument(0); return saved[0]; })
                .when(repository).save(any());

        ingestionService.processManual(dto);

        assertNotNull(saved[0]);
        assertEquals(255, saved[0].getOpticalR(), "R=999 should clamp to 255");
        assertEquals(0,   saved[0].getOpticalG(), "G=-50 should clamp to 0");
        assertEquals(255, saved[0].getOpticalB(), "B=300 should clamp to 255");
    }

    @Test
    @DisplayName("Null RGB fields are defaulted to 0 before persistence")
    void nullRgbFields_defaultToZero() {
        SpectrometerDataDto dto = validDto();
        dto.opticalR = null; dto.opticalG = null; dto.opticalB = null;

        final SpectrometerData[] saved = {null};
        doAnswer(inv -> { saved[0] = inv.getArgument(0); return saved[0]; })
                .when(repository).save(any());

        ingestionService.processManual(dto);

        assertNotNull(saved[0]);
        assertEquals(0, saved[0].getOpticalR());
        assertEquals(0, saved[0].getOpticalG());
        assertEquals(0, saved[0].getOpticalB());
    }

    // ── hardware lockout tests ─────────────────────────────────────────────────

    @Test
    @DisplayName("Simulated scan is accepted when no hardware has sent data")
    void simulated_accepted_whenNoHardware() {
        doAnswer(inv -> inv.getArgument(0)).when(repository).save(any());
        assertDoesNotThrow(() -> ingestionService.processManual(validDto()));
        verify(repository).save(any());
    }

    @Test
    @DisplayName("Simulated scan is rejected within the 10s hardware lockout window")
    void simulated_rejected_duringHardwareLockout() {
        // Trigger lockout via MQTT path
        doAnswer(inv -> inv.getArgument(0)).when(repository).save(any());
        String mqttJson = "{\"opticalR\":200,\"opticalG\":50,\"opticalB\":50,\"conductivityMv\":300}";
        ingestionService.processMessage(mqttJson);

        // Simulated device must now be rejected
        SpectrometerDataDto sim = validDto();
        sim.deviceId = "simulated-node";

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ingestionService.processManual(sim));
        assertEquals("HARDWARE_ACTIVE_LOCKOUT", ex.getMessage());
    }
}

package com.spectrometer.backend;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Accepts BOTH camelCase (frontend JS) and snake_case (MQTT firmware).
 * isSimulated = true marks data as synthetic — excluded from AI training.
 */
public class SpectrometerDataDto {

    @JsonProperty("deviceId")
    @JsonAlias("device_id")
    public String deviceId;

    @JsonProperty("timestamp")
    public Long timestamp;

    @JsonProperty("opticalR")
    @JsonAlias("optical_r")
    public Integer opticalR;

    @JsonProperty("opticalG")
    @JsonAlias("optical_g")
    public Integer opticalG;

    @JsonProperty("opticalB")
    @JsonAlias("optical_b")
    public Integer opticalB;

    @JsonProperty("conductivityMv")
    @JsonAlias("conductivity_mv")
    public Integer conductivityMv;

    /** Explicit simulation flag. If true, this reading is stored but NEVER used for AI training. */
    @JsonProperty("isSimulated")
    public Boolean isSimulated;
}

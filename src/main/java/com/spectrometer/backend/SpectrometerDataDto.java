package com.spectrometer.backend;

import com.fasterxml.jackson.annotation.JsonProperty;

// Helper to deserialize the exact JSON contract given the snake_case keys
public class SpectrometerDataDto {
    @JsonProperty("device_id")
    public String deviceId;
    @JsonProperty("timestamp")
    public Long timestamp;
    @JsonProperty("optical_r")
    public Integer opticalR;
    @JsonProperty("optical_g")
    public Integer opticalG;
    @JsonProperty("optical_b")
    public Integer opticalB;
    @JsonProperty("conductivity_mv")
    public Integer conductivityMv;
}

package com.spectrometer.backend;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Data Transfer Object for incoming scan payloads.
 * Accepts BOTH camelCase (from frontend JS) and snake_case (from MQTT/firmware).
 * The @JsonAlias handles the dual-format contract so the API is more resilient.
 */
public class SpectrometerDataDto {

    @JsonProperty("deviceId")
    @com.fasterxml.jackson.annotation.JsonAlias("device_id")
    public String deviceId;

    @JsonProperty("timestamp")
    public Long timestamp;

    @JsonProperty("opticalR")
    @com.fasterxml.jackson.annotation.JsonAlias("optical_r")
    public Integer opticalR;

    @JsonProperty("opticalG")
    @com.fasterxml.jackson.annotation.JsonAlias("optical_g")
    public Integer opticalG;

    @JsonProperty("opticalB")
    @com.fasterxml.jackson.annotation.JsonAlias("optical_b")
    public Integer opticalB;

    @JsonProperty("conductivityMv")
    @com.fasterxml.jackson.annotation.JsonAlias("conductivity_mv")
    public Integer conductivityMv;
}

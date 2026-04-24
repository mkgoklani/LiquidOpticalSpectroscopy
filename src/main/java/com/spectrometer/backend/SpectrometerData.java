package com.spectrometer.backend;

import jakarta.persistence.*;

@Entity
@Table(name = "spectrometer_data")
public class SpectrometerData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String deviceId;
    private Long timestamp;
    private Integer opticalR;
    private Integer opticalG;
    private Integer opticalB;
    private Integer conductivityMv;
    private Double purityPercentage; // Used later by AI models
    private String hexCode;          // Bonus Feature: Digital Eyedropper
    private Boolean isSimulated = false; // true = synthetic/simulated data, excluded from AI training

    // Constructors
    public SpectrometerData() {}

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public Long getTimestamp() { return timestamp; }
    public void setTimestamp(Long timestamp) { this.timestamp = timestamp; }
    public Integer getOpticalR() { return opticalR; }
    public void setOpticalR(Integer opticalR) { this.opticalR = opticalR; }
    public Integer getOpticalG() { return opticalG; }
    public void setOpticalG(Integer opticalG) { this.opticalG = opticalG; }
    public Integer getOpticalB() { return opticalB; }
    public void setOpticalB(Integer opticalB) { this.opticalB = opticalB; }
    public Integer getConductivityMv() { return conductivityMv; }
    public void setConductivityMv(Integer conductivityMv) { this.conductivityMv = conductivityMv; }
    public Double getPurityPercentage() { return purityPercentage; }
    public void setPurityPercentage(Double purityPercentage) { this.purityPercentage = purityPercentage; }
    public String getHexCode() { return hexCode; }
    public void setHexCode(String hexCode) { this.hexCode = hexCode; }
    public Boolean getIsSimulated() { return isSimulated != null && isSimulated; }
    public void setIsSimulated(Boolean isSimulated) { this.isSimulated = isSimulated; }
}

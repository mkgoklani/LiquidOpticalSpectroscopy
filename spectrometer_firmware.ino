// ============================================================
//  MILAAWAT DETECTOR — Edge Firmware v1.0
//  Hardware: ESP8266 NodeMCU + TCS3200 + White LED
//  Role: Hardware & Data Operations
//  Output: JSON over MQTT (Wi-Fi)
// ============================================================

#include <ESP8266WiFi.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>

// ------------------------------------------------------------
// 1. CONFIGURATION — Edit these before flashing
// ------------------------------------------------------------

// Wi-Fi credentials
const char* WIFI_SSID     = "YOUR_WIFI_SSID";
const char* WIFI_PASSWORD = "YOUR_WIFI_PASSWORD";

// MQTT Broker (your laptop's local IP address)
// Find it on Windows: ipconfig | on Linux/Mac: ifconfig
const char* MQTT_BROKER   = "192.168.1.100";   // <-- CHANGE THIS
const int   MQTT_PORT     = 1883;
const char* MQTT_TOPIC    = "spectrometer/data";
const char* DEVICE_ID     = "MILAAWAT-NODE-01";

// Scan interval (milliseconds)
const unsigned long SCAN_INTERVAL = 2000;  // 1 scan every 2 seconds

// ------------------------------------------------------------
// 2. TCS3200 PIN MAPPING (NodeMCU GPIO)
// ------------------------------------------------------------
//
//  TCS3200 Pin  |  NodeMCU Pin  |  Purpose
//  -------------|---------------|----------------------------
//  S0           |  D5 (GPIO14)  |  Frequency scaling bit 0
//  S1           |  D6 (GPIO12)  |  Frequency scaling bit 1
//  S2           |  D7 (GPIO13)  |  Filter select bit 0
//  S3           |  D8 (GPIO15)  |  Filter select bit 1
//  OUT          |  D4 (GPIO2)   |  Frequency output (pulse)
//  OE           |  GND          |  Output Enable (always on)
//  VCC          |  3.3V         |  Power
//  GND          |  GND          |  Ground
//
//  White LED    |  D1 (GPIO5)   |  Controlled illumination
//  LED GND      |  GND via 100Ω resistor
//
// NOTE: TCS3200 built-in LEDs (pins LED1/LED2) — tape them off
//       or connect to GND. We use our own white LED for control.

#define TCS_S0   14  // D5
#define TCS_S1   12  // D6
#define TCS_S2   13  // D7
#define TCS_S3   15  // D8
#define TCS_OUT   2  // D4
#define LED_PIN   5  // D1

// ------------------------------------------------------------
// 3. GLOBAL OBJECTS
// ------------------------------------------------------------

WiFiClient   wifiClient;
PubSubClient mqttClient(wifiClient);

unsigned long lastScanTime = 0;
int           sampleID     = 0;

// ------------------------------------------------------------
// 4. SETUP
// ------------------------------------------------------------

void setup() {
  Serial.begin(115200);
  delay(200);
  Serial.println("\n=== MILAAWAT DETECTOR BOOTING ===");

  // TCS3200 pin modes
  pinMode(TCS_S0,  OUTPUT);
  pinMode(TCS_S1,  OUTPUT);
  pinMode(TCS_S2,  OUTPUT);
  pinMode(TCS_S3,  OUTPUT);
  pinMode(TCS_OUT, INPUT);

  // White LED pin
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, LOW);  // LED off by default

  // Set TCS3200 output frequency scaling to 20%
  // S0=HIGH, S1=LOW → 20% scaling (good balance of speed & accuracy)
  digitalWrite(TCS_S0, HIGH);
  digitalWrite(TCS_S1, LOW);

  connectWiFi();

  mqttClient.setServer(MQTT_BROKER, MQTT_PORT);
  connectMQTT();

  Serial.println("=== READY. Scanning every " + String(SCAN_INTERVAL/1000) + "s ===\n");
}

// ------------------------------------------------------------
// 5. MAIN LOOP
// ------------------------------------------------------------

void loop() {
  // Keep MQTT connection alive
  if (!mqttClient.connected()) {
    connectMQTT();
  }
  mqttClient.loop();

  // Timed scan
  unsigned long now = millis();
  if (now - lastScanTime >= SCAN_INTERVAL) {
    lastScanTime = now;
    performScanAndPublish();
  }
}

// ------------------------------------------------------------
// 6. SCAN & PUBLISH
// ------------------------------------------------------------

void performScanAndPublish() {
  sampleID++;

  // Step 1: Turn on LED — allow 50ms for light to stabilise
  digitalWrite(LED_PIN, HIGH);
  delay(50);

  // Step 2: Read all four channels from TCS3200
  long freqR = readChannel(LOW,  LOW);   // Red filter
  long freqG = readChannel(HIGH, HIGH);  // Green filter
  long freqB = readChannel(LOW,  HIGH);  // Blue filter
  long freqC = readChannel(HIGH, LOW);   // Clear (no filter)

  // Step 3: Turn off LED immediately after reading
  digitalWrite(LED_PIN, LOW);

  // Step 4: Guard against zero-division
  if (freqC == 0) freqC = 1;

  // Step 5: Normalise to clear channel (removes ambient light bias)
  float normR = (float)freqR / freqC;
  float normG = (float)freqG / freqC;
  float normB = (float)freqB / freqC;

  // Step 6: Compute simple colour ratios (extra KNN features)
  float ratioRG = (freqG > 0) ? (float)freqR / freqG : 0;
  float ratioRB = (freqB > 0) ? (float)freqR / freqB : 0;

  // Step 7: Build JSON Data Contract
  // This exact structure is what the Python KNN model expects.
  StaticJsonDocument<256> doc;

  doc["device_id"]   = DEVICE_ID;
  doc["sample_id"]   = sampleID;
  doc["timestamp_ms"]= millis();

  // Raw optical frequencies (Hz — pulses per second)
  JsonObject raw = doc.createNestedObject("raw_optical");
  raw["freq_red"]   = freqR;
  raw["freq_green"] = freqG;
  raw["freq_blue"]  = freqB;
  raw["freq_clear"] = freqC;

  // Normalised values (primary KNN features)
  JsonObject norm = doc.createNestedObject("normalised");
  norm["norm_r"] = serialise(normR);
  norm["norm_g"] = serialise(normG);
  norm["norm_b"] = serialise(normB);

  // Ratio features (secondary KNN features)
  JsonObject ratios = doc.createNestedObject("ratios");
  ratios["ratio_rg"] = serialise(ratioRG);
  ratios["ratio_rb"] = serialise(ratioRB);

  // Placeholder for ground truth label (filled manually during data collection)
  doc["adulteration_pct"] = -1;  // -1 = unknown / live inference mode

  // Step 8: Serialise and publish
  char payload[256];
  serializeJson(doc, payload);

  bool ok = mqttClient.publish(MQTT_TOPIC, payload);

  // Step 9: Echo to Serial monitor for debugging
  Serial.print("[Sample #" + String(sampleID) + "] ");
  Serial.print("R=" + String(freqR) + " G=" + String(freqG) +
               " B=" + String(freqB) + " C=" + String(freqC));
  Serial.println(ok ? "  → MQTT OK" : "  → MQTT FAIL");
}

// ------------------------------------------------------------
// 7. TCS3200 CHANNEL READER
// ------------------------------------------------------------
// Returns pulse frequency in Hz by counting pulses over 100ms.
// More pulses = more light of that colour passing through.

long readChannel(uint8_t s2, uint8_t s3) {
  digitalWrite(TCS_S2, s2);
  digitalWrite(TCS_S3, s3);
  delay(10);  // Let filter settle

  // Count rising-edge pulses for 100ms
  long count = 0;
  unsigned long start = millis();
  while (millis() - start < 100) {
    if (digitalRead(TCS_OUT) == HIGH) {
      count++;
      while (digitalRead(TCS_OUT) == HIGH);  // Wait for LOW
    }
  }
  // Convert to Hz (count per 100ms → per second)
  return count * 10;
}

// ------------------------------------------------------------
// 8. HELPERS
// ------------------------------------------------------------

// Round float to 4 decimal places for clean JSON
float serialise(float val) {
  return round(val * 10000.0) / 10000.0;
}

void connectWiFi() {
  Serial.print("Connecting to Wi-Fi: " + String(WIFI_SSID));
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.println("\nWi-Fi connected. IP: " + WiFi.localIP().toString());
}

void connectMQTT() {
  Serial.print("Connecting to MQTT broker at " + String(MQTT_BROKER) + "...");
  while (!mqttClient.connected()) {
    if (mqttClient.connect(DEVICE_ID)) {
      Serial.println(" connected.");
    } else {
      Serial.print(" failed (rc=" + String(mqttClient.state()) + "). Retrying in 3s...");
      delay(3000);
    }
  }
}

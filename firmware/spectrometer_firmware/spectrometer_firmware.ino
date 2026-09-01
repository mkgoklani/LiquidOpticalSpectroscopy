// ============================================================
//  MILAAWAT DETECTOR — Edge Firmware v1.1
//  Hardware: ESP8266 NodeMCU + TCS3200 + White LED
//  Role: Hardware & Data Operations
//  Output: JSON over MQTT (Wi-Fi)
// ============================================================

#include <ESP8266WiFi.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>
#include <time.h>      // For NTP time

// ------------------------------------------------------------
// 1. CONFIGURATION
// ------------------------------------------------------------

// Wi-Fi credentials
const char* WIFI_SSID     = "HOD-IT";
const char* WIFI_PASSWORD = "Bit1234@";

// MQTT Broker
const char* MQTT_BROKER   = "broker.emqx.io";   // <-- Public EMQX Broker
const int   MQTT_PORT     = 1883;
const char* MQTT_TOPIC    = "iot/spectrometer/raw";
const char* DEVICE_ID     = "MILAAWAT-NODE-01";

// Scan interval (milliseconds)
const unsigned long SCAN_INTERVAL = 200;

// RGB Sensor Calibration Parameters
// Black references (measured under total darkness - black paper & lights off)
const long CAL_BLACK_R = 50;
const long CAL_BLACK_G = 50;
const long CAL_BLACK_B = 50;

// White references (measured under reference white - white paper)
// We set these as defaults first; we'll update them once measured!
long calWhiteR = 5000;
long calWhiteG = 5000;
long calWhiteB = 5000;

// NTP settings
const char* NTP_SERVER_1 = "pool.ntp.org";
const char* NTP_SERVER_2 = "time.nist.gov";
const char* NTP_SERVER_3 = "time.google.com";

// ------------------------------------------------------------
// 2. PIN MAPPING (Updated to match physical wiring)
// ------------------------------------------------------------
#define TCS_S0    5  // D1
#define TCS_S1    4  // D2
#define TCS_S2    0  // D3
#define TCS_S3    2  // D4
#define TCS_OUT  14  // D5
#define LED_PIN  12  // D6
#define ZAP_PIN  13  // D7 (High-Speed Zap Gate)
#define COND_PIN A0  // A0 (Analog Milk Impedance)

// ------------------------------------------------------------
// 3. GLOBAL OBJECTS
// ------------------------------------------------------------
WiFiClient   wifiClient;
PubSubClient mqttClient(wifiClient);

unsigned long lastScanTime = 0;
unsigned long lastReconnectAttempt = 0;
int           sampleID     = 0;
bool          timeSynchronized = false;

// ------------------------------------------------------------
// 4. SETUP
// ------------------------------------------------------------
void setup() {
  Serial.begin(115200);
  delay(200);
  Serial.println("\n=== MILAAWAT DETECTOR BOOTING ===");

  // TCS3200 pins
  pinMode(TCS_S0,  OUTPUT);
  pinMode(TCS_S1,  OUTPUT);
  pinMode(TCS_S2,  OUTPUT);
  pinMode(TCS_S3,  OUTPUT);
  pinMode(TCS_OUT, INPUT);

  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, LOW); 

  // Impedance & Zap Gate pins
  pinMode(ZAP_PIN, OUTPUT);
  digitalWrite(ZAP_PIN, LOW); 
  pinMode(COND_PIN, INPUT); 

  // 20% scaling
  digitalWrite(TCS_S0, HIGH);
  digitalWrite(TCS_S1, LOW);

  connectWiFi();
  syncTime();

  mqttClient.setServer(MQTT_BROKER, MQTT_PORT);
  
  // Connect MQTT once blocking in setup
  connectMQTT_Blocking();
  
  // Enable hardware watchdog
  ESP.wdtEnable(5000); // 5 seconds watchdog

  Serial.println("=== READY. Scanning every " + String(SCAN_INTERVAL/1000) + "s ===\n");
}

// ------------------------------------------------------------
// 5. MAIN LOOP
// ------------------------------------------------------------
void loop() {
  ESP.wdtFeed(); // Feed the watchdog

  // Check WiFi
  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("WiFi lost. Reconnecting...");
    connectWiFi(); 
    syncTime(); // Resync time if needed
  }

  // Non-blocking MQTT reconnect
  if (!mqttClient.connected()) {
    long now = millis();
    if (now - lastReconnectAttempt > 5000) { // Try every 5 seconds
      lastReconnectAttempt = now;
      if (reconnectMQTT()) {
        lastReconnectAttempt = 0;
      }
    }
  } else {
    mqttClient.loop();
  }

  // Timed scan
  unsigned long now = millis();
  if (now - lastScanTime >= SCAN_INTERVAL) {
    lastScanTime = now;
    if (mqttClient.connected()) {
      performScanAndPublish();
    } else {
      Serial.println("Skipping scan, MQTT not connected.");
    }
  }
}

// ------------------------------------------------------------
// 6. SCAN & PUBLISH
// ------------------------------------------------------------
void performScanAndPublish() {
  sampleID++;

  // 1. Run Conductivity/Impedance Scan (Zap Gate)
  digitalWrite(ZAP_PIN, HIGH);
  delay(10); // Allow transient response to settle
  int rawAnalog = analogRead(COND_PIN);
  digitalWrite(ZAP_PIN, LOW); // Turn off immediately to prevent electrode polarization/corrosion
  
  // Software noise-floor filter to clamp floating pin noise (when open-circuit/on wood)
  int conductivityMv = 0;
  if (rawAnalog > 20) {
    conductivityMv = (int)((rawAnalog * 3300.0) / 1023.0);
  }

  // 2. Run Optical Color Scan
  digitalWrite(LED_PIN, HIGH);
  delay(10); // Allow stabilization

  long freqR = readChannel(LOW,  LOW);   
  long freqG = readChannel(HIGH, HIGH);  
  long freqB = readChannel(LOW,  HIGH);  
  long freqC = readChannel(HIGH, LOW);   

  digitalWrite(LED_PIN, LOW);

  // Map raw sensor frequencies to 0-255 RGB range using calibration parameters
  int rVal = map(freqR, CAL_BLACK_R, calWhiteR, 0, 255);
  int gVal = map(freqG, CAL_BLACK_G, calWhiteG, 0, 255);
  int bVal = map(freqB, CAL_BLACK_B, calWhiteB, 0, 255);

  // Build JSON
  StaticJsonDocument<256> doc;
  
  doc["device_id"] = DEVICE_ID;
  
  // Use Unix timestamp in milliseconds
  time_t nowTime;
  time(&nowTime);
  // Add fallback just in case NTP failed to prevent extremely weird values
  if (timeSynchronized && nowTime > 1000000000) {
    doc["timestamp"] = (long long)nowTime * 1000;
  } else {
    // If we have no epoch, fall back to uptime, the backend gracefully handles missing/bad timestamp
    doc["timestamp"] = millis(); 
  }
  
  doc["optical_r"] = constrain(rVal, 0, 255);
  doc["optical_g"] = constrain(gVal, 0, 255);
  doc["optical_b"] = constrain(bVal, 0, 255);
  doc["conductivity_mv"] = conductivityMv; 
  doc["isSimulated"] = false;

  char payload[256];
  size_t bytesWritten = serializeJson(doc, payload);
  
  if (bytesWritten == 0) {
    Serial.println("Error: Failed to serialize JSON.");
    return;
  }

  bool ok = mqttClient.publish(MQTT_TOPIC, payload);

  Serial.print("[Sample #" + String(sampleID) + "] ");
  Serial.print("R=" + String(freqR) + " G=" + String(freqG) +
               " B=" + String(freqB) + " C=" + String(freqC));
  Serial.println(ok ? "  → MQTT OK" : "  → MQTT FAIL");
}

// ------------------------------------------------------------
// 7. SENSOR READING
// ------------------------------------------------------------
long readChannel(uint8_t s2, uint8_t s3) {
  digitalWrite(TCS_S2, s2);
  digitalWrite(TCS_S3, s3);
  delay(5); 

  long count = 0;
  unsigned long start = millis();
  // 20ms reading window
  while (millis() - start < 20) {
    ESP.wdtFeed(); // Keep watchdog happy during while loop
    
    // Timeout check for digitalRead block: prevent hanging if sensor gets disconnected
    if (digitalRead(TCS_OUT) == HIGH) {
      count++;
      unsigned long waitLowStart = millis();
      while (digitalRead(TCS_OUT) == HIGH) {
         if (millis() - waitLowStart > 5) break; // Timeout if stuck HIGH for 5ms
         ESP.wdtFeed();
      }
    }
  }
  return count * 50; // Convert to Hz
}

// ------------------------------------------------------------
// 8. HELPERS
// ------------------------------------------------------------
void connectWiFi() {
  Serial.print("Connecting to Wi-Fi: " + String(WIFI_SSID));
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  int attempts = 0;
  while (WiFi.status() != WL_CONNECTED && attempts < 20) {
    delay(500);
    Serial.print(".");
    attempts++;
    ESP.wdtFeed();
  }
  if (WiFi.status() == WL_CONNECTED) {
    Serial.println("\nWi-Fi connected. IP: " + WiFi.localIP().toString());
  } else {
    Serial.println("\nWi-Fi connection failed.");
  }
}

void syncTime() {
  Serial.print("Syncing time via NTP");
  configTime(0, 0, NTP_SERVER_1, NTP_SERVER_2, NTP_SERVER_3);
  time_t now = time(nullptr);
  int attempts = 0;
  // Wait for time to be set, Jan 1, 2024 epoch approx is > 1.7e9
  while (now < 8 * 3600 * 2 && attempts < 20) { 
    delay(500);
    Serial.print(".");
    now = time(nullptr);
    attempts++;
    ESP.wdtFeed();
  }
  if (now > 8 * 3600 * 2) {
    Serial.println("\nTime synchronized.");
    timeSynchronized = true;
  } else {
    Serial.println("\nNTP sync failed.");
  }
}

void connectMQTT_Blocking() {
  Serial.print("Connecting to MQTT broker at " + String(MQTT_BROKER) + "...");
  while (!mqttClient.connected()) {
    if (mqttClient.connect(DEVICE_ID)) {
      Serial.println(" connected.");
    } else {
      Serial.print(" failed (rc=" + String(mqttClient.state()) + "). Retrying in 3s...");
      delay(3000);
      ESP.wdtFeed();
    }
  }
}

bool reconnectMQTT() {
  Serial.print("Attempting MQTT reconnect...");
  if (mqttClient.connect(DEVICE_ID)) {
    Serial.println(" connected.");
    return true;
  } else {
    Serial.println(" failed, rc=" + String(mqttClient.state()));
    return false;
  }
}

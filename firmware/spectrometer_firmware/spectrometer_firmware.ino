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
const char* WIFI_SSID     = "YOUR_WIFI_SSID";
const char* WIFI_PASSWORD = "YOUR_WIFI_PASSWORD";

// MQTT Broker
const char* MQTT_BROKER   = "192.168.1.100";   // <-- CHANGE THIS
const int   MQTT_PORT     = 1883;
const char* MQTT_TOPIC    = "spectrometer/data";
const char* DEVICE_ID     = "MILAAWAT-NODE-01";

// Scan interval (milliseconds)
const unsigned long SCAN_INTERVAL = 2000;

// NTP settings
const char* NTP_SERVER_1 = "pool.ntp.org";
const char* NTP_SERVER_2 = "time.nist.gov";
const char* NTP_SERVER_3 = "time.google.com";

// ------------------------------------------------------------
// 2. PIN MAPPING
// ------------------------------------------------------------
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

  digitalWrite(LED_PIN, HIGH);
  delay(50); // Allow stabilization

  long freqR = readChannel(LOW,  LOW);   
  long freqG = readChannel(HIGH, HIGH);  
  long freqB = readChannel(LOW,  HIGH);  
  long freqC = readChannel(HIGH, LOW);   

  digitalWrite(LED_PIN, LOW);

  // Edge cases: avoid zero division, handle sensor blockage/failure
  if (freqC == 0) freqC = 1;

  float normR = (float)freqR / freqC;
  float normG = (float)freqG / freqC;
  float normB = (float)freqB / freqC;

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
  
  doc["optical_r"] = constrain((int)(normR * 255.0), 0, 255);
  doc["optical_g"] = constrain((int)(normG * 255.0), 0, 255);
  doc["optical_b"] = constrain((int)(normB * 255.0), 0, 255);
  doc["conductivity_mv"] = 0; 
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
  delay(10); 

  long count = 0;
  unsigned long start = millis();
  // 100ms reading window
  while (millis() - start < 100) {
    ESP.wdtFeed(); // Keep watchdog happy during while loop
    
    // Timeout check for digitalRead block: prevent hanging if sensor gets disconnected
    if (digitalRead(TCS_OUT) == HIGH) {
      count++;
      unsigned long waitLowStart = millis();
      while (digitalRead(TCS_OUT) == HIGH) {
         if (millis() - waitLowStart > 10) break; // Timeout if stuck HIGH for 10ms
         ESP.wdtFeed();
      }
    }
  }
  return count * 10; // Convert to Hz
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

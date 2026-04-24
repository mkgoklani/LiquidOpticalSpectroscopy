<div align="center">

# 👻 Ghost Rider — Liquid Optical Spectrometer

**A full-stack IoT platform for real-time liquid purity detection using optical spectroscopy and AI-driven inference.**

[![Java](https://img.shields.io/badge/Java-21-orange?style=flat-square&logo=openjdk)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.2.5-brightgreen?style=flat-square&logo=spring)](https://spring.io/projects/spring-boot)
[![Python](https://img.shields.io/badge/Python-3.9+-blue?style=flat-square&logo=python)](https://www.python.org/)
[![Flask](https://img.shields.io/badge/Flask-3.0-lightgrey?style=flat-square&logo=flask)](https://flask.palletsprojects.com/)
[![MQTT](https://img.shields.io/badge/MQTT-broker.emqx.io-purple?style=flat-square)](https://emqx.io/)
[![License](https://img.shields.io/badge/License-MIT-yellow?style=flat-square)](LICENSE)

*BIT Durg — IoT & AI Systems Engineering Project*

</div>

---

## 📋 Table of Contents

- [Overview](#-overview)
- [System Architecture](#-system-architecture)
- [Tech Stack](#-tech-stack)
- [Project Structure](#-project-structure)
- [Quick Start](#-quick-start)
- [Full Workflow](#-full-workflow)
  - [Phase 0: One-Time Setup](#phase-0--one-time-setup)
  - [Phase 1: Build Dataset](#phase-1--build-dataset)
  - [Phase 2: Train AI Model](#phase-2--train-ai-model)
  - [Phase 3: Demonstration](#phase-3--demonstration)
- [API Reference](#-api-reference)
- [Features](#-features)
- [Data Architecture](#-data-architecture)
- [Cross-Machine Portability](#-cross-machine-portability)
- [Team](#-team)

---

## 🔬 Overview

The **Ghost Rider Liquid Optical Spectrometer** is a hardware-software-AI system designed to detect and quantify liquid adulteration in real time. A custom ESP8266-based sensor node measures optical RGB values (via TCS3200 colour sensor) and electrical conductivity, streams the telemetry via MQTT, and a machine learning model predicts purity percentage with up to **K-optimised KNN regression**.

### Key Capabilities

| Capability | Description |
|---|---|
| **Real-time telemetry** | Hardware streams data via MQTT at 1Hz to the backend |
| **AI purity prediction** | KNN regression with automatic hyperparameter tuning (K=1–15) |
| **Digital eyedropper** | Repurposes the colour sensor as a HEX-code colorimeter |
| **Dataset portability** | One-click Git sync — full dataset travels with the repo to any machine |
| **Hardware lockout** | Simulated data is automatically rejected while real hardware is streaming |
| **Triple-gate Git sync** | Model weights and dataset require 3 confirmations before pushing to Git |

---

## 🏗 System Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    ESP8266 Hardware Node                         │
│         TCS3200 (RGB) + EC Sensor (Conductivity)                │
└───────────────────────┬─────────────────────────────────────────┘
                        │ MQTT  tcp://broker.emqx.io:1883
                        │ Topic: iot/spectrometer/raw
                        ▼
┌─────────────────────────────────────────────────────────────────┐
│              Java Spring Boot Backend  :8080                     │
│                                                                  │
│  ┌─────────────────┐   ┌──────────────────┐   ┌─────────────┐  │
│  │  MqttConfig     │   │  IngestionService │   │ Controller  │  │
│  │  (inbound sub)  │──▶│  isSimulated tag  │──▶│ REST API    │  │
│  └─────────────────┘   │  HW lockout 10s   │   └─────────────┘  │
│                        │  Input clamping   │                     │
│                        └────────┬──────────┘                     │
│                                 │                                │
│  ┌──────────────────────────────▼────────────────────────────┐  │
│  │  H2 File Database  (./data/spectrometer_db.mv.db)         │  │
│  │  Persistent across restarts · Separate real/sim tables    │  │
│  └──────────────────────────────┬────────────────────────────┘  │
│                                 │ /export → dataset/training_data.csv
└─────────────────────────────────┼───────────────────────────────┘
                                  │ HTTP  localhost:5001
                                  ▼
┌─────────────────────────────────────────────────────────────────┐
│              Python Flask AI Server  :5001                       │
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │  SpectrometerInference                                     │ │
│  │  • fetch /history/real → real scans ONLY                   │ │
│  │  • StandardScaler normalisation                            │ │
│  │  • KNN Regression  K=1..15  (5-fold CV R²)                 │ │
│  │  • Saves model.joblib  (auto-restored on restart)          │ │
│  │  • git push model   (–f, ignores .gitignore)               │ │
│  │  • git push dataset (calls /export then stages CSV)        │ │
│  └────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────┘
                                  │ JSON polling 1s
                                  ▼
┌─────────────────────────────────────────────────────────────────┐
│                  Frontend  (served by Spring Boot)               │
│                                                                  │
│  index.html         Live telemetry HUD · MQTT simulation        │
│  training.html      AI Training Studio · Dataset management      │
│  eyedropper.html    Digital Colorimeter · HEX transpiler        │
└─────────────────────────────────────────────────────────────────┘
```

---

## 🛠 Tech Stack

| Layer | Technology |
|---|---|
| **Hardware** | ESP8266 NodeMCU, TCS3200 Colour Sensor, EC/Conductivity Probe |
| **Messaging** | MQTT (public broker: `broker.emqx.io:1883`) |
| **Backend** | Java 21, Spring Boot 3.2.5, Spring Integration (MQTT), Spring Data JPA |
| **Database** | H2 (file-mode, persistent) · PostgreSQL-ready |
| **AI Server** | Python 3.9+, Flask 3.0, scikit-learn (KNN), pandas, joblib |
| **Frontend** | HTML5, Tailwind CSS, GSAP 3.12, Chart.js, Google Fonts |
| **Build** | Gradle 8, pip3 |
| **Version Control** | Git (automated model + dataset Git sync via triple-gate UI) |

---

## 📁 Project Structure

```
LiquidOpticalSpectroscopy/
│
├── ai_training/
│   ├── ai_server.py               # Flask AI server (port 5001)
│   ├── spectrometer_inference.py  # KNN training, prediction, Git sync
│   ├── requirements.txt           # Python dependencies
│   └── model.joblib               # Trained model (gitignored; synced via UI)
│
├── dataset/
│   └── training_data.csv          # Real scan CSV (auto-imported on startup)
│
├── src/main/
│   ├── java/com/spectrometer/backend/
│   │   ├── BackendApplication.java      # Spring Boot entry point
│   │   ├── AppConfig.java               # CORS + RestTemplate beans
│   │   ├── MqttConfig.java              # MQTT subscriber configuration
│   │   ├── IngestionService.java        # Core data processing + HW lockout
│   │   ├── DatasetInitializer.java      # Auto-import CSV on empty DB startup
│   │   ├── SpectrometerController.java  # REST API endpoints
│   │   ├── SpectrometerData.java        # JPA Entity (includes isSimulated flag)
│   │   ├── SpectrometerDataDto.java     # Request DTO (camelCase + snake_case)
│   │   └── SpectrometerDataRepository.java  # JPA queries (real/sim separated)
│   │
│   └── resources/
│       ├── application.yml         # Spring config (H2, MQTT, AI URL, CORS)
│       └── static/
│           ├── index.html          # Live telemetry dashboard
│           ├── training.html       # AI Training Studio
│           └── eyedropper.html     # Digital Colorimeter
│
├── src/test/java/com/spectrometer/backend/
│   ├── BackendApplicationTests.java  # Spring context smoke test
│   └── IngestionServiceTest.java     # 7 unit tests (Mockito)
│
├── data/                  # H2 file database (gitignored, machine-local)
├── build.gradle
├── .gitignore
└── README.md
```

---

## ⚡ Quick Start

### Prerequisites
- Java 21+
- Python 3.9+
- Git

### Launch in 3 commands

```bash
# Terminal 1 — Java Backend
./gradlew bootRun

# Terminal 2 — Python AI Server
pip3 install -r ai_training/requirements.txt
python3 ai_training/ai_server.py

# Open browser
open http://localhost:8080
```

> **H2 Console:** `http://localhost:8080/h2-console`
> JDBC URL: `jdbc:h2:file:./data/spectrometer_db`  · Username: `sa` · Password: *(blank)*

---

## 🔄 Full Workflow

### Phase 0 — One-Time Setup

```bash
git clone https://github.com/mkgoklani/LiquidOpticalSpectroscopy.git
cd LiquidOpticalSpectroscopy
pip3 install -r ai_training/requirements.txt
```

> If a `dataset/training_data.csv` with real scans already exists in the repo (committed by a teammate), the backend **auto-imports it into H2 on first startup** — no manual data collection needed.

---

### Phase 1 — Build Dataset

1. Navigate to **[http://localhost:8080/training.html](http://localhost:8080/training.html)**
2. Enter a **Liquid Name** (e.g. `Pure Water`) and **Expected Purity %** (`100`)
3. Click **Start Data Collection**
4. Click **Simulate Hardware Reading** 15–20 times per liquid type
5. Stop → Repeat for different liquid types

**Recommended liquid profiles:**

| Liquid | Approx R | Approx G | Approx B | Conductivity | Purity |
|---|---|---|---|---|---|
| Pure Water | 30–60 | 200–240 | 200–240 | 50–100 mV | 100% |
| Synthetic Blood | 200–240 | 30–60 | 30–60 | 350–450 mV | 65% |
| Saline Solution | 100–140 | 100–140 | 200–240 | 400–500 mV | 70% |
| Adulterated Milk | 220–240 | 210–230 | 180–200 | 150–250 mV | 40% |
| Distilled Solvent | 200–240 | 190–220 | 50–80 | 20–60 mV | 90% |

> ℹ️ Simulated readings are stored with `isSimulated = true` and are **never used in AI training**. The "Real DB Scans" counter (green) shows only clean data.

---

### Phase 2 — Train AI Model

1. Click **"Train AI Pipeline"** in the Training Studio
2. The Python server fetches **real scans only** (`/history/real`), tunes K from 1–15, trains, and saves `model.joblib`
3. On success, click **"Sync Model to Git"** 3 times (triple-gate) to push the model to the repository
4. Click **"Sync Dataset to Git"** 3 times to push `dataset/training_data.csv` to the repository

> **Triple-gate:** Each button requires 3 confirmations (Green → Yellow → Red → Execute) to prevent accidental pushes.

---

### Phase 3 — Demonstration

```bash
# Start services
./gradlew bootRun
python3 ai_training/ai_server.py

# Open these tabs
http://localhost:8080              # Live Telemetry Dashboard
http://localhost:8080/training.html   # AI Training Studio
http://localhost:8080/eyedropper.html # Digital Colorimeter
```

**Demonstration script:**
1. **Live Dashboard** → Simulate Hardware Stream → watch RGB, Conductivity, and AI Purity update
2. **Training Studio** → Show scatter chart (your collected dataset clusters)
3. **Eyedropper** → Simulate colour scans → show real-time HEX transpilation
4. **Git Sync** → Show model.joblib and training_data.csv in the GitHub repository

---

## 📡 API Reference

### Java Backend — `http://localhost:8080`

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/v1/scan` | API discovery + real scan count |
| `POST` | `/api/v1/scan/manual` | Submit a scan (real or simulated) |
| `GET` | `/api/v1/scan/latest` | Most recent scan record |
| `GET` | `/api/v1/scan/history` | Last 100 records (all, incl. simulated) |
| `GET` | `/api/v1/scan/history/real` | Last 500 **real** records only (used by AI) |
| `GET` | `/api/v1/scan/count/real` | Count of real scans (for stat cards) |
| `GET` | `/api/v1/scan/export` | Export real scans to `dataset/training_data.csv` |
| `POST` | `/api/v1/scan/train` | Proxy trigger to Python AI training |
| `GET` | `/h2-console` | H2 database browser |

### Python AI Server — `http://localhost:5001`

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/ai/train` | Fetch real history, tune & train KNN, save model |
| `POST` | `/api/ai/predict` | Run inference on a single scan payload |
| `POST` | `/api/ai/git-sync` | Triple-gate: push `model.joblib` to Git |
| `POST` | `/api/ai/git-sync-dataset` | Triple-gate: export CSV + push `training_data.csv` |
| `GET` | `/api/ai/status` | Model ready status |
| `GET` | `/` | Server info + endpoint listing |

---

## ✨ Features

### Hardware Lockout
When the real ESP8266 hardware node is streaming live MQTT data, any simulated manual readings are automatically rejected with HTTP 423. The lockout window is **10 seconds** after the last real hardware message, preventing contamination of real telemetry sessions.

### Simulated vs Real Data Separation
Every reading is permanently tagged in the database:

```
isSimulated = false  →  Real hardware data (used for AI training)
isSimulated = true   →  Simulated/demo data (stored but excluded from AI)
```

Detection is dual-layer: explicit `isSimulated: true` flag in the request body, **and** device ID pattern matching (`manual-input`, `simulated`, `colorimeter-input`, etc.).

### Auto-Hyperparameter Tuning
The AI server cross-validates K values from 1 to 15 using 5-fold CV R² scoring and picks the optimal K automatically. No manual tuning required.

### Triple-Gate Git Sync
Both the model and dataset require 3 deliberate confirmations (with progressive visual warnings: Green → Yellow → Red → Execute) before any Git push is made. Prevents accidental overwrites.

### Dataset Auto-Import
On backend startup, if the H2 database is empty and `dataset/training_data.csv` exists (e.g. after `git pull` on a new machine), the entire dataset is automatically bulk-imported. The AI server then restores `model.joblib` and is instantly ready.

---

## 📊 Data Architecture

```
SpectrometerData (JPA Entity)
├── id               BIGINT  AUTO
├── deviceId         VARCHAR  "hardware-mqtt-node" | "manual-input" | ...
├── timestamp        BIGINT   epoch ms
├── opticalR         INT      0–255
├── opticalG         INT      0–255
├── opticalB         INT      0–255
├── conductivityMv   INT      0–2000
├── purityPercentage DOUBLE   0.0–100.0
├── hexCode          VARCHAR  "#RRGGBB"
└── isSimulated      BOOLEAN  false = real | true = simulated
```

The AI model trains on features `[opticalR, opticalG, opticalB, conductivityMv]` and predicts `purityPercentage`, using only rows where `isSimulated = false`.

---

## 🖥 Cross-Machine Portability

| Scenario | Model | Dataset |
|---|---|---|
| Same machine, restart | ✅ Auto-loaded from `model.joblib` | ✅ Persists in H2 file DB |
| New machine, `git pull` | ✅ If synced via triple-gate | ✅ Auto-imported from CSV on startup |
| New machine, no sync | ❌ Must retrain | ❌ Must recollect |

**Recommended before demo day:**
```bash
# On your primary machine — after collecting data and training
# In Training Studio, click "Sync Dataset to Git" × 3
# Then click "Sync Model to Git" × 3

# On demo machine
git pull
./gradlew bootRun        # auto-imports dataset from CSV
python3 ai_training/ai_server.py  # auto-loads model.joblib
# Ready instantly
```

---

## 🧪 Running Tests

```bash
./gradlew test
```

**Test coverage (`IngestionServiceTest.java`):**
- ✅ Valid manual scan is persisted
- ✅ HEX transpilation (R=255,G=0,B=0 → `#FF0000`)
- ✅ Sensor fault produces `purityPercentage = 0.0`
- ✅ Out-of-range RGB clamped to `[0, 255]`
- ✅ Null RGB fields default to `0`
- ✅ Simulated data accepted when no hardware active
- ✅ Simulated data rejected within 10s hardware lockout window

---

## 👥 Team

> Team profiles are editable from the Live Dashboard. Photos and details are stored in browser `localStorage`.

| Role | Responsibility |
|---|---|
| Hardware Lead | ESP8266 firmware, sensor wiring, MQTT publisher |
| Backend Lead | Spring Boot, MQTT integration, REST API, H2 database |
| AI Lead | KNN model, training pipeline, Python Flask server |
| Frontend Lead | Dashboard UI, training studio, eyedropper module |

---

## 🔧 Configuration

All configuration lives in `src/main/resources/application.yml`:

```yaml
# MQTT Broker (change for local mosquitto)
mqtt.broker.url: tcp://broker.emqx.io:1883

# AI Server URL
ai.server.url: http://localhost:5001

# CORS (restrict for production deployment)
cors.allowed-origins: "*"

# Database (file-mode H2; switch to PostgreSQL for production)
spring.datasource.url: jdbc:h2:file:./data/spectrometer_db
```

---

## 🚨 Troubleshooting

| Problem | Fix |
|---|---|
| Port 8080 in use | `lsof -i :8080` → `kill -9 <PID>` |
| Port 5001 in use | `lsof -i :5001` → `kill -9 <PID>` |
| AI says "not trained" | Click Train AI Pipeline in training.html |
| Git sync fails | Check `git remote -v` and auth credentials |
| MQTT not connecting | System works fully offline via simulation mode |
| H2 console shows empty table | Check `./data/` directory exists; use file JDBC URL |

---

<div align="center">

**Ghost Rider Spectrometer** · BIT Durg · IoT & AI Systems

*Built with Spring Boot, Flask, MQTT, and a lot of spectroscopy.*

</div>

import time
import requests
import pandas as pd
import numpy as np
import joblib
import subprocess
import os
import logging
from sklearn.neighbors import KNeighborsRegressor
from sklearn.model_selection import train_test_split, cross_val_score, KFold
from sklearn.preprocessing import StandardScaler

logging.basicConfig(level=logging.INFO, format='[%(levelname)s] %(message)s')
log = logging.getLogger(__name__)

BACKEND_URL     = os.environ.get('BACKEND_URL', 'http://localhost:8080')
HISTORY_REAL    = f'{BACKEND_URL}/api/v1/scan/history/real'  # Real scans ONLY — no simulated data
LATEST_ENDPOINT = f'{BACKEND_URL}/api/v1/scan/latest'
EXPORT_ENDPOINT = f'{BACKEND_URL}/api/v1/scan/export'       # Writes CSV to dataset/training_data.csv
POLL_INTERVAL_SECONDS = 1.0
GIT_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


class SpectrometerInference:
    def __init__(self):
        self.scaler = None
        self.model  = None
        self.last_timestamp = None
        self.model_path = os.path.join(os.path.dirname(__file__), 'model.joblib')

    # ------------------------------------------------------------------
    # Model persistence
    # ------------------------------------------------------------------
    def save_model(self):
        """Serialise the trained model + scaler to disk."""
        if self.model is None or self.scaler is None:
            log.warning("save_model called but model/scaler not initialised. Skipping.")
            return False
        try:
            joblib.dump({'model': self.model, 'scaler': self.scaler}, self.model_path)
            log.info("Model saved to %s", self.model_path)
            return True
        except Exception as e:
            log.error("Failed to save model: %s", e)
            return False

    def load_model(self):
        """Attempt to load a previously saved model from disk."""
        if not os.path.exists(self.model_path):
            log.info("No saved model found at %s.", self.model_path)
            return False
        try:
            payload = joblib.load(self.model_path)
            self.model  = payload['model']
            self.scaler = payload['scaler']
            log.info("Model loaded from %s", self.model_path)
            return True
        except Exception as e:
            log.error("Failed to load model: %s", e)
            return False

    def git_push_dataset(self):
        """Tell Java to export the CSV, then commit and push it to Git."""
        try:
            log.info("[GIT] Requesting Java backend to export real scan CSV...")
            resp = requests.get(EXPORT_ENDPOINT, timeout=15)
            resp.raise_for_status()
            result = resp.json()
            if result.get('count', 0) == 0:
                log.warning("[GIT] No real scans to export. Aborting dataset sync.")
                return False

            csv_path = os.path.join(GIT_REPO_ROOT, 'dataset', 'training_data.csv')
            log.info("[GIT] Staging dataset CSV (%d scans)...", result['count'])
            subprocess.run(["git", "-C", GIT_REPO_ROOT, "add", csv_path],
                           check=True, capture_output=True)

            diff = subprocess.run(
                ["git", "-C", GIT_REPO_ROOT, "diff", "--cached", "--quiet"],
                capture_output=True
            )
            if diff.returncode == 0:
                log.warning("[GIT] Dataset CSV unchanged. Nothing to commit.")
                return True

            subprocess.run(["git", "-C", GIT_REPO_ROOT, "commit",
                            "-m", f"data: Export {result['count']} real scans [skip ci]"],
                           check=True, capture_output=True)
            subprocess.run(["git", "-C", GIT_REPO_ROOT, "push", "origin", "main"],
                           check=True, capture_output=True)
            log.info("[GIT] Dataset (%d scans) pushed to origin/main.", result['count'])
            return True
        except subprocess.CalledProcessError as e:
            stderr = e.stderr.decode().strip() if e.stderr else ''
            log.error("[GIT] Dataset push failed: %s | %s", e.cmd, stderr)
            return False
        except Exception as e:
            log.error("[GIT] Unexpected error during dataset push: %s", e)
            return False

    def git_push_model(self):
        """Add, commit and push model.joblib to the remote repository."""
        try:
            log.info("[GIT] Staging model file (force-adding ignored file)...")
            # -f is required because model.joblib is in .gitignore by default.
            # The triple-gate UI is the intentional bypass mechanism.
            subprocess.run(["git", "-C", GIT_REPO_ROOT, "add", "-f", self.model_path],
                           check=True, capture_output=True)

            # Check if there is actually anything new to commit
            diff = subprocess.run(
                ["git", "-C", GIT_REPO_ROOT, "diff", "--cached", "--quiet"],
                capture_output=True
            )
            if diff.returncode == 0:
                log.warning("[GIT] model.joblib unchanged since last push. Nothing to commit.")
                return True   # Not an error — model is already up-to-date

            subprocess.run(["git", "-C", GIT_REPO_ROOT, "commit",
                            "-m", "AI: Auto-update trained model weights [skip ci]"],
                           check=True, capture_output=True)
            subprocess.run(["git", "-C", GIT_REPO_ROOT, "push", "origin", "main"],
                           check=True, capture_output=True)
            log.info("[GIT] Model pushed to origin/main successfully.")
            return True
        except subprocess.CalledProcessError as e:
            stderr = e.stderr.decode().strip() if e.stderr else ''
            log.error("[GIT] Command failed: %s | stderr: %s", e.cmd, stderr)
            return False
        except Exception as e:
            log.error("[GIT] Unexpected error: %s", e)
            return False

    # ------------------------------------------------------------------
    # Data fetching
    # ------------------------------------------------------------------
    def fetch_history(self):
        """Fetch REAL (non-simulated) scan records from the Java backend."""
        log.info("Fetching real scan history from backend...")
        response = requests.get(HISTORY_REAL, timeout=5)
        if response.status_code == 204:
            return pd.DataFrame()  # No real data yet
        response.raise_for_status()
        data = response.json()
        if not data:
            return pd.DataFrame()
        return pd.DataFrame(data)

    def generate_fallback_history(self, n_samples=250):
        """Generate synthetic training data when the backend has no records."""
        log.info("Generating fallback synthetic dataset (%d samples).", n_samples)
        np.random.seed(42)
        df = pd.DataFrame({
            'opticalR': np.random.normal(150, 40, n_samples).clip(0, 255).astype(int),
            'opticalG': np.random.normal(150, 40, n_samples).clip(0, 255).astype(int),
            'opticalB': np.random.normal(150, 40, n_samples).clip(0, 255).astype(int),
            'conductivityMv': np.random.normal(220, 110, n_samples).clip(0, 1000).astype(int),
        })
        df['purityPercentage'] = np.maximum(
            0,
            100 - (df['conductivityMv'] * 0.1)
            - (np.abs(df['opticalR'] - 150) * 0.05)
            - (np.abs(df['opticalG'] - 150) * 0.05)
            - (np.abs(df['opticalB'] - 150) * 0.05)
            + np.random.normal(0, 6, n_samples)
        ).clip(0, 100)
        return df

    # ------------------------------------------------------------------
    # Training
    # ------------------------------------------------------------------
    def _prepare(self, df):
        """Clean, impute, scale and split the dataset."""
        required = {'opticalR', 'opticalG', 'opticalB', 'conductivityMv', 'purityPercentage'}
        missing  = required - set(df.columns)
        if missing:
            raise ValueError(f"Dataset missing columns: {missing}")

        df = df.copy()
        df['opticalR']       = df['opticalR'].fillna(150).clip(0, 255)
        df['opticalG']       = df['opticalG'].fillna(150).clip(0, 255)
        df['opticalB']       = df['opticalB'].fillna(150).clip(0, 255)
        df['conductivityMv'] = df['conductivityMv'].fillna(df['conductivityMv'].median()).clip(0, 2000)

        # Drop sensor-fault rows (all zeros)
        fault_mask = (df['opticalR'] == 0) & (df['opticalG'] == 0) & (df['opticalB'] == 0)
        if fault_mask.any():
            log.warning("Dropping %d sensor-fault rows from training set.", fault_mask.sum())
            df = df[~fault_mask]

        if df.empty:
            raise ValueError("No valid training rows remain after cleaning.")

        features = df[['opticalR', 'opticalG', 'opticalB', 'conductivityMv']]
        target   = df['purityPercentage'].fillna(df['purityPercentage'].mean())

        scaler    = StandardScaler()
        X_scaled  = scaler.fit_transform(features)
        self.scaler = scaler
        return train_test_split(X_scaled, target, test_size=0.2, random_state=42)

    def _tune_k(self, X_train, y_train):
        """Cross-validate k in [1,15] and return the best k."""
        best_k, best_score = 1, -np.inf
        for k in range(1, 16):
            scores     = cross_val_score(
                KNeighborsRegressor(n_neighbors=k), X_train, y_train,
                cv=KFold(n_splits=5, shuffle=True, random_state=42),
                scoring='r2'
            )
            mean_score = float(np.mean(scores))
            if mean_score > best_score:
                best_score, best_k = mean_score, k
        log.info("Optimal K = %d  (CV R² = %.4f)", best_k, best_score)
        return best_k

    def train_with_tuning(self, df):
        """Full training pipeline: clean → tune → fit → evaluate → save."""
        X_train, X_test, y_train, y_test = self._prepare(df)
        best_k     = self._tune_k(X_train, y_train)
        self.model = KNeighborsRegressor(n_neighbors=best_k)
        self.model.fit(X_train, y_train)
        score = self.model.score(X_test, y_test)
        log.info("Final model R² on test set: %.4f", score)
        self.save_model()
        return score

    # ------------------------------------------------------------------
    # Inference
    # ------------------------------------------------------------------
    def predict(self, row: dict):
        """Run inference on a single scan row (dict with optical + conductivity keys)."""
        if self.model is None or self.scaler is None:
            raise RuntimeError("Model is not trained. Call train_with_tuning() first.")

        r   = row.get('opticalR', 0)
        g   = row.get('opticalG', 0)
        b   = row.get('opticalB', 0)
        cnd = row.get('conductivityMv', 0)

        if r == 0 and g == 0 and b == 0:
            return 'ERR_OPTICAL_FAULT'
        if cnd is None or (isinstance(cnd, float) and np.isnan(cnd)):
            return 'ERR_CONDUCTIVITY_FAULT'

        X        = np.array([[r, g, b, cnd]])
        X_scaled = self.scaler.transform(X)
        return float(np.clip(self.model.predict(X_scaled)[0], 0, 100))

    # ------------------------------------------------------------------
    # Polling loop (used when running as standalone script)
    # ------------------------------------------------------------------
    def poll_latest(self):
        log.info("Starting polling loop every %.1fs ...", POLL_INTERVAL_SECONDS)
        while True:
            try:
                resp = requests.get(LATEST_ENDPOINT, timeout=5)
                if resp.status_code == 200:
                    payload    = resp.json()
                    current_ts = payload.get('timestamp') if payload else None
                    if current_ts and current_ts != self.last_timestamp:
                        self.last_timestamp = current_ts
                        result = self.predict(payload)
                        log.info("Scan ts=%s => purity=%.1f%%", current_ts, result
                                 if isinstance(result, float) else result)
                elif resp.status_code == 204:
                    log.debug("Backend has no scan data yet.")
                else:
                    log.warning("Unexpected backend status: %d", resp.status_code)
            except requests.RequestException as exc:
                log.error("Cannot reach backend: %s", exc)
            time.sleep(POLL_INTERVAL_SECONDS)


# --------------------------------------------------------------------------
# Standalone entry point
# --------------------------------------------------------------------------
if __name__ == '__main__':
    engine = SpectrometerInference()

    # Try loading a previously trained model first
    if not engine.load_model():
        try:
            history_df = engine.fetch_history()
        except Exception as err:
            log.warning("History fetch failed (%s). Using synthetic fallback.", err)
            history_df = pd.DataFrame()

        if history_df.empty:
            history_df = engine.generate_fallback_history()

        engine.train_with_tuning(history_df)

    engine.poll_latest()

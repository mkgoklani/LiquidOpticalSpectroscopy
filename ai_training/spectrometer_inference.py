import time
import requests
import pandas as pd
import numpy as np
from sklearn.neighbors import KNeighborsRegressor
from sklearn.model_selection import train_test_split, cross_val_score, KFold
from sklearn.preprocessing import StandardScaler

BACKEND_URL = 'http://localhost:8080'
HISTORY_ENDPOINT = f'{BACKEND_URL}/api/v1/scan/history'
LATEST_ENDPOINT = f'{BACKEND_URL}/api/v1/scan/latest'

MODEL_K = 5
POLL_INTERVAL_SECONDS = 1.0


class SpectrometerInference:
    def __init__(self):
        self.scaler = None
        self.model = None
        self.last_timestamp = None

    def fetch_history(self):
        print("[SYSTEM] Reaching out to Java Backend for historical data....")
        response = requests.get(HISTORY_ENDPOINT, timeout=5)
        response.raise_for_status()
        data = response.json()
        return pd.DataFrame(data)

    def generate_fallback_history(self, n_samples=250):
        np.random.seed(42)
        df = pd.DataFrame({
            'deviceId': ['esp8266_fallback'] * n_samples,
            'timestamp': np.arange(n_samples) * 1000 + int(time.time() * 1000),
            'opticalR': np.random.normal(150, 40, n_samples).clip(0, 255).astype(int),
            'opticalG': np.random.normal(150, 40, n_samples).clip(0, 255).astype(int),
            'opticalB': np.random.normal(150, 40, n_samples).clip(0, 255).astype(int),
            'conductivityMv': np.random.normal(220, 110, n_samples).clip(0, 1000).astype(int),
        })
        df['purityPercentage'] = np.maximum(
            0,
            100 - (df['conductivityMv'] * 0.1) - (np.abs(df['opticalR'] - 150) * 0.05)
            - (np.abs(df['opticalG'] - 150) * 0.05) - (np.abs(df['opticalB'] - 150) * 0.05)
            + np.random.normal(0, 6, n_samples)
        ).clip(0, 100)
        return df

    def prepare_training_data(self, df):
        if df.empty:
            raise ValueError("No training data available")

        df = df.copy()
        df['opticalR'] = df['opticalR'].fillna(150)
        df['opticalG'] = df['opticalG'].fillna(150)
        df['opticalB'] = df['opticalB'].fillna(150)
        df['conductivityMv'] = df['conductivityMv'].fillna(df['conductivityMv'].median())

        df.loc[(df['opticalR'] == 0) & (df['opticalG'] == 0) & (df['opticalB'] == 0),
               ['opticalR', 'opticalG', 'opticalB']] = 150

        features = df[['opticalR', 'opticalG', 'opticalB', 'conductivityMv']]
        target = df['purityPercentage'].fillna(df['purityPercentage'].mean())

        scaler = StandardScaler()
        X_scaled = scaler.fit_transform(features)
        self.scaler = scaler
        return train_test_split(X_scaled, target, test_size=0.2, random_state=42)

    def tune_model(self, X_train, y_train):
        best_k = 1
        best_score = -np.inf
        for k in range(1, 16):
            knn = KNeighborsRegressor(n_neighbors=k)
            scores = cross_val_score(knn, X_train, y_train,
                                     cv=KFold(n_splits=5, shuffle=True, random_state=42),
                                     scoring='r2')
            mean_score = np.mean(scores)
            if mean_score > best_score:
                best_score = mean_score
                best_k = k
        print(f"[SYSTEM] Optimal K found: {best_k} (CV R2={best_score:.4f})")
        return best_k

    def train(self, df):
        X_train, X_test, y_train, y_test = self.prepare_training_data(df)
        self.model = KNeighborsRegressor(n_neighbors=MODEL_K)
        self.model.fit(X_train, y_train)
        score = self.model.score(X_test, y_test)
        print(f"[SYSTEM] Model trained with score: {score:.4f}")

    def train_with_tuning(self, df):
        X_train, X_test, y_train, y_test = self.prepare_training_data(df)
        best_k = self.tune_model(X_train, y_train)
        self.model = KNeighborsRegressor(n_neighbors=best_k)
        self.model.fit(X_train, y_train)
        score = self.model.score(X_test, y_test)
        print(f"[SYSTEM] Final tuned model score: {score:.4f}")

    def predict(self, row):
        if row['opticalR'] == 0 and row['opticalG'] == 0 and row['opticalB'] == 0:
            return 'ERR_OPTICAL_FAULT'
        if pd.isna(row['conductivityMv']):
            return 'ERR_CONDUCTIVITY_FAULT'

        X = np.array([[row['opticalR'], row['opticalG'], row['opticalB'], row['conductivityMv']]])
        X_scaled = self.scaler.transform(X)
        prediction = self.model.predict(X_scaled)[0]
        return float(np.clip(prediction, 0, 100))

    def poll_latest(self):
        while True:
            try:
                response = requests.get(LATEST_ENDPOINT, timeout=5)
                if response.status_code == 200:
                    payload = response.json()
                    if not payload:
                        print("[SYSTEM] No latest payload available yet.")
                    else:
                        current_ts = payload.get('timestamp')
                        if current_ts != self.last_timestamp:
                            self.last_timestamp = current_ts
                            result = self.predict(payload)
                            print(f"[INFERENCE] Latest scan {current_ts} => {result}")
                elif response.status_code == 204:
                    print("[SYSTEM] Backend has no latest scan yet.")
                else:
                    print(f"[SYSTEM] Unexpected status: {response.status_code}")
            except requests.RequestException as exc:
                print(f"[SYSTEM] Failed to reach backend: {exc}")
            time.sleep(POLL_INTERVAL_SECONDS)


if __name__ == '__main__':
    engine = SpectrometerInference()
    try:
        history_df = engine.fetch_history()
        if history_df.empty:
            print("[SYSTEM] History empty, generating fallback data.")
            history_df = engine.generate_fallback_history()
    except Exception as error:
        print(f"[SYSTEM] Error fetching history: {error}")
        history_df = engine.generate_fallback_history()

    engine.train_with_tuning(history_df)
    engine.poll_latest()

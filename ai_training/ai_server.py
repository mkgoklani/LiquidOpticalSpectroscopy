from flask import Flask, jsonify, request
from flask_cors import CORS
from spectrometer_inference import SpectrometerInference

app = Flask(__name__)
CORS(app)
engine = SpectrometerInference()
trained = False

@app.route('/api/ai/train', methods=['POST'])
def train_model():
    global trained
    try:
        history_df = engine.fetch_history()
    except Exception as exc:
        return jsonify({
            'status': 'error',
            'message': f'Failed to fetch history from backend: {exc}'
        }), 503

    if history_df.empty:
        return jsonify({
            'status': 'error',
            'message': 'No historical scans available to train.'
        }), 400

    try:
        engine.train_with_tuning(history_df)
        trained = True
        return jsonify({
            'status': 'success',
            'message': 'Model trained successfully.',
            'trained': True
        }), 200
    except Exception as exc:
        return jsonify({
            'status': 'error',
            'message': f'AI training failed: {exc}'
        }), 500

@app.route('/api/ai/predict', methods=['POST'])
def predict_model():
    if not trained:
        return jsonify({'status': 'error', 'message': 'Model is not trained yet.'}), 400

    payload = request.get_json(force=True)
    if payload is None:
        return jsonify({'status': 'error', 'message': 'JSON payload required.'}), 400

    required = ['opticalR', 'opticalG', 'opticalB', 'conductivityMv']
    if not all(key in payload for key in required):
        return jsonify({'status': 'error', 'message': 'Payload must include opticalR, opticalG, opticalB, conductivityMv.'}), 400

    try:
        prediction = engine.predict(payload)
        return jsonify({'status': 'success', 'prediction': prediction}), 200
    except Exception as exc:
        return jsonify({'status': 'error', 'message': f'Inference failed: {exc}'}), 500

@app.route('/api/ai/status', methods=['GET'])
def status():
    return jsonify({'trained': trained})

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5001, debug=False)

from flask import Flask, jsonify, request
from flask_cors import CORS
from spectrometer_inference import SpectrometerInference
import logging

logging.basicConfig(level=logging.INFO, format='[%(levelname)s] %(message)s')
log = logging.getLogger(__name__)

app = Flask(__name__)
CORS(app)

engine        = SpectrometerInference()
trained       = False
confirmations = 0   # Triple-gate counter for Git sync

# Try to restore a previously saved model on startup
if engine.load_model():
    trained = True
    log.info("Restored pre-trained model from disk.")

# ------------------------------------------------------------------
# Routes
# ------------------------------------------------------------------

@app.route('/', methods=['GET'])
def index():
    return jsonify({
        'status':    'online',
        'service':   'Spectrometer AI Server',
        'model_ready': trained,
        'endpoints': [
            'POST /api/ai/train',
            'POST /api/ai/predict',
            'POST /api/ai/git-sync',
            'GET  /api/ai/status',
        ]
    })


@app.route('/api/ai/train', methods=['POST'])
def train_model():
    global trained
    try:
        history_df = engine.fetch_history()
    except Exception as exc:
        log.error("History fetch failed: %s", exc)
        return jsonify({'status': 'error', 'message': f'Failed to fetch history: {exc}'}), 503

    if history_df.empty:
        log.warning("Training requested but no data in backend. Using fallback.")
        history_df = engine.generate_fallback_history()

    try:
        score  = engine.train_with_tuning(history_df)
        trained = True
        return jsonify({
            'status':    'success',
            'message':   'Model trained and saved.',
            'model_r2':  round(score, 4),
            'trained':   True
        }), 200
    except Exception as exc:
        log.error("Training failed: %s", exc)
        return jsonify({'status': 'error', 'message': f'AI training failed: {exc}'}), 500


@app.route('/api/ai/predict', methods=['POST'])
def predict_model():
    if not trained:
        return jsonify({'status': 'error', 'message': 'Model is not trained yet.'}), 400

    payload = request.get_json(force=True, silent=True)
    if payload is None:
        return jsonify({'status': 'error', 'message': 'Valid JSON body required.'}), 400

    required = ['opticalR', 'opticalG', 'opticalB', 'conductivityMv']
    missing  = [k for k in required if k not in payload]
    if missing:
        return jsonify({'status': 'error', 'message': f'Missing fields: {missing}'}), 400

    try:
        prediction = engine.predict(payload)
        return jsonify({'status': 'success', 'prediction': prediction}), 200
    except Exception as exc:
        log.error("Inference failed: %s", exc)
        return jsonify({'status': 'error', 'message': f'Inference failed: {exc}'}), 500


@app.route('/api/ai/git-sync', methods=['POST'])
def git_sync():
    """
    Triple-gate endpoint. Must be called 3 times to actually push to Git.
    Returns 202 with confirmation count on calls 1 and 2, 200 on successful push.
    """
    global confirmations, trained
    if not trained:
        return jsonify({'status': 'error', 'message': 'Train the model before syncing to Git.'}), 400

    confirmations += 1
    log.info("Git sync confirmation %d/3 received.", confirmations)

    if confirmations < 3:
        return jsonify({
            'status':  'pending',
            'count':   confirmations,
            'message': f'Confirmation {confirmations}/3 received. Call again to continue.'
        }), 202

    # Third confirmation — execute push
    success        = engine.git_push_model()
    confirmations  = 0  # Reset regardless of outcome

    if success:
        return jsonify({
            'status':  'success',
            'message': 'Model weights committed and pushed to origin/main.'
        }), 200
    else:
        return jsonify({
            'status':  'error',
            'message': 'Git push failed. Check server logs for details.'
        }), 500


@app.route('/api/ai/status', methods=['GET'])
def status():
    return jsonify({
        'trained':   trained,
        'model_path_exists': engine.load_model() if not trained else True
    })


# ------------------------------------------------------------------
if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5001, debug=False)

from flask import Flask, request, jsonify, render_template
import requests

app = Flask(__name__)

BACKEND_URL = "http://localhost:8080"


@app.route("/")
def index():
    return render_template("index.html")


@app.route("/chat", methods=["POST"])
def chat():
    data = request.get_json()
    try:
        resp = requests.post(
            f"{BACKEND_URL}/api/chat",
            json=data,
            timeout=30,
        )
        return jsonify(resp.json()), resp.status_code
    except requests.exceptions.ConnectionError:
        return jsonify({"type": "error", "text": "Backend not reachable", "sessionReset": False}), 503
    except Exception as e:
        return jsonify({"type": "error", "text": str(e), "sessionReset": False}), 500


@app.route("/health")
def health():
    try:
        resp = requests.get(f"{BACKEND_URL}/health", timeout=5)
        return jsonify({"status": "connected", "backend": resp.status_code})
    except requests.exceptions.ConnectionError:
        return jsonify({"status": "disconnected"}), 503


if __name__ == "__main__":
    app.run(port=5000, debug=True)

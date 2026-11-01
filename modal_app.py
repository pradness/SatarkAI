import modal

app = modal.App("satarkai")
hf_cache = modal.Volume.from_name("satarkai-hf-cache", create_if_missing=True)

image = (
    modal.Image.debian_slim(python_version="3.11")
    .apt_install("libsndfile1")
    .pip_install_from_requirements("backend/requirements.txt")
    .pip_install("cryptography")
    .env({"HF_HOME": "/cache/hf"})
    .add_local_dir(
        "backend", remote_path="/root/backend",
        ignore=["**/.venv", "**/__pycache__", "**/.env"],
    )
    .add_local_dir("monitoring_dashboard/dist", remote_path="/root/monitoring_dashboard/dist")
)

@app.function(image=image, volumes={"/cache": hf_cache}, timeout=1800)
def download_models():
    from huggingface_hub import snapshot_download
    snapshot_download("Oriserve/Whisper-Hindi2Hinglish-Apex")
    snapshot_download("suyog17/indica-vishing-model")
    hf_cache.commit()

@app.local_entrypoint()
def main():
    download_models.remote()

@app.cls(
    image=image, gpu="T4", cpu=2, memory=8192,
    volumes={"/cache": hf_cache},
    secrets=[modal.Secret.from_name("satarkai-env")],
    scaledown_window=120, timeout=3600, max_containers=1,
)
@modal.concurrent(max_inputs=4)
class Backend:
    @modal.enter()
    def load(self):
        import sys
        sys.path.insert(0, "/root/backend")
        import server
        from whisper_service import get_whisper_engine
        get_whisper_engine()
        self.web_app = server.app

    @modal.asgi_app()
    def web(self):
        return self.web_app
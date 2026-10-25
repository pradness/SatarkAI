import logging
import os
from pathlib import Path

from dotenv import load_dotenv
from pymongo import MongoClient
from pymongo.errors import PyMongoError

logger = logging.getLogger("satarkai.db")

_PLACEHOLDER_MARKERS = (
    "<username>",
    "<db_username>",
    "<password>",
    "<db_password>",
)

_client: MongoClient | None = None
MONGODB_URI = ""
MONGODB_DATABASE = "satarkai_db"


def _load_env() -> None:
    """Load the first .env found walking from this file to repo root, then cwd."""
    candidates: list[Path] = []
    here = Path(__file__).resolve().parent
    candidates.append(here)
    candidates.extend(here.parents)
    candidates.append(Path.cwd())

    seen: set[Path] = set()
    for directory in candidates:
        if directory in seen:
            continue
        seen.add(directory)
        env_path = directory / ".env"
        if env_path.is_file():
            load_dotenv(dotenv_path=env_path, override=True)
            return


def _refresh_settings() -> None:
    global MONGODB_URI, MONGODB_DATABASE
    MONGODB_URI = os.getenv("MONGODB_URI", "").strip()
    MONGODB_DATABASE = os.getenv("MONGODB_DATABASE", "satarkai_db").strip() or "satarkai_db"


_load_env()
_refresh_settings()


def _uri_is_configured(uri: str) -> bool:
    if not uri:
        return False
    return not any(marker in uri for marker in _PLACEHOLDER_MARKERS)


def get_mongo_client() -> MongoClient:
    """Returns a singleton MongoClient instance."""
    global _client
    if _client is None:
        _refresh_settings()
        if not _uri_is_configured(MONGODB_URI):
            raise ValueError(
                "MONGODB_URI is missing or still contains placeholders. "
                "Set a real Atlas URI in the project .env file."
            )
        _client = MongoClient(MONGODB_URI, serverSelectionTimeoutMS=5000)
    return _client


def get_db():
    """Returns the MongoDB database instance."""
    client = get_mongo_client()
    return client[MONGODB_DATABASE]


def get_collection(collection_name: str):
    """Returns a specific collection from the database."""
    return get_db()[collection_name]


def ping_db() -> tuple[bool, str]:
    """Pings MongoDB to verify connection status. Never includes the URI."""
    try:
        client = get_mongo_client()
        client.admin.command("ping")
        return True, f"Connected to database '{MONGODB_DATABASE}'"
    except ValueError as e:
        return False, str(e)
    except PyMongoError as e:
        return False, f"MongoDB ping failed: {e.__class__.__name__}"
    except Exception as e:
        return False, f"MongoDB connection error: {e.__class__.__name__}"


def close_db() -> None:
    """Closes the MongoDB client connection."""
    global _client
    if _client is not None:
        _client.close()
        _client = None

import logging
import os
import sys
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

# Ensure backend directory is in sys.path
backend_dir = Path(__file__).resolve().parent
if str(backend_dir) not in sys.path:
    sys.path.insert(0, str(backend_dir))

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from jose import JWTError, jwt
from passlib.context import CryptContext
from pymongo import ASCENDING

from database import get_collection

logger = logging.getLogger("satarkai.auth")

# JWT Configuration
JWT_SECRET_KEY = os.getenv("JWT_SECRET_KEY", "satarkai_cyber_investigation_jwt_secret_key_2026_spit")
JWT_ALGORITHM = os.getenv("JWT_ALGORITHM", "HS256")
ACCESS_TOKEN_EXPIRE_MINUTES = int(os.getenv("ACCESS_TOKEN_EXPIRE_MINUTES", "1440"))  # 24 hours

pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")
security_bearer = HTTPBearer(auto_error=False)


def hash_password(password: str) -> str:
    """Hashes a plain text password with bcrypt."""
    return pwd_context.hash(password)


def verify_password(plain_password: str, hashed_password: str) -> bool:
    """Verifies a plain password against stored hash."""
    try:
        return pwd_context.verify(plain_password, hashed_password)
    except Exception as e:
        logger.error("verify_password error: %s", e)
        return False


def create_access_token(data: dict, expires_delta: timedelta | None = None) -> str:
    """Encodes JWT payload with expiration time."""
    to_encode = data.copy()
    expire = datetime.now(timezone.utc) + (
        expires_delta or timedelta(minutes=ACCESS_TOKEN_EXPIRE_MINUTES)
    )
    to_encode.update({"exp": expire})
    return jwt.encode(to_encode, JWT_SECRET_KEY, algorithm=JWT_ALGORITHM)


def decode_access_token(token: str) -> dict | None:
    """Decodes and validates a JWT token."""
    try:
        payload = jwt.decode(token, JWT_SECRET_KEY, algorithms=[JWT_ALGORITHM])
        return payload
    except JWTError:
        return None
    except Exception as e:
        logger.error("decode_access_token error: %s", e)
        return None


def init_auth_indexes() -> None:
    """Creates indexes for professional user authentication."""
    try:
        users_col = get_collection("users")
        users_col.create_index([("email", ASCENDING)], unique=True, sparse=True)
        users_col.create_index([("account_type", ASCENDING), ("approval_status", ASCENDING)])
        seed_default_investigators_if_empty()
    except Exception as e:
        logger.error("init_auth_indexes error: %s", e)


def seed_default_investigators_if_empty() -> None:
    """
    Seeds initial authorized cyber investigation accounts:
    1. Cyber Commander (Admin role)
    2. Senior Investigator (Investigator role)
    """
    try:
        users_col = get_collection("users")
        now = datetime.now(timezone.utc)

        # 1. Admin Commander Account
        admin_email = "commander.satark@cyberpolice.gov.in"
        existing_admin = users_col.find_one({"email": admin_email})
        if not existing_admin:
            admin_doc = {
                "user_id": "inv_admin_001",
                "name": "Inspector Vikram Rathore",
                "email": admin_email,
                "password_hash": hash_password("CyberOfficer@2026"),
                "badge_id": "CYBER-DELHI-4091",
                "department": "Cyber Crime Investigation Cell, HQ",
                "role": "admin",
                "account_type": "professional",
                "is_approved": True,
                "approval_status": "approved",
                "is_contact": False,
                "created_at": now,
                "last_seen_at": now,
                "approved_at": now,
                "approved_by": "SYSTEM",
            }
            users_col.insert_one(admin_doc)
            logger.info("Seeded default Cyber Admin: %s", admin_email)

        # 2. Investigator Account
        inv_email = "officer.sharma@cyberpolice.gov.in"
        existing_inv = users_col.find_one({"email": inv_email})
        if not existing_inv:
            inv_doc = {
                "user_id": "inv_agent_002",
                "name": "Sub-Inspector Neha Sharma",
                "email": inv_email,
                "password_hash": hash_password("Investigator@2026"),
                "badge_id": "CYBER-MUMBAI-2045",
                "department": "Financial Fraud & Vishing Unit",
                "role": "investigator",
                "account_type": "professional",
                "is_approved": True,
                "approval_status": "approved",
                "is_contact": False,
                "created_at": now,
                "last_seen_at": now,
                "approved_at": now,
                "approved_by": "inv_admin_001",
            }
            users_col.insert_one(inv_doc)
            logger.info("Seeded default Cyber Investigator: %s", inv_email)

    except Exception as e:
        logger.error("seed_default_investigators_if_empty error: %s", e)


def register_investigator(
    full_name: str,
    email: str,
    password: str,
    badge_id: str,
    department: str,
) -> tuple[dict | None, str | None]:
    """
    Public registration for cyber professionals.
    CRITICAL: Public sign-up NEVER grants automatic approval.
    Account is created with approval_status='pending' and is_approved=False.
    Must be reviewed and approved by an authorized administrator.
    """
    email_clean = email.strip().lower()
    if not email_clean or "@" not in email_clean:
        return None, "A valid official email address is required"
    if len(password) < 8:
        return None, "Password must be at least 8 characters long"
    if not badge_id.strip():
        return None, "Official badge ID is required"

    try:
        users_col = get_collection("users")
        existing = users_col.find_one({"email": email_clean})
        if existing:
            return None, "An account with this email address already exists"

        now = datetime.now(timezone.utc)
        user_id = f"inv_{uuid.uuid4().hex[:10]}"
        doc = {
            "user_id": user_id,
            "name": full_name.strip(),
            "email": email_clean,
            "password_hash": hash_password(password),
            "badge_id": badge_id.strip().upper(),
            "department": department.strip() or "Cyber Investigation Branch",
            "role": "investigator",
            "account_type": "professional",
            "is_approved": False,
            "approval_status": "pending",
            "is_contact": False,
            "created_at": now,
            "last_seen_at": now,
            "approved_at": None,
            "approved_by": None,
        }
        users_col.insert_one(doc)
        logger.info("New investigator registered (pending approval): %s", email_clean)

        return {
            "user_id": user_id,
            "name": doc["name"],
            "email": doc["email"],
            "badge_id": doc["badge_id"],
            "department": doc["department"],
            "role": doc["role"],
            "approval_status": doc["approval_status"],
            "is_approved": False,
            "created_at": now.isoformat(),
        }, None
    except Exception as e:
        logger.error("register_investigator error: %s", e)
        return None, f"Registration failed: {str(e)}"


def authenticate_professional(email: str, password: str) -> tuple[dict | None, str | None, int]:
    """
    Authenticates a professional user.
    Enforces password verification AND administrator approval check.
    Returns: (user_dict, error_message, http_status_code)
    """
    email_clean = email.strip().lower()
    try:
        users_col = get_collection("users")
        user = users_col.find_one({
            "email": email_clean,
            "account_type": "professional",
        })

        if not user:
            return None, "Invalid email or credentials", status.HTTP_401_UNAUTHORIZED

        if not verify_password(password, user.get("password_hash", "")):
            return None, "Invalid email or credentials", status.HTTP_401_UNAUTHORIZED

        # Controlled registration rule: public sign-up cannot access dashboard without approval
        if not user.get("is_approved", False) or user.get("approval_status") != "approved":
            status_desc = user.get("approval_status", "pending")
            return (
                None,
                f"Access denied: Account authorization status is '{status_desc}'. "
                "Contact a Cyber Cell Administrator for credential approval.",
                status.HTTP_403_FORBIDDEN,
            )

        now = datetime.now(timezone.utc)
        users_col.update_one({"user_id": user["user_id"]}, {"$set": {"last_seen_at": now}})

        token_data = {
            "sub": user["user_id"],
            "email": user["email"],
            "name": user.get("name", "Officer"),
            "role": user.get("role", "investigator"),
            "badge_id": user.get("badge_id", "CYBER-OFFICER"),
            "department": user.get("department", "Cyber Unit"),
        }
        access_token = create_access_token(token_data)

        return {
            "access_token": access_token,
            "token_type": "bearer",
            "expires_in_minutes": ACCESS_TOKEN_EXPIRE_MINUTES,
            "user": {
                "user_id": user["user_id"],
                "name": user.get("name"),
                "email": user.get("email"),
                "role": user.get("role"),
                "badge_id": user.get("badge_id"),
                "department": user.get("department"),
                "approval_status": user.get("approval_status"),
            },
        }, None, status.HTTP_200_OK

    except Exception as e:
        logger.error("authenticate_professional error: %s", e)
        return None, f"Authentication error: {str(e)}", status.HTTP_500_INTERNAL_SERVER_ERROR


def get_current_investigator(
    credentials: HTTPAuthorizationCredentials | None = Depends(security_bearer),
) -> dict:
    """
    FastAPI dependency for protecting cyber monitoring endpoints.
    Verifies JWT token and ensures account is approved with investigator or admin role.
    Rejects normal users and unauthenticated requests with HTTP 401 / 403.
    """
    if not credentials or not credentials.credentials:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Authorization token missing. Professional credentials required.",
            headers={"WWW-Authenticate": "Bearer"},
        )

    payload = decode_access_token(credentials.credentials)
    if not payload:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or expired session token. Please re-authenticate.",
            headers={"WWW-Authenticate": "Bearer"},
        )

    user_id = payload.get("sub")
    if not user_id:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Token identity missing.",
        )

    try:
        users_col = get_collection("users")
        user = users_col.find_one({
            "user_id": user_id,
            "account_type": "professional",
        })

        if not user:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Access Denied: Professional investigator identity not found.",
            )

        if not user.get("is_approved", False) or user.get("approval_status") != "approved":
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Access Denied: Investigator account is not approved.",
            )

        role = user.get("role", "investigator")
        if role not in ("investigator", "admin"):
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Access Denied: Insufficient investigation clearance.",
            )

        return {
            "user_id": user["user_id"],
            "name": user.get("name"),
            "email": user.get("email"),
            "role": role,
            "badge_id": user.get("badge_id"),
            "department": user.get("department"),
            "is_admin": role == "admin",
        }
    except HTTPException:
        raise
    except Exception as e:
        logger.error("get_current_investigator error: %s", e)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Authorization verification error.",
        )


def get_current_admin(
    investigator: dict = Depends(get_current_investigator),
) -> dict:
    """Dependency that restricts access to Cyber Administrators only."""
    if investigator.get("role") != "admin":
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Administrator clearance required for this operation.",
        )
    return investigator

import React, { useState } from 'react';
import logoImg from '../assets/logo.png';
import { loginProfessional, registerProfessional } from '../api';
import {
  IconAlertTriangle,
  IconCheckCircle,
  IconMoon,
  IconSun
} from './Icons';

export default function LoginModal({ onLoginSuccess, isDarkMode, onToggleDarkMode }) {
  const [isRegister, setIsRegister] = useState(false);
  const [email, setEmail] = useState('officer.sharma@cyberpolice.gov.in');
  const [password, setPassword] = useState('Investigator@2026');

  // Registration fields
  const [fullName, setFullName] = useState('');
  const [regEmail, setRegEmail] = useState('');
  const [regPassword, setRegPassword] = useState('');
  const [badgeId, setBadgeId] = useState('');
  const [department, setDepartment] = useState('Financial Fraud & Cyber Cell');

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [successMsg, setSuccessMsg] = useState(null);

  const handleLogin = async (e) => {
    e.preventDefault();
    setError(null);
    setSuccessMsg(null);
    setLoading(true);

    try {
      const res = await loginProfessional(email, password);
      onLoginSuccess(res.user);
    } catch (err) {
      setError(err.message || 'Login failed. Please check your credentials.');
    } finally {
      setLoading(false);
    }
  };

  const handleRegister = async (e) => {
    e.preventDefault();
    setError(null);
    setSuccessMsg(null);
    setLoading(true);

    try {
      const res = await registerProfessional({
        full_name: fullName,
        email: regEmail,
        password: regPassword,
        badge_id: badgeId,
        department: department,
      });
      setSuccessMsg(res.message || 'Registration submitted for administrator approval.');
      setIsRegister(false);
    } catch (err) {
      setError(err.message || 'Registration failed.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="login-wrap">
      <div className="login-card">
        {onToggleDarkMode && (
          <div style={{ position: 'absolute', top: '1.25rem', right: '1.25rem' }}>
            <button
              className="theme-toggle-btn"
              onClick={onToggleDarkMode}
              title={isDarkMode ? 'Switch to Light Mode' : 'Switch to Dark Mode'}
              aria-label="Toggle dark mode"
              type="button"
            >
              {isDarkMode ? <IconSun size={18} /> : <IconMoon size={18} />}
            </button>
          </div>
        )}

        <div className="login-header">
          <img
            src={logoImg}
            alt="SatarkAI"
            className="app-logo-rounded"
            style={{ width: '56px', height: '56px', marginBottom: '0.5rem' }}
          />
          <h2>SatarkAI Cyber Forensics</h2>
          <p className="brand-subtitle" style={{ marginTop: '0.25rem' }}>
            Law Enforcement & Cyber-Defense Portal
          </p>
        </div>

        {error && (
          <div className="alert-error">
            <IconAlertTriangle size={18} />
            <span>{error}</span>
          </div>
        )}

        {successMsg && (
          <div
            className="alert-error"
            style={{
              backgroundColor: 'var(--satark-safe-container)',
              borderColor: 'var(--satark-safe)',
              color: 'var(--satark-on-safe-container)',
            }}
          >
            <IconCheckCircle size={18} />
            <span>{successMsg}</span>
          </div>
        )}

        {!isRegister ? (
          <form onSubmit={handleLogin}>
            <div className="form-group">
              <label className="form-label">Official Cyber Police Email</label>
              <input
                type="email"
                className="form-input"
                required
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="officer@cyberpolice.gov.in"
              />
            </div>

            <div className="form-group">
              <label className="form-label">Security Clearance Password</label>
              <input
                type="password"
                className="form-input"
                required
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="Password"
              />
            </div>

            <button
              type="submit"
              className="btn-primary-action"
              disabled={loading}
            >
              {loading ? 'Authenticating...' : 'Sign In with Security Clearance'}
            </button>

            <div style={{ textAlign: 'center', marginTop: '1.25rem', fontSize: '0.8rem', color: 'var(--md-sys-color-on-surface-variant)' }}>
              New Officer on Duty?{' '}
              <button
                type="button"
                style={{ background: 'none', border: 'none', color: 'var(--md-sys-color-primary)', fontWeight: 600, cursor: 'pointer' }}
                onClick={() => { setIsRegister(true); setError(null); }}
              >
                Register Clearance
              </button>
            </div>
          </form>
        ) : (
          <form onSubmit={handleRegister}>
            <div className="form-group">
              <label className="form-label">Full Name & Rank</label>
              <input
                type="text"
                className="form-input"
                required
                value={fullName}
                onChange={(e) => setFullName(e.target.value)}
                placeholder="Insp. Rajesh Kumar"
              />
            </div>

            <div className="form-group">
              <label className="form-label">Official Department Email</label>
              <input
                type="email"
                className="form-input"
                required
                value={regEmail}
                onChange={(e) => setRegEmail(e.target.value)}
                placeholder="rajesh.kumar@cybercell.gov.in"
              />
            </div>

            <div className="form-group">
              <label className="form-label">Create Clearance Password</label>
              <input
                type="password"
                className="form-input"
                required
                value={regPassword}
                onChange={(e) => setRegPassword(e.target.value)}
                placeholder="Min 8 characters"
              />
            </div>

            <div className="form-group">
              <label className="form-label">Badge ID / Service Number</label>
              <input
                type="text"
                className="form-input"
                required
                value={badgeId}
                onChange={(e) => setBadgeId(e.target.value)}
                placeholder="e.g. DL-CYBER-8841"
              />
            </div>

            <div className="form-group">
              <label className="form-label">Department / Unit</label>
              <input
                type="text"
                className="form-input"
                required
                value={department}
                onChange={(e) => setDepartment(e.target.value)}
              />
            </div>

            <button
              type="submit"
              className="btn-primary-action"
              disabled={loading}
            >
              {loading ? 'Submitting...' : 'Register Clearance'}
            </button>

            <div style={{ textAlign: 'center', marginTop: '1.25rem', fontSize: '0.8rem', color: 'var(--md-sys-color-on-surface-variant)' }}>
              Already registered?{' '}
              <button
                type="button"
                style={{ background: 'none', border: 'none', color: 'var(--md-sys-color-primary)', fontWeight: 600, cursor: 'pointer' }}
                onClick={() => { setIsRegister(false); setError(null); }}
              >
                Sign In
              </button>
            </div>
          </form>
        )}
      </div>
    </div>
  );
}

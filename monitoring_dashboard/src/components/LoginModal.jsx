import React, { useState } from 'react';
import { loginProfessional, registerProfessional } from '../api';

export default function LoginModal({ onLoginSuccess }) {
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
        <div className="login-header">
          <div className="login-shield">🛡️</div>
          <h2>SatarkAI Cyber Intelligence</h2>
          <p className="brand-subtitle" style={{ marginTop: '0.25rem' }}>
            Law Enforcement & Cyber-Professional Portal
          </p>
        </div>

        {error && <div className="alert-error">⚠️ {error}</div>}
        {successMsg && (
          <div className="alert-error" style={{ background: 'var(--safe-green-bg)', borderColor: 'var(--safe-green-border)', color: '#6ee7b7' }}>
            ✅ {successMsg}
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
                placeholder="••••••••••••"
              />
            </div>

            <button type="submit" className="btn-primary" disabled={loading}>
              {loading ? 'Authenticating...' : 'Secure Investigator Login'}
            </button>

            <div style={{ marginTop: '1rem', textAlign: 'center', fontSize: '0.8rem' }}>
              <span style={{ color: 'var(--text-muted)' }}>Need clearance? </span>
              <button
                type="button"
                onClick={() => { setIsRegister(true); setError(null); }}
                style={{ background: 'none', border: 'none', color: 'var(--accent-blue)', cursor: 'pointer', fontWeight: 600 }}
              >
                Apply for Professional Access
              </button>
            </div>

            <div className="demo-credentials-box">
              <div style={{ fontWeight: 600, color: 'var(--text-secondary)', marginBottom: '0.35rem' }}>
                Pre-Authorized Official Credentials:
              </div>
              <div>Investigator: <code>officer.sharma@cyberpolice.gov.in</code> / <code>Investigator@2026</code></div>
              <div style={{ marginTop: '0.2rem' }}>Cyber Admin: <code>commander.satark@cyberpolice.gov.in</code> / <code>CyberOfficer@2026</code></div>
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
                placeholder="Inspector Rajesh Kumar"
              />
            </div>

            <div className="form-group">
              <label className="form-label">Official Gov / Police Email</label>
              <input
                type="email"
                className="form-input"
                required
                value={regEmail}
                onChange={(e) => setRegEmail(e.target.value)}
                placeholder="rajesh.kumar@cyberpolice.gov.in"
              />
            </div>

            <div className="form-group">
              <label className="form-label">Official Badge / Service ID</label>
              <input
                type="text"
                className="form-input"
                required
                value={badgeId}
                onChange={(e) => setBadgeId(e.target.value)}
                placeholder="CYBER-DELHI-5541"
              />
            </div>

            <div className="form-group">
              <label className="form-label">Department / Unit</label>
              <input
                type="text"
                className="form-input"
                value={department}
                onChange={(e) => setDepartment(e.target.value)}
                placeholder="Financial Fraud & Vishing Wing"
              />
            </div>

            <div className="form-group">
              <label className="form-label">Set Password (min 8 characters)</label>
              <input
                type="password"
                className="form-input"
                required
                minLength={8}
                value={regPassword}
                onChange={(e) => setRegPassword(e.target.value)}
                placeholder="••••••••••••"
              />
            </div>

            <button type="submit" className="btn-primary" disabled={loading}>
              {loading ? 'Submitting Application...' : 'Submit Clearance Request'}
            </button>

            <div style={{ marginTop: '1rem', textAlign: 'center', fontSize: '0.8rem' }}>
              <button
                type="button"
                onClick={() => { setIsRegister(false); setError(null); }}
                style={{ background: 'none', border: 'none', color: 'var(--accent-blue)', cursor: 'pointer', fontWeight: 600 }}
              >
                Back to Sign In
              </button>
            </div>
          </form>
        )}
      </div>
    </div>
  );
}

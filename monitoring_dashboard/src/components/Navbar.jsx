import React from 'react';

export default function Navbar({ user, activeTab, onTabChange, onLogout }) {
  const isAdmin = user?.role === 'admin';

  return (
    <header className="cyber-header">
      <div className="brand-section">
        <div className="brand-shield">🛡️</div>
        <div>
          <div className="brand-title">
            SATARK<span className="accent">AI</span>
            <span style={{ fontSize: '0.7rem', padding: '0.1rem 0.4rem', background: '#0284c7', borderRadius: '4px', color: '#fff', marginLeft: '0.3rem' }}>
              CYBER CELL
            </span>
          </div>
          <div className="brand-subtitle">Vishing Forensics & Citizen Protection</div>
        </div>
      </div>

      <nav className="nav-tabs">
        <button
          className={`nav-tab-btn ${activeTab === 'incidents' ? 'active' : ''}`}
          onClick={() => onTabChange('incidents')}
        >
          🚨 Incident Registry
        </button>
        <button
          className={`nav-tab-btn ${activeTab === 'users' ? 'active' : ''}`}
          onClick={() => onTabChange('users')}
        >
          👥 Monitored Directory
        </button>
        {isAdmin && (
          <button
            className={`nav-tab-btn ${activeTab === 'admin' ? 'active' : ''}`}
            onClick={() => onTabChange('admin')}
          >
            ⚖️ Investigator Clearances
          </button>
        )}
      </nav>

      <div className="user-badge-bar">
        <div className="investigator-pill">
          <span className="badge-id">{user?.badge_id || 'CYBER-INVESTIGATOR'}</span>
          <span style={{ fontWeight: 600 }}>{user?.name || 'Officer'}</span>
          <span style={{ color: 'var(--text-muted)', fontSize: '0.75rem' }}>({user?.role?.toUpperCase()})</span>
        </div>

        <button className="btn-logout" onClick={onLogout} title="Sign out securely">
          Sign Out
        </button>
      </div>
    </header>
  );
}

import React from 'react';
import logoImg from '../assets/logo.png';
import {
  IconAlertOctagon,
  IconUsers,
  IconScale,
  IconLogout,
  IconMoon,
  IconSun
} from './Icons';

export default function Navbar({
  user,
  activeTab,
  onTabChange,
  onLogout,
  isDarkMode,
  onToggleDarkMode
}) {
  const isAdmin = user?.role === 'admin';

  return (
    <header className="cyber-header">
      <div className="brand-section">
        <img
          src={logoImg}
          alt="SatarkAI Logo"
          className="app-logo-rounded"
        />
        <div>
          <div className="brand-title">
            SATARK<span className="accent">AI</span>
            <span className="brand-tag">
              CYBER FORENSICS
            </span>
          </div>
          <div className="brand-subtitle">AI Telephony & Vishing Defense Center</div>
        </div>
      </div>

      <nav className="nav-tabs">
        <button
          className={`nav-tab-btn ${activeTab === 'incidents' ? 'active' : ''}`}
          onClick={() => onTabChange('incidents')}
        >
          <IconAlertOctagon size={16} />
          <span>Incident Registry</span>
        </button>

        <button
          className={`nav-tab-btn ${activeTab === 'users' ? 'active' : ''}`}
          onClick={() => onTabChange('users')}
        >
          <IconUsers size={16} />
          <span>Monitored Directory</span>
        </button>

        {isAdmin && (
          <button
            className={`nav-tab-btn ${activeTab === 'admin' ? 'active' : ''}`}
            onClick={() => onTabChange('admin')}
          >
            <IconScale size={16} />
            <span>Investigator Clearances</span>
          </button>
        )}
      </nav>

      <div className="user-badge-bar">
        {/* Dark Mode Switch Button */}
        <button
          className="theme-toggle-btn"
          onClick={onToggleDarkMode}
          title={isDarkMode ? 'Switch to Light Mode' : 'Switch to Dark Mode'}
          aria-label="Toggle dark mode"
        >
          {isDarkMode ? <IconSun size={18} /> : <IconMoon size={18} />}
        </button>

        <div className="investigator-pill">
          <span className="badge-id">{user?.badge_id || 'CYBER-INV'}</span>
          <span style={{ fontWeight: 600 }}>{user?.name || 'Officer'}</span>
          <span style={{ opacity: 0.7, fontSize: '0.75rem' }}>
            ({user?.role?.toUpperCase() || 'OFFICER'})
          </span>
        </div>

        <button className="btn-logout" onClick={onLogout} title="Sign out securely">
          <IconLogout size={16} />
          <span>Sign Out</span>
        </button>
      </div>
    </header>
  );
}

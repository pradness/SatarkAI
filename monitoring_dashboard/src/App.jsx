import React, { useState, useEffect } from 'react';
import { getCurrentUser, getAuthToken, logout, getStats, getInvestigationCalls, checkBackendHealth } from './api';
import LoginModal from './components/LoginModal';
import Navbar from './components/Navbar';
import KpiStats from './components/KpiStats';
import IncidentsList from './components/IncidentsList';
import TranscriptDrawer from './components/TranscriptDrawer';
import UsersDirectory from './components/UsersDirectory';
import AdminClearance from './components/AdminClearance';
import BackendConfigModal from './components/BackendConfigModal';
import { IconCloudOff, IconSettings, IconRefreshCw } from './components/Icons';

export default function App() {
  const [currentUser, setCurrentUser] = useState(getCurrentUser());
  const [token, setToken] = useState(getAuthToken());

  // Dark Mode State
  const [isDarkMode, setIsDarkMode] = useState(() => {
    const saved = localStorage.getItem('satark_dashboard_theme');
    if (saved) return saved === 'dark';
    return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
  });

  // Backend Health & Configuration
  const [backendHealth, setBackendHealth] = useState({ online: true, latency: 0 });
  const [isConfigOpen, setIsConfigOpen] = useState(false);

  const verifyHealth = async () => {
    const health = await checkBackendHealth();
    setBackendHealth(health);
  };

  useEffect(() => {
    verifyHealth();
    const interval = setInterval(verifyHealth, 15000);
    return () => clearInterval(interval);
  }, []);

  useEffect(() => {
    const root = document.documentElement;
    if (isDarkMode) {
      root.setAttribute('data-theme', 'dark');
      localStorage.setItem('satark_dashboard_theme', 'dark');
    } else {
      root.setAttribute('data-theme', 'light');
      localStorage.setItem('satark_dashboard_theme', 'light');
    }
  }, [isDarkMode]);

  const toggleDarkMode = () => setIsDarkMode((prev) => !prev);

  // Views & Tabs
  const [activeTab, setActiveTab] = useState('incidents'); // 'incidents' | 'users' | 'admin'
  const [selectedCallId, setSelectedCallId] = useState(null);

  // Incidents data state
  const [stats, setStats] = useState(null);
  const [calls, setCalls] = useState([]);
  const [totalCallsCount, setTotalCallsCount] = useState(0);
  const [filterType, setFilterType] = useState('all');
  const [search, setSearch] = useState('');
  const [sortBy, setSortBy] = useState('date_desc');
  const [page, setPage] = useState(1);
  const [loadingCalls, setLoadingCalls] = useState(false);

  // Authentication check
  const handleLoginSuccess = (user) => {
    setCurrentUser(user);
    setToken(getAuthToken());
    verifyHealth();
  };

  const handleLogout = () => {
    logout();
    setCurrentUser(null);
    setToken(null);
  };

  // Load KPI Stats
  const loadStats = async () => {
    try {
      const data = await getStats();
      setStats(data);
      setBackendHealth((prev) => ({ ...prev, online: true }));
    } catch (err) {
      if (err.status === 401 || err.status === 403) {
        handleLogout();
      } else {
        verifyHealth();
      }
    }
  };

  // Load Incidents List
  const loadCalls = async () => {
    if (!token) return;
    setLoadingCalls(true);
    try {
      const data = await getInvestigationCalls({
        filterType,
        search,
        sortBy,
        page,
        limit: 25,
      });
      setCalls(data.items || []);
      setTotalCallsCount(data.total || 0);
    } catch (err) {
      if (err.status === 401 || err.status === 403) {
        handleLogout();
      } else {
        verifyHealth();
      }
    } finally {
      setLoadingCalls(false);
    }
  };

  useEffect(() => {
    if (token) {
      loadStats();
      loadCalls();
    }
  }, [token, filterType, search, sortBy, page]);

  // If not logged in, show secure login modal
  if (!token || !currentUser) {
    return (
      <>
        <LoginModal
          onLoginSuccess={handleLoginSuccess}
          isDarkMode={isDarkMode}
          onToggleDarkMode={toggleDarkMode}
          backendHealth={backendHealth}
          onOpenBackendConfig={() => setIsConfigOpen(true)}
        />
        <BackendConfigModal
          isOpen={isConfigOpen}
          onClose={() => setIsConfigOpen(false)}
          onHealthChange={(health) => setBackendHealth(health)}
        />
      </>
    );
  }

  return (
    <div className="app-container">
      {/* Top Navigation */}
      <Navbar
        user={currentUser}
        activeTab={activeTab}
        onTabChange={(tab) => {
          setActiveTab(tab);
          if (tab === 'incidents') {
            loadStats();
            loadCalls();
          }
        }}
        onLogout={handleLogout}
        isDarkMode={isDarkMode}
        onToggleDarkMode={toggleDarkMode}
        backendHealth={backendHealth}
        onOpenBackendConfig={() => setIsConfigOpen(true)}
      />

      <main className="main-wrapper">
        {/* Offline Alert Strip when Backend is unreachable */}
        {!backendHealth.online && (
          <div
            className="alert-error"
            style={{
              marginBottom: '1.25rem',
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              cursor: 'pointer',
            }}
            onClick={() => setIsConfigOpen(true)}
            title="Click to configure backend connection"
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: '0.65rem' }}>
              <IconCloudOff size={18} />
              <div>
                <strong>Backend Server Offline:</strong> Unable to communicate with the FastAPI server ({backendHealth.error || 'Connection refused'}). Live call events and data cannot sync.
              </div>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '0.4rem', fontSize: '0.75rem', fontWeight: 600, textDecoration: 'underline' }}>
              <IconSettings size={14} />
              <span>Configure URL</span>
            </div>
          </div>
        )}

        {/* KPI Stats Overview */}
        <KpiStats stats={stats} />

        {/* Tab 1: Incidents Registry */}
        {activeTab === 'incidents' && (
          <IncidentsList
            calls={calls}
            total={totalCallsCount}
            filterType={filterType}
            onFilterChange={(f) => { setFilterType(f); setPage(1); }}
            search={search}
            onSearchChange={(s) => { setSearch(s); setPage(1); }}
            sortBy={sortBy}
            onSortChange={(s) => { setSortBy(s); setPage(1); }}
            onSelectCall={(cid) => setSelectedCallId(cid)}
            selectedCallId={selectedCallId}
            loading={loadingCalls}
          />
        )}

        {/* Tab 2: Monitored Users Directory */}
        {activeTab === 'users' && (
          <UsersDirectory
            onSelectCallForTranscript={(cid) => setSelectedCallId(cid)}
          />
        )}

        {/* Tab 3: Admin Clearance Oversight */}
        {activeTab === 'admin' && <AdminClearance />}
      </main>

      {/* Floating / Sliding Transcript Drawer */}
      {selectedCallId && (
        <TranscriptDrawer
          callId={selectedCallId}
          onClose={() => setSelectedCallId(null)}
        />
      )}

      {/* Backend API Configuration Modal */}
      <BackendConfigModal
        isOpen={isConfigOpen}
        onClose={() => setIsConfigOpen(false)}
        onHealthChange={(health) => setBackendHealth(health)}
      />
    </div>
  );
}

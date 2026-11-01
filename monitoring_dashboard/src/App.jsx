import React, { useState, useEffect } from 'react';
import { getCurrentUser, getAuthToken, logout, getStats, getInvestigationCalls } from './api';
import LoginModal from './components/LoginModal';
import Navbar from './components/Navbar';
import KpiStats from './components/KpiStats';
import IncidentsList from './components/IncidentsList';
import TranscriptDrawer from './components/TranscriptDrawer';
import UsersDirectory from './components/UsersDirectory';
import AdminClearance from './components/AdminClearance';

export default function App() {
  const [currentUser, setCurrentUser] = useState(getCurrentUser());
  const [token, setToken] = useState(getAuthToken());

  // Dark Mode State
  const [isDarkMode, setIsDarkMode] = useState(() => {
    const saved = localStorage.getItem('satark_dashboard_theme');
    if (saved) return saved === 'dark';
    return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
  });

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
    } catch (err) {
      if (err.status === 401 || err.status === 403) {
        handleLogout();
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
      <LoginModal
        onLoginSuccess={handleLoginSuccess}
        isDarkMode={isDarkMode}
        onToggleDarkMode={toggleDarkMode}
      />
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
      />

      <main className="main-wrapper">
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
    </div>
  );
}

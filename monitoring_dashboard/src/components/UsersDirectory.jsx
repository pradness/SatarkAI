import React, { useState, useEffect } from 'react';
import { getMonitoredUsers, getUserProfile } from '../api';

export default function UsersDirectory({ onSelectCallForTranscript }) {
  const [users, setUsers] = useState([]);
  const [search, setSearch] = useState('');
  const [loading, setLoading] = useState(true);
  const [selectedUser, setSelectedUser] = useState(null);
  const [userProfile, setUserProfile] = useState(null);
  const [profileLoading, setProfileLoading] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    fetchUsers();
  }, [search]);

  const fetchUsers = async () => {
    setLoading(true);
    try {
      const data = await getMonitoredUsers({ search });
      setUsers(data.users || []);
    } catch (err) {
      setError(err.message || 'Failed to fetch users');
    } finally {
      setLoading(false);
    }
  };

  const handleSelectUser = async (userId) => {
    setSelectedUser(userId);
    setProfileLoading(true);
    try {
      const profile = await getUserProfile(userId);
      setUserProfile(profile);
    } catch (err) {
      setError(err.message || 'Failed to fetch user profile');
    } finally {
      setProfileLoading(false);
    }
  };

  return (
    <div>
      <div className="controls-bar">
        <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
          <span style={{ fontWeight: 700, fontSize: '1rem', color: 'var(--text-primary)' }}>
            👥 Monitored Citizen Registry
          </span>
          <span style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>
            ({users.length} registered callers)
          </span>
        </div>

        <div className="search-box">
          <span style={{ marginRight: '0.4rem', color: 'var(--text-muted)' }}>🔍</span>
          <input
            type="text"
            placeholder="Search by user ID or handle..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </div>
      </div>

      {loading ? (
        <div className="empty-state">
          <div>Loading monitored citizens from MongoDB...</div>
        </div>
      ) : (
        <div style={{ background: 'var(--bg-card)', borderRadius: '10px', border: '1px solid var(--border-color)', overflow: 'hidden' }}>
          <table className="users-table">
            <thead>
              <tr>
                <th>User / Subscriber</th>
                <th>SIP Identity</th>
                <th>Total Calls</th>
                <th>AI Flagged</th>
                <th>User Reported</th>
                <th>Actionable Incidents</th>
                <th>Investigation Action</th>
              </tr>
            </thead>
            <tbody>
              {users.map((u) => (
                <tr key={u.user_id}>
                  <td>
                    <div style={{ fontWeight: 600, color: 'var(--text-primary)' }}>{u.name}</div>
                    <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', fontFamily: 'var(--font-mono)' }}>
                      ID: {u.user_id}
                    </div>
                  </td>
                  <td style={{ fontFamily: 'var(--font-mono)', fontSize: '0.8rem', color: 'var(--accent-blue)' }}>
                    {u.handle_or_phone}
                  </td>
                  <td>
                    <strong>{u.total_calls}</strong>
                  </td>
                  <td>
                    <span style={{ color: u.flagged_calls > 0 ? 'var(--scam-red)' : 'var(--text-muted)', fontWeight: 600 }}>
                      {u.flagged_calls}
                    </span>
                  </td>
                  <td>
                    <span style={{ color: u.reported_calls > 0 ? 'var(--warning-amber)' : 'var(--text-muted)', fontWeight: 600 }}>
                      {u.reported_calls}
                    </span>
                  </td>
                  <td>
                    <span
                      style={{
                        padding: '0.2rem 0.5rem',
                        borderRadius: '4px',
                        fontSize: '0.75rem',
                        fontWeight: 700,
                        background: u.actionable_calls > 0 ? 'rgba(239, 68, 68, 0.15)' : 'rgba(255,255,255,0.05)',
                        color: u.actionable_calls > 0 ? 'var(--scam-red)' : 'var(--text-muted)',
                        border: u.actionable_calls > 0 ? '1px solid var(--scam-red-border)' : '1px solid var(--border-color)',
                      }}
                    >
                      {u.actionable_calls} {u.actionable_calls === 1 ? 'Incident' : 'Incidents'}
                    </span>
                  </td>
                  <td>
                    <button
                      className="btn-open-transcript"
                      onClick={() => handleSelectUser(u.user_id)}
                      style={{ padding: '0.35rem 0.75rem', fontSize: '0.75rem' }}
                    >
                      Inspect Profile 🔍
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* User Profile Drawer */}
      {selectedUser && (
        <div className="modal-overlay" onClick={() => setSelectedUser(null)}>
          <div className="transcript-drawer" onClick={(e) => e.stopPropagation()}>
            <div className="drawer-header">
              <div className="drawer-title-area">
                <div className="drawer-title">
                  <span>👤 Monitored Profile:</span>
                  <span style={{ color: 'var(--text-primary)' }}>{userProfile?.name || userProfile?.user?.name || selectedUser}</span>
                </div>
                <div className="drawer-call-id">
                  Subscriber ID: <code>{selectedUser}</code> • SIP: {userProfile?.handle_or_phone || userProfile?.user?.handle_or_phone}
                </div>
              </div>
              <button className="btn-close" onClick={() => setSelectedUser(null)}>
                ✕
              </button>
            </div>

            {profileLoading ? (
              <div className="empty-state">
                <div>Retrieving subscriber telemetry & history...</div>
              </div>
            ) : userProfile ? (
              <div style={{ flex: 1, overflowY: 'auto', padding: '1.25rem' }}>
                {/* Stats Breakdown */}
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '0.75rem', marginBottom: '1.25rem' }}>
                  <div style={{ background: 'var(--bg-card)', padding: '0.75rem', borderRadius: '8px', border: '1px solid var(--border-color)' }}>
                    <div style={{ fontSize: '0.7rem', color: 'var(--text-muted)' }}>TOTAL CALLS</div>
                    <div style={{ fontSize: '1.25rem', fontWeight: 700 }}>{userProfile.stats?.total_calls || 0}</div>
                  </div>
                  <div style={{ background: 'var(--bg-card)', padding: '0.75rem', borderRadius: '8px', border: '1px solid var(--border-color)' }}>
                    <div style={{ fontSize: '0.7rem', color: 'var(--scam-red)' }}>AI FLAGGED</div>
                    <div style={{ fontSize: '1.25rem', fontWeight: 700, color: 'var(--scam-red)' }}>{userProfile.stats?.total_flagged || userProfile.stats?.flagged || 0}</div>
                  </div>
                  <div style={{ background: 'var(--bg-card)', padding: '0.75rem', borderRadius: '8px', border: '1px solid var(--border-color)' }}>
                    <div style={{ fontSize: '0.7rem', color: 'var(--warning-amber)' }}>REPORTED</div>
                    <div style={{ fontSize: '1.25rem', fontWeight: 700, color: 'var(--warning-amber)' }}>{userProfile.stats?.total_reported || userProfile.stats?.reported || 0}</div>
                  </div>
                  <div style={{ background: 'var(--bg-card)', padding: '0.75rem', borderRadius: '8px', border: '1px solid var(--border-color)' }}>
                    <div style={{ fontSize: '0.7rem', color: 'var(--safe-green)' }}>NORMAL / SAFE</div>
                    <div style={{ fontSize: '1.25rem', fontWeight: 700, color: 'var(--safe-green)' }}>{userProfile.stats?.normal_calls || userProfile.stats?.normal || 0}</div>
                  </div>
                </div>

                {/* Privacy Warning Banner */}
                <div style={{ background: 'rgba(56, 189, 248, 0.08)', border: '1px solid rgba(56, 189, 248, 0.25)', padding: '0.75rem 1rem', borderRadius: '8px', fontSize: '0.75rem', color: '#93c5fd', marginBottom: '1.25rem', display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                  <span>🔒</span>
                  <span>
                    <strong>Statutory Privacy Notice:</strong> Full transcript audio access is legally permitted ONLY for calls that are flagged or reported. Normal verified calls expose non-content metadata only.
                  </span>
                </div>

                {/* Calls History Table */}
                {(() => {
                  const callList = userProfile.call_history || userProfile.all_calls || [];
                  return (
                    <>
                      <div style={{ fontWeight: 600, fontSize: '0.9rem', marginBottom: '0.75rem', color: 'var(--text-primary)' }}>
                        Call Activity Log ({callList.length} records)
                      </div>

                      <div style={{ display: 'flex', flexDirection: 'column', gap: '0.6rem' }}>
                        {callList.map((c) => {
                          const isPermitted = c.has_transcript_access || c.is_actionable || c.is_flagged || c.is_reported;
                          return (
                            <div
                              key={c.call_id}
                              style={{
                                background: 'var(--bg-card)',
                                border: isPermitted ? '1px solid rgba(239, 68, 68, 0.35)' : '1px solid var(--border-color)',
                                borderRadius: '8px',
                                padding: '0.85rem',
                                display: 'flex',
                                alignItems: 'center',
                                justifyContent: 'space-between',
                              }}
                            >
                              <div>
                                <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                                  <strong style={{ fontSize: '0.85rem' }}>{c.caller_name}</strong>
                                  <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)', fontFamily: 'var(--font-mono)' }}>
                                    ({c.caller_number})
                                  </span>
                                  {c.is_flagged && (
                                    <span className="incident-classification-badge badge-scam-only" style={{ padding: '0.1rem 0.35rem', fontSize: '0.65rem' }}>
                                      FLAGGED
                                    </span>
                                  )}
                                  {c.is_reported && (
                                    <span className="incident-classification-badge badge-reported-only" style={{ padding: '0.1rem 0.35rem', fontSize: '0.65rem' }}>
                                      REPORTED
                                    </span>
                                  )}
                                  {!c.is_flagged && !c.is_reported && (
                                    <span style={{ fontSize: '0.65rem', padding: '0.1rem 0.35rem', borderRadius: '4px', background: 'var(--safe-green-bg)', color: 'var(--safe-green)', border: '1px solid var(--safe-green-border)' }}>
                                      SAFE
                                    </span>
                                  )}
                                </div>
                                <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: '0.2rem' }}>
                                  {c.timestamp_formatted} • Duration: {c.duration_formatted}
                                </div>
                              </div>

                              <div>
                                {isPermitted ? (
                                  <button
                                    className="btn-open-transcript"
                                    onClick={() => {
                                      setSelectedUser(null);
                                      onSelectCallForTranscript(c.call_id);
                                    }}
                                    style={{ padding: '0.35rem 0.75rem', fontSize: '0.75rem' }}
                                  >
                                    📜 Transcript
                                  </button>
                                ) : (
                                  <span style={{ fontSize: '0.72rem', color: 'var(--text-muted)', padding: '0.3rem 0.5rem', background: 'rgba(255,255,255,0.03)', borderRadius: '4px', border: '1px solid var(--border-color)' }}>
                                    🔒 Metadata Only
                                  </span>
                                )}
                              </div>
                            </div>
                          );
                        })}
                      </div>
                    </>
                  );
                })()}
              </div>
            ) : null}
          </div>
        </div>
      )}
    </div>
  );
}

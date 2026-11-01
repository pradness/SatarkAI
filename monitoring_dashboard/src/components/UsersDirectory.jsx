import React, { useState, useEffect } from 'react';
import { getMonitoredUsers, getUserProfile } from '../api';
import {
  IconUsers,
  IconSearch,
  IconFileText,
  IconClock,
  IconAlertOctagon,
  IconAlertTriangle,
  IconX
} from './Icons';

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
          <IconUsers size={20} style={{ color: 'var(--md-sys-color-primary)' }} />
          <span style={{ fontWeight: 700, fontSize: '1rem', color: 'var(--md-sys-color-on-surface)' }}>
            Monitored Citizen Registry
          </span>
          <span style={{ fontSize: '0.8rem', color: 'var(--md-sys-color-on-surface-variant)' }}>
            ({users.length} registered callers)
          </span>
        </div>

        <div className="search-box">
          <IconSearch size={16} />
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
          <IconClock size={32} />
          <div>Loading monitored citizens from MongoDB...</div>
        </div>
      ) : (
        <div style={{ backgroundColor: 'var(--md-sys-color-surface)', borderRadius: '16px', border: '1px solid var(--md-sys-color-outline-variant)', overflow: 'hidden', boxShadow: 'var(--shadow-elevation-1)' }}>
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
                    <div style={{ fontWeight: 600, color: 'var(--md-sys-color-on-surface)' }}>{u.name}</div>
                    <div style={{ fontSize: '0.75rem', color: 'var(--md-sys-color-on-surface-variant)', fontFamily: 'var(--font-mono)' }}>
                      ID: {u.user_id}
                    </div>
                  </td>
                  <td style={{ fontFamily: 'var(--font-mono)', fontSize: '0.8rem', color: 'var(--md-sys-color-primary)' }}>
                    {u.handle_or_phone}
                  </td>
                  <td>
                    <strong>{u.total_calls}</strong>
                  </td>
                  <td>
                    <span style={{ color: u.flagged_calls > 0 ? 'var(--md-sys-color-error)' : 'var(--md-sys-color-on-surface-variant)', fontWeight: 600 }}>
                      {u.flagged_calls}
                    </span>
                  </td>
                  <td>
                    <span style={{ color: u.reported_calls > 0 ? 'var(--satark-warning)' : 'var(--md-sys-color-on-surface-variant)', fontWeight: 600 }}>
                      {u.reported_calls}
                    </span>
                  </td>
                  <td>
                    <span
                      style={{
                        padding: '0.2rem 0.6rem',
                        borderRadius: '9999px',
                        fontSize: '0.75rem',
                        fontWeight: 700,
                        backgroundColor: u.actionable_incidents > 0 ? 'var(--md-sys-color-error-container)' : 'var(--md-sys-color-surface-container-high)',
                        color: u.actionable_incidents > 0 ? 'var(--md-sys-color-on-error-container)' : 'var(--md-sys-color-on-surface-variant)',
                      }}
                    >
                      {u.actionable_incidents} actionable
                    </span>
                  </td>
                  <td>
                    <button
                      className="btn-open-transcript"
                      style={{ padding: '0.35rem 0.8rem', fontSize: '0.75rem' }}
                      onClick={() => handleSelectUser(u.user_id)}
                    >
                      <IconFileText size={13} />
                      <span>Investigate History</span>
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* User Profile Modal */}
      {selectedUser && (
        <div className="modal-overlay" onClick={() => setSelectedUser(null)}>
          <div
            className="transcript-drawer"
            style={{ maxWidth: '640px' }}
            onClick={(e) => e.stopPropagation()}
          >
            <div className="drawer-header">
              <div className="drawer-title-area">
                <div className="drawer-title">
                  <IconUsers size={18} />
                  <span>Citizen Forensic Profile:</span>
                  <span style={{ color: 'var(--md-sys-color-primary)' }}>{userProfile?.user?.name || selectedUser}</span>
                </div>
                <div className="drawer-call-id">
                  SIP Handle: {userProfile?.user?.handle_or_phone || selectedUser}
                </div>
              </div>
              <button className="btn-close" onClick={() => setSelectedUser(null)} aria-label="Close modal">
                <IconX size={18} />
              </button>
            </div>

            <div style={{ flex: 1, overflowY: 'auto', padding: '1.25rem' }}>
              {profileLoading ? (
                <div className="empty-state">
                  <IconClock size={32} />
                  <div>Loading user investigation dossier...</div>
                </div>
              ) : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
                  <div
                    style={{
                      background: 'var(--md-sys-color-surface-container)',
                      padding: '1rem',
                      borderRadius: '12px',
                      border: '1px solid var(--md-sys-color-outline-variant)',
                      display: 'grid',
                      gridTemplateColumns: 'repeat(3, 1fr)',
                      gap: '0.5rem',
                      textAlign: 'center',
                    }}
                  >
                    <div>
                      <div style={{ fontSize: '0.75rem', color: 'var(--md-sys-color-on-surface-variant)' }}>Total Monitored</div>
                      <div style={{ fontSize: '1.4rem', fontWeight: 700 }}>{userProfile?.user?.total_calls || 0}</div>
                    </div>
                    <div>
                      <div style={{ fontSize: '0.75rem', color: 'var(--md-sys-color-on-surface-variant)' }}>AI Flagged</div>
                      <div style={{ fontSize: '1.4rem', fontWeight: 700, color: 'var(--md-sys-color-error)' }}>
                        {userProfile?.user?.flagged_calls || 0}
                      </div>
                    </div>
                    <div>
                      <div style={{ fontSize: '0.75rem', color: 'var(--md-sys-color-on-surface-variant)' }}>User Reported</div>
                      <div style={{ fontSize: '1.4rem', fontWeight: 700, color: 'var(--satark-warning)' }}>
                        {userProfile?.user?.reported_calls || 0}
                      </div>
                    </div>
                  </div>

                  <h4 style={{ fontSize: '0.9rem', fontWeight: 700, marginTop: '0.5rem', color: 'var(--md-sys-color-on-surface)' }}>
                    Actionable Threat Calls for this Citizen:
                  </h4>

                  {userProfile?.actionable_calls?.length === 0 ? (
                    <div className="empty-state" style={{ padding: '1.5rem' }}>
                      <div>No active threats recorded against this citizen account.</div>
                    </div>
                  ) : (
                    userProfile?.actionable_calls?.map((c) => (
                      <div
                        key={c.call_id}
                        style={{
                          background: 'var(--md-sys-color-surface)',
                          border: '1px solid var(--md-sys-color-outline-variant)',
                          borderRadius: '12px',
                          padding: '0.85rem 1rem',
                          display: 'flex',
                          justifyContent: 'space-between',
                          alignItems: 'center',
                        }}
                      >
                        <div>
                          <div style={{ fontWeight: 600 }}>{c.caller_name || 'Suspicious Caller'}</div>
                          <div style={{ fontSize: '0.75rem', color: 'var(--md-sys-color-on-surface-variant)' }}>
                            {c.caller_number} • {c.timestamp_formatted}
                          </div>
                          {c.threat_summary && (
                            <div style={{ fontSize: '0.75rem', color: 'var(--md-sys-color-error)', marginTop: '0.2rem' }}>
                              {c.threat_summary}
                            </div>
                          )}
                        </div>
                        <button
                          className="btn-open-transcript"
                          style={{ padding: '0.35rem 0.75rem', fontSize: '0.75rem' }}
                          onClick={() => {
                            setSelectedUser(null);
                            onSelectCallForTranscript(c.call_id);
                          }}
                        >
                          <IconFileText size={13} />
                          <span>Transcript</span>
                        </button>
                      </div>
                    ))
                  )}
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

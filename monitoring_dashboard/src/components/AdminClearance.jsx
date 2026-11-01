import React, { useEffect, useState } from 'react';
import { getInvestigatorsList, updateInvestigatorStatus } from '../api';
import {
  IconScale,
  IconClock,
  IconCheckCircle,
  IconAlertTriangle
} from './Icons';

export default function AdminClearance() {
  const [investigators, setInvestigators] = useState([]);
  const [loading, setLoading] = useState(true);
  const [statusFilter, setStatusFilter] = useState('all');
  const [error, setError] = useState(null);
  const [actionMsg, setActionMsg] = useState(null);

  useEffect(() => {
    fetchInvestigators();
  }, [statusFilter]);

  const fetchInvestigators = async () => {
    setLoading(true);
    try {
      const data = await getInvestigatorsList(statusFilter);
      setInvestigators(data.investigators || []);
    } catch (err) {
      setError(err.message || 'Failed to fetch investigator roster');
    } finally {
      setLoading(false);
    }
  };

  const handleUpdateStatus = async (userId, newStatus) => {
    try {
      setError(null);
      await updateInvestigatorStatus(userId, newStatus);
      setActionMsg(`Updated status for user ${userId} to ${newStatus}.`);
      fetchInvestigators();
    } catch (err) {
      setError(err.message || 'Failed to update clearance status');
    }
  };

  return (
    <div>
      <div className="controls-bar">
        <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
          <IconScale size={20} style={{ color: 'var(--md-sys-color-primary)' }} />
          <span style={{ fontWeight: 700, fontSize: '1rem', color: 'var(--md-sys-color-on-surface)' }}>
            Investigator Security Clearance Administration
          </span>
          <span style={{ fontSize: '0.8rem', color: 'var(--md-sys-color-on-surface-variant)' }}>
            ({investigators.length} personnel registered)
          </span>
        </div>

        <div className="filter-group">
          <button
            className={`filter-btn ${statusFilter === 'all' ? 'active' : ''}`}
            onClick={() => setStatusFilter('all')}
          >
            All Personnel
          </button>
          <button
            className={`filter-btn ${statusFilter === 'pending' ? 'active reported' : ''}`}
            onClick={() => setStatusFilter('pending')}
          >
            <IconClock size={14} />
            <span>Pending Review</span>
          </button>
          <button
            className={`filter-btn ${statusFilter === 'approved' ? 'active' : ''}`}
            onClick={() => setStatusFilter('approved')}
          >
            <IconCheckCircle size={14} />
            <span>Approved Officers</span>
          </button>
        </div>
      </div>

      {actionMsg && (
        <div
          className="alert-error"
          style={{
            backgroundColor: 'var(--satark-safe-container)',
            borderColor: 'var(--satark-safe)',
            color: 'var(--satark-on-safe-container)',
          }}
        >
          <IconCheckCircle size={16} />
          <span>{actionMsg}</span>
        </div>
      )}
      {error && (
        <div className="alert-error">
          <IconAlertTriangle size={16} />
          <span>{error}</span>
        </div>
      )}

      <div style={{ backgroundColor: 'var(--md-sys-color-surface)', borderRadius: '16px', border: '1px solid var(--md-sys-color-outline-variant)', overflow: 'hidden', boxShadow: 'var(--shadow-elevation-1)' }}>
        <table className="users-table">
          <thead>
            <tr>
              <th>Officer Name</th>
              <th>Official Email</th>
              <th>Badge ID</th>
              <th>Department / Unit</th>
              <th>Clearance Role</th>
              <th>Approval Status</th>
              <th>Administrative Action</th>
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <tr>
                <td colSpan={7} style={{ textAlign: 'center', padding: '2rem' }}>
                  Loading investigator clearance roster...
                </td>
              </tr>
            ) : investigators.length === 0 ? (
              <tr>
                <td colSpan={7} style={{ textAlign: 'center', padding: '2rem', color: 'var(--md-sys-color-on-surface-variant)' }}>
                  No investigator accounts matching filter.
                </td>
              </tr>
            ) : (
              investigators.map((inv) => (
                <tr key={inv._id || inv.email}>
                  <td>
                    <div style={{ fontWeight: 600 }}>{inv.name}</div>
                  </td>
                  <td style={{ fontFamily: 'var(--font-mono)', fontSize: '0.8rem' }}>{inv.email}</td>
                  <td>
                    <span className="badge-id">{inv.badge_id || 'N/A'}</span>
                  </td>
                  <td style={{ color: 'var(--md-sys-color-on-surface-variant)', fontSize: '0.8rem' }}>
                    {inv.department || 'Cyber Cell'}
                  </td>
                  <td>
                    <span
                      style={{
                        padding: '0.2rem 0.5rem',
                        borderRadius: '4px',
                        fontSize: '0.725rem',
                        fontWeight: 700,
                        backgroundColor: inv.role === 'admin' ? 'var(--satark-purple-container)' : 'var(--md-sys-color-secondary-container)',
                        color: inv.role === 'admin' ? 'var(--satark-on-purple-container)' : 'var(--md-sys-color-on-secondary-container)',
                      }}
                    >
                      {inv.role?.toUpperCase()}
                    </span>
                  </td>
                  <td>
                    <span
                      style={{
                        padding: '0.25rem 0.65rem',
                        borderRadius: '9999px',
                        fontSize: '0.75rem',
                        fontWeight: 700,
                        backgroundColor:
                          inv.status === 'approved'
                            ? 'var(--satark-safe-container)'
                            : inv.status === 'pending'
                            ? 'var(--satark-warning-container)'
                            : 'var(--md-sys-color-error-container)',
                        color:
                          inv.status === 'approved'
                            ? 'var(--satark-on-safe-container)'
                            : inv.status === 'pending'
                            ? 'var(--satark-on-warning-container)'
                            : 'var(--md-sys-color-on-error-container)',
                      }}
                    >
                      {inv.status?.toUpperCase() || 'PENDING'}
                    </span>
                  </td>
                  <td>
                    <div style={{ display: 'flex', gap: '0.4rem' }}>
                      {inv.status !== 'approved' && (
                        <button
                          style={{
                            backgroundColor: 'var(--satark-safe)',
                            color: '#ffffff',
                            border: 'none',
                            padding: '0.35rem 0.65rem',
                            borderRadius: '9999px',
                            fontSize: '0.75rem',
                            fontWeight: 600,
                            cursor: 'pointer',
                          }}
                          onClick={() => handleUpdateStatus(inv.user_id || inv._id, 'approved')}
                        >
                          Approve
                        </button>
                      )}
                      {inv.status !== 'rejected' && (
                        <button
                          style={{
                            backgroundColor: 'var(--md-sys-color-error)',
                            color: '#ffffff',
                            border: 'none',
                            padding: '0.35rem 0.65rem',
                            borderRadius: '9999px',
                            fontSize: '0.75rem',
                            fontWeight: 600,
                            cursor: 'pointer',
                          }}
                          onClick={() => handleUpdateStatus(inv.user_id || inv._id, 'rejected')}
                        >
                          Revoke
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

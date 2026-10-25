import React, { useEffect, useState } from 'react';
import { getInvestigatorsList, updateInvestigatorStatus } from '../api';

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
          <span style={{ fontWeight: 700, fontSize: '1rem', color: 'var(--text-primary)' }}>
            ⚖️ Investigator Security Clearance Administration
          </span>
          <span style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>
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
            Pending Review ⏳
          </button>
          <button
            className={`filter-btn ${statusFilter === 'approved' ? 'active' : ''}`}
            onClick={() => setStatusFilter('approved')}
          >
            Approved Officers ✅
          </button>
        </div>
      </div>

      {actionMsg && (
        <div className="alert-error" style={{ background: 'var(--safe-green-bg)', borderColor: 'var(--safe-green-border)', color: '#6ee7b7' }}>
          ✅ {actionMsg}
        </div>
      )}
      {error && <div className="alert-error">⚠️ {error}</div>}

      <div style={{ background: 'var(--bg-card)', borderRadius: '10px', border: '1px solid var(--border-color)', overflow: 'hidden' }}>
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
                <td colSpan={7} style={{ textAlign: 'center', padding: '2rem', color: 'var(--text-muted)' }}>
                  No investigators found for this clearance status.
                </td>
              </tr>
            ) : (
              investigators.map((inv) => {
                const isApproved = inv.approval_status === 'approved';
                const isPending = inv.approval_status === 'pending';
                const isRejected = inv.approval_status === 'rejected';

                return (
                  <tr key={inv.user_id}>
                    <td>
                      <strong style={{ color: 'var(--text-primary)' }}>{inv.name}</strong>
                      <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', fontFamily: 'var(--font-mono)' }}>
                        {inv.user_id}
                      </div>
                    </td>
                    <td style={{ fontSize: '0.8rem', fontFamily: 'var(--font-mono)' }}>{inv.email}</td>
                    <td>
                      <span className="badge-id">{inv.badge_id || 'CYBER-UNASSIGNED'}</span>
                    </td>
                    <td style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>{inv.department}</td>
                    <td style={{ fontSize: '0.8rem', fontWeight: 600, color: inv.role === 'admin' ? 'var(--accent-cyan)' : 'var(--text-primary)' }}>
                      {inv.role?.toUpperCase()}
                    </td>
                    <td>
                      <span
                        style={{
                          padding: '0.2rem 0.5rem',
                          borderRadius: '4px',
                          fontSize: '0.75rem',
                          fontWeight: 700,
                          background: isApproved
                            ? 'var(--safe-green-bg)'
                            : isPending
                            ? 'var(--warning-amber-bg)'
                            : 'var(--scam-red-bg)',
                          color: isApproved
                            ? 'var(--safe-green)'
                            : isPending
                            ? 'var(--warning-amber)'
                            : 'var(--scam-red)',
                          border: `1px solid ${
                            isApproved
                              ? 'var(--safe-green-border)'
                              : isPending
                              ? 'var(--warning-amber-border)'
                              : 'var(--scam-red-border)'
                          }`,
                        }}
                      >
                        {inv.approval_status?.toUpperCase()}
                      </span>
                    </td>
                    <td>
                      {isPending ? (
                        <div style={{ display: 'flex', gap: '0.4rem' }}>
                          <button
                            onClick={() => handleUpdateStatus(inv.user_id, 'approved')}
                            style={{
                              background: 'var(--safe-green-bg)',
                              border: '1px solid var(--safe-green-border)',
                              color: 'var(--safe-green)',
                              padding: '0.3rem 0.6rem',
                              borderRadius: '4px',
                              cursor: 'pointer',
                              fontSize: '0.75rem',
                              fontWeight: 600,
                            }}
                          >
                            Approve Clearance
                          </button>
                          <button
                            onClick={() => handleUpdateStatus(inv.user_id, 'rejected')}
                            style={{
                              background: 'var(--scam-red-bg)',
                              border: '1px solid var(--scam-red-border)',
                              color: 'var(--scam-red)',
                              padding: '0.3rem 0.6rem',
                              borderRadius: '4px',
                              cursor: 'pointer',
                              fontSize: '0.75rem',
                            }}
                          >
                            Reject
                          </button>
                        </div>
                      ) : (
                        <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>
                          {isApproved ? 'Clearance Active' : 'Access Denied'}
                        </span>
                      )}
                    </td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

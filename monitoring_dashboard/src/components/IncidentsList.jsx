import React from 'react';
import {
  IconFileText,
  IconAlertOctagon,
  IconAlertTriangle,
  IconFlame,
  IconSearch,
  IconPhoneIncoming,
  IconPhoneOutgoing,
  IconClock,
  IconShield
} from './Icons';

export default function IncidentsList({
  calls,
  total,
  filterType,
  onFilterChange,
  search,
  onSearchChange,
  sortBy,
  onSortChange,
  onSelectCall,
  selectedCallId,
  loading,
}) {
  return (
    <div>
      <div className="controls-bar">
        <div className="filter-group">
          <button
            className={`filter-btn ${filterType === 'all' ? 'active' : ''}`}
            onClick={() => onFilterChange('all')}
          >
            <IconFileText size={15} />
            <span>All Incidents</span>
          </button>
          <button
            className={`filter-btn ${filterType === 'flagged' ? 'active scam' : ''}`}
            onClick={() => onFilterChange('flagged')}
          >
            <IconAlertOctagon size={15} />
            <span>AI Flagged Only</span>
          </button>
          <button
            className={`filter-btn ${filterType === 'reported' ? 'active reported' : ''}`}
            onClick={() => onFilterChange('reported')}
          >
            <IconAlertTriangle size={15} />
            <span>Citizen Reported Only</span>
          </button>
          <button
            className={`filter-btn ${filterType === 'both' ? 'active both' : ''}`}
            onClick={() => onFilterChange('both')}
          >
            <IconFlame size={15} />
            <span>Dual Flagged & Reported</span>
          </button>
        </div>

        <div style={{ display: 'flex', gap: '0.75rem', alignItems: 'center' }}>
          <div className="search-box">
            <IconSearch size={16} />
            <input
              type="text"
              placeholder="Search caller, number, session..."
              value={search}
              onChange={(e) => onSearchChange(e.target.value)}
            />
          </div>

          <select
            className="sort-select"
            value={sortBy}
            onChange={(e) => onSortChange(e.target.value)}
          >
            <option value="date_desc">Newest First</option>
            <option value="date_asc">Oldest First</option>
            <option value="duration">Longest Duration</option>
          </select>
        </div>
      </div>

      {loading ? (
        <div className="empty-state">
          <IconClock size={32} />
          <div>Loading authenticated forensic incident registry from MongoDB...</div>
        </div>
      ) : calls.length === 0 ? (
        <div className="empty-state">
          <IconShield size={40} />
          <div style={{ fontWeight: 600, fontSize: '1rem', color: 'var(--md-sys-color-on-surface)' }}>
            No calls matching current investigative criteria
          </div>
          <div style={{ fontSize: '0.825rem', marginTop: '0.25rem' }}>
            Normal verified citizen calls are strictly quarantined and excluded from forensic surveillance.
          </div>
        </div>
      ) : (
        <div className="incidents-container">
          {calls.map((c) => {
            const isScamOnly = c.is_flagged && !c.is_reported;
            const isReportedOnly = c.is_reported && !c.is_flagged;
            const isBoth = c.is_flagged && c.is_reported;
            const isSelected = selectedCallId === c.call_id;
            const confPct = Math.round((c.scam_confidence || 0) * 100);

            return (
              <div
                key={c.call_id}
                className={`incident-card ${isSelected ? 'selected' : ''}`}
                onClick={() => onSelectCall(c.call_id)}
              >
                {/* 1. Caller & Session Details */}
                <div className="caller-info">
                  <div className="caller-name-row">
                    <span className="caller-name">{c.caller_name || 'Unknown Caller'}</span>
                    <span className="caller-handle">{c.caller_number}</span>
                  </div>
                  <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center', marginTop: '0.2rem' }}>
                    <span style={{ fontSize: '0.75rem', color: 'var(--md-sys-color-on-surface-variant)' }}>
                      User: <strong style={{ color: 'var(--md-sys-color-primary)' }}>{c.user_id}</strong>
                    </span>
                    <span style={{ fontSize: '0.75rem', color: 'var(--md-sys-color-on-surface-variant)' }}>•</span>
                    <span style={{ fontSize: '0.75rem', color: 'var(--md-sys-color-on-surface-variant)', fontFamily: 'var(--font-mono)' }}>
                      ID: {c.call_id}
                    </span>
                  </div>
                </div>

                {/* 2. Classification Badge & Threat Cue */}
                <div>
                  {isBoth && (
                    <span className="incident-classification-badge badge-both">
                      <IconFlame size={14} />
                      <span>AI FLAGGED & REPORTED</span>
                    </span>
                  )}
                  {isScamOnly && (
                    <span className="incident-classification-badge badge-scam-only">
                      <IconAlertOctagon size={14} />
                      <span>AI DETECTED VISHING</span>
                    </span>
                  )}
                  {isReportedOnly && (
                    <span className="incident-classification-badge badge-reported-only">
                      <IconAlertTriangle size={14} />
                      <span>CITIZEN REPORTED</span>
                    </span>
                  )}

                  {c.threat_summary && (
                    <div style={{ fontSize: '0.75rem', color: 'var(--md-sys-color-on-surface-variant)', marginTop: '0.35rem', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: '320px' }}>
                      {c.threat_summary}
                    </div>
                  )}
                </div>

                {/* 3. Scam Probability Meter */}
                <div className="confidence-meter">
                  <div className="confidence-val">
                    Threat Risk:{' '}
                    <span style={{ color: confPct >= 70 ? 'var(--md-sys-color-error)' : 'var(--satark-warning)' }}>
                      {confPct}%
                    </span>
                  </div>
                  <div className="meter-bar">
                    <div
                      className="meter-fill"
                      style={{
                        width: `${confPct}%`,
                        backgroundColor: confPct >= 70 ? 'var(--md-sys-color-error)' : 'var(--satark-warning)',
                      }}
                    />
                  </div>
                  <div style={{ fontSize: '0.7rem', color: 'var(--md-sys-color-on-surface-variant)' }}>
                    {c.scam_segment_count || 0} flagged lines
                  </div>
                </div>

                {/* 4. Session Timing */}
                <div className="session-meta">
                  <div className="time">{c.timestamp_formatted || 'Recent'}</div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '0.3rem' }}>
                    <IconClock size={12} />
                    <span>Duration: {c.duration_formatted || '0s'}</span>
                  </div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '0.3rem' }}>
                    {c.is_incoming ? <IconPhoneIncoming size={12} /> : <IconPhoneOutgoing size={12} />}
                    <span>{c.is_incoming ? 'Incoming Call' : 'Outgoing Call'}</span>
                  </div>
                </div>

                {/* 5. Action Button */}
                <div>
                  <button
                    className="btn-open-transcript"
                    onClick={(e) => {
                      e.stopPropagation();
                      onSelectCall(c.call_id);
                    }}
                  >
                    <IconFileText size={14} />
                    <span>View Transcript</span>
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

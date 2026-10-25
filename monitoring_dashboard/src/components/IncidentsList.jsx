import React from 'react';

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
            📋 All Incidents
          </button>
          <button
            className={`filter-btn ${filterType === 'flagged' ? 'active scam' : ''}`}
            onClick={() => onFilterChange('flagged')}
          >
            🛑 AI Flagged Only
          </button>
          <button
            className={`filter-btn ${filterType === 'reported' ? 'active reported' : ''}`}
            onClick={() => onFilterChange('reported')}
          >
            ⚠️ User Reported Only
          </button>
          <button
            className={`filter-btn ${filterType === 'both' ? 'active both' : ''}`}
            onClick={() => onFilterChange('both')}
          >
            🔥 Dual Flagged & Reported
          </button>
        </div>

        <div style={{ display: 'flex', gap: '0.75rem', alignItems: 'center' }}>
          <div className="search-box">
            <span style={{ marginRight: '0.4rem', color: 'var(--text-muted)' }}>🔍</span>
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
          <div style={{ fontSize: '1.5rem', marginBottom: '0.5rem' }}>⏳</div>
          <div>Loading authenticated forensic incident registry from MongoDB...</div>
        </div>
      ) : calls.length === 0 ? (
        <div className="empty-state">
          <div style={{ fontSize: '2rem', marginBottom: '0.5rem' }}>🛡️</div>
          <div style={{ fontWeight: 600, fontSize: '1rem', color: 'var(--text-primary)' }}>
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
                    <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>
                      User: <strong style={{ color: 'var(--accent-blue)' }}>{c.user_id}</strong>
                    </span>
                    <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>•</span>
                    <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)', fontFamily: 'var(--font-mono)' }}>
                      ID: {c.call_id}
                    </span>
                  </div>
                </div>

                {/* 2. Classification Badge & Threat Cue */}
                <div>
                  {isBoth && (
                    <span className="incident-classification-badge badge-both">
                      🔥 AI FLAGGED & REPORTED
                    </span>
                  )}
                  {isScamOnly && (
                    <span className="incident-classification-badge badge-scam-only">
                      🛑 AI DETECTED VISHING
                    </span>
                  )}
                  {isReportedOnly && (
                    <span className="incident-classification-badge badge-reported-only">
                      ⚠️ CITIZEN REPORTED
                    </span>
                  )}

                  {c.threat_summary && (
                    <div style={{ fontSize: '0.75rem', color: 'var(--text-secondary)', marginTop: '0.35rem', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: '320px' }}>
                      {c.threat_summary}
                    </div>
                  )}
                </div>

                {/* 3. Scam Probability Meter */}
                <div className="confidence-meter">
                  <div className="confidence-val">
                    Threat Risk: <span style={{ color: confPct >= 70 ? 'var(--scam-red)' : 'var(--warning-amber)' }}>{confPct}%</span>
                  </div>
                  <div className="meter-bar">
                    <div
                      className="meter-fill"
                      style={{
                        width: `${confPct}%`,
                        background: confPct >= 70 ? 'var(--scam-red)' : 'var(--warning-amber)',
                      }}
                    />
                  </div>
                  <div style={{ fontSize: '0.7rem', color: 'var(--text-muted)' }}>
                    {c.scam_segment_count || 0} flagged lines
                  </div>
                </div>

                {/* 4. Session Timing */}
                <div className="session-meta">
                  <div className="time">{c.timestamp_formatted || 'Recent'}</div>
                  <div>Duration: {c.duration_formatted || '0s'}</div>
                  <div>{c.is_incoming ? 'Incoming Call' : 'Outgoing Call'}</div>
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
                    <span>📜</span> View Transcript
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

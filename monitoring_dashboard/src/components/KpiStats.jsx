import React from 'react';

export default function KpiStats({ stats }) {
  if (!stats) return null;

  return (
    <div className="kpi-grid">
      <div className="kpi-card total">
        <div className="kpi-title">Actionable Incidents</div>
        <div className="kpi-value" style={{ color: 'var(--accent-cyan)' }}>
          {stats.total_actionable ?? 0}
        </div>
        <div className="kpi-sub">
          {stats.total_calls ?? 0} total sessions in database
        </div>
      </div>

      <div className="kpi-card scam">
        <div className="kpi-title">AI Flagged (IndicBERT)</div>
        <div className="kpi-value" style={{ color: 'var(--scam-red)' }}>
          {stats.total_flagged ?? 0}
        </div>
        <div className="kpi-sub">
          {stats.flagged_only ?? 0} automated detections only
        </div>
      </div>

      <div className="kpi-card reported">
        <div className="kpi-title">User Reported Calls</div>
        <div className="kpi-value" style={{ color: 'var(--warning-amber)' }}>
          {stats.total_reported ?? 0}
        </div>
        <div className="kpi-sub">
          {stats.reported_only ?? 0} user complaints only
        </div>
      </div>

      <div className="kpi-card both">
        <div className="kpi-title">Dual Flagged & Reported</div>
        <div className="kpi-value" style={{ color: 'var(--both-purple)' }}>
          {stats.both ?? 0}
        </div>
        <div className="kpi-sub">
          Critical corroborations (AI + Citizen)
        </div>
      </div>

      <div className="kpi-card users">
        <div className="kpi-title">Monitored Citizens</div>
        <div className="kpi-value" style={{ color: 'var(--safe-green)' }}>
          {stats.total_users ?? 0}
        </div>
        <div className="kpi-sub">
          Protected active SIP subscribers
        </div>
      </div>
    </div>
  );
}

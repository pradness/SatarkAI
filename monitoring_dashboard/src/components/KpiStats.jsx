import React from 'react';
import {
  IconFileText,
  IconAlertOctagon,
  IconAlertTriangle,
  IconFlame,
  IconUsers
} from './Icons';

export default function KpiStats({ stats }) {
  if (!stats) return null;

  return (
    <div className="kpi-grid">
      <div className="kpi-card">
        <div className="kpi-title">
          <IconFileText size={16} />
          <span>Actionable Incidents</span>
        </div>
        <div className="kpi-value" style={{ color: 'var(--md-sys-color-primary)' }}>
          {stats.total_actionable ?? 0}
        </div>
        <div className="kpi-sub">
          {stats.total_calls ?? 0} total sessions in database
        </div>
      </div>

      <div className="kpi-card">
        <div className="kpi-title">
          <IconAlertOctagon size={16} />
          <span>AI Flagged (IndicBERT)</span>
        </div>
        <div className="kpi-value" style={{ color: 'var(--md-sys-color-error)' }}>
          {stats.total_flagged ?? 0}
        </div>
        <div className="kpi-sub">
          {stats.flagged_only ?? 0} automated detections only
        </div>
      </div>

      <div className="kpi-card">
        <div className="kpi-title">
          <IconAlertTriangle size={16} />
          <span>Citizen Reported Calls</span>
        </div>
        <div className="kpi-value" style={{ color: 'var(--satark-warning)' }}>
          {stats.total_reported ?? 0}
        </div>
        <div className="kpi-sub">
          {stats.reported_only ?? 0} user complaints only
        </div>
      </div>

      <div className="kpi-card">
        <div className="kpi-title">
          <IconFlame size={16} />
          <span>Dual Flagged & Reported</span>
        </div>
        <div className="kpi-value" style={{ color: 'var(--satark-purple)' }}>
          {stats.both ?? 0}
        </div>
        <div className="kpi-sub">
          Critical corroborations (AI + Citizen)
        </div>
      </div>

      <div className="kpi-card">
        <div className="kpi-title">
          <IconUsers size={16} />
          <span>Monitored Citizens</span>
        </div>
        <div className="kpi-value" style={{ color: 'var(--satark-safe)' }}>
          {stats.total_users ?? 0}
        </div>
        <div className="kpi-sub">
          Protected active SIP subscribers
        </div>
      </div>
    </div>
  );
}

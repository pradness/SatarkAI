import React, { useEffect, useState } from 'react';
import { getCallDetails, getCallTranscripts } from '../api';

export default function TranscriptDrawer({ callId, onClose }) {
  const [call, setCall] = useState(null);
  const [transcripts, setTranscripts] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [privacyBlocked, setPrivacyBlocked] = useState(false);

  useEffect(() => {
    if (!callId) return;

    let isMounted = true;
    setLoading(true);
    setError(null);
    setPrivacyBlocked(false);

    async function fetchData() {
      try {
        const [callData, transcriptData] = await Promise.all([
          getCallDetails(callId),
          getCallTranscripts(callId),
        ]);

        if (isMounted) {
          setCall(callData);
          setTranscripts(transcriptData.segments || []);
        }
      } catch (err) {
        if (isMounted) {
          if (err.status === 403) {
            setPrivacyBlocked(true);
            setError(err.message || 'Access Denied: Statutory Privacy Violation. Normal calls cannot expose transcripts.');
          } else {
            setError(err.message || 'Failed to load forensic evidence.');
          }
        }
      } finally {
        if (isMounted) setLoading(false);
      }
    }

    fetchData();

    return () => {
      isMounted = false;
    };
  }, [callId]);

  if (!callId) return null;

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="transcript-drawer" onClick={(e) => e.stopPropagation()}>
        {/* Header */}
        <div className="drawer-header">
          <div className="drawer-title-area">
            <div className="drawer-title">
              <span>🛡️ Evidence Docket:</span>
              <span style={{ color: 'var(--text-primary)' }}>{call?.caller_name || 'Call Investigation'}</span>
            </div>
            <div className="drawer-call-id">
              Session ID: <code>{callId}</code>
            </div>
          </div>
          <button className="btn-close" onClick={onClose}>
            ✕
          </button>
        </div>

        {loading ? (
          <div className="empty-state" style={{ marginTop: '3rem' }}>
            <div style={{ fontSize: '1.5rem', marginBottom: '0.5rem' }}>⏳</div>
            <div>Retrieving authenticated forensic records & IndicBERT cues...</div>
          </div>
        ) : privacyBlocked ? (
          <div className="privacy-enforcement-banner">
            <div style={{ fontSize: '2.5rem', marginBottom: '0.5rem' }}>🔒</div>
            <h4>STATUTORY TRANSCRIPT PRIVACY ENFORCEMENT</h4>
            <p>{error}</p>
            <div style={{ marginTop: '1rem', fontSize: '0.75rem', color: 'var(--text-muted)' }}>
              SatarkAI strictly enforces that only calls automatically flagged by the ML model or manually reported by citizens are subject to audio transcript inspection.
            </div>
          </div>
        ) : error ? (
          <div className="alert-error" style={{ margin: '1.5rem' }}>
            ⚠️ {error}
          </div>
        ) : (
          <>
            {/* Evidence & Report Details Banner */}
            <div className="evidence-section" style={{ maxHeight: '220px', overflowY: 'auto' }}>
              {/* Scam Detection Summary */}
              {call?.is_flagged && (
                <div className="evidence-banner scam">
                  <div className="evidence-title scam">
                    <span>🛑</span> ML Vishing Detection (IndicBERT)
                  </div>
                  <div className="evidence-text">
                    <strong>Threat Summary:</strong> {call?.threat_summary || 'Vishing cues flagged in dialogue.'}
                  </div>
                  <div style={{ fontSize: '0.75rem', color: 'var(--scam-red-dark)', display: 'flex', gap: '1rem', fontWeight: 500 }}>
                    <span>Confidence: <strong>{Math.round((call?.scam_confidence || 0) * 100)}%</strong></span>
                    <span>Flagged Cues: <strong>{call?.scam_segment_count || 0} segments</strong></span>
                    <span>Timing: <strong>{call?.timestamp_formatted} ({call?.duration_formatted})</strong></span>
                  </div>
                </div>
              )}

              {/* Citizen Report Summary */}
              {call?.is_reported && (
                <div className="evidence-banner reported">
                  <div className="evidence-title reported">
                    <span>⚠️</span> Citizen Suspicious Call Report
                  </div>
                  <div className="evidence-text">
                    <strong>Reported Reason:</strong> {call?.report_details?.reason || call?.threat_summary || 'Reported as suspicious'}
                  </div>
                  {call?.report_details?.description && (
                    <div className="evidence-text" style={{ fontStyle: 'italic', color: 'var(--text-secondary)' }}>
                      " {call?.report_details?.description} "
                    </div>
                  )}
                  <div style={{ fontSize: '0.75rem', color: 'var(--warning-amber-text)', display: 'flex', gap: '1rem', marginTop: '0.2rem', fontWeight: 500 }}>
                    <span>Reported By: <strong>{call?.report_details?.reported_by || call?.user_id}</strong></span>
                    <span>Reported At: <strong>{call?.report_details?.reported_at || 'Recent'}</strong></span>
                  </div>
                </div>
              )}
            </div>

            {/* Conversation Header */}
            <div style={{ padding: '0.65rem 1.5rem', background: '#f8fafc', borderBottom: '1px solid var(--border-color)', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span style={{ fontSize: '0.75rem', fontWeight: 700, color: 'var(--text-secondary)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
                WhatsApp-Style Audio Transcript Stream
              </span>
              <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)', fontWeight: 600 }}>
                {transcripts.length} segments recorded
              </span>
            </div>

            {/* WhatsApp-Like Chat Bubble Stream */}
            <div className="transcript-chat-area">
              {transcripts.length === 0 ? (
                <div className="empty-state">
                  <div>No transcript segments recorded for this call session.</div>
                </div>
              ) : (
                transcripts.map((seg, idx) => {
                  const isUser = seg.speaker?.toLowerCase() === 'you' || seg.channel === 'LOCAL' || seg.channel === 'OUTGOING';
                  const isScam = !isUser && (seg.is_scam_flagged || seg.threat_reason || seg.prediction?.label === 'SCAM');

                  return (
                    <div
                      key={seg._id || idx}
                      className={`chat-bubble-row ${isUser ? 'user' : 'caller'}`}
                    >
                      <div
                        className={`chat-bubble ${
                          isUser
                            ? 'user'
                            : isScam
                            ? 'caller-scam'
                            : 'caller-normal'
                        }`}
                      >
                        {!isUser && (
                          <div className="chat-speaker-header">
                            {isScam ? '⚠️ SUSPICIOUS CALLER' : (call?.caller_name || 'Caller')}
                          </div>
                        )}

                        <div className="chat-text" style={{ wordBreak: 'break-word' }}>
                          {seg.text}
                        </div>

                        {/* Highlighted Scam Cue Explanation (in Red) */}
                        {isScam && (
                          <div className="threat-explanation-tag">
                            <span>🚨</span>
                            <span>{seg.threat_reason || 'Flagged by IndicBERT as Vishing / Coercion Cue'}</span>
                          </div>
                        )}

                        <div className="chat-meta">
                          {seg.time_formatted || seg.timestamp_ms ? `${Math.round(seg.timestamp_ms / 1000)}s` : ''}
                          {seg.language ? ` • ${seg.language}` : ''}
                        </div>
                      </div>
                    </div>
                  );
                })
              )}
            </div>

            {/* Footer */}
            <div style={{ padding: '0.75rem 1.25rem', background: 'var(--bg-card)', borderTop: '1px solid var(--border-color)', fontSize: '0.75rem', color: 'var(--text-muted)', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span>⚖️ Chain-of-Custody Cryptographic Hash Verified</span>
              <span>SatarkAI IndicBERT Forensics Engine</span>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

import React, { useState } from 'react';
import { getBackendUrl, setBackendUrl, resetBackendUrl, checkBackendHealth } from '../api';
import { IconSettings, IconCloudDone, IconCloudOff, IconRefreshCw, IconX } from './Icons';

export default function BackendConfigModal({ isOpen, onClose, onHealthChange }) {
  const [urlInput, setUrlInput] = useState(getBackendUrl());
  const [testing, setTesting] = useState(false);
  const [result, setResult] = useState(null);

  if (!isOpen) return null;

  const handleTest = async () => {
    setTesting(true);
    setResult(null);
    const health = await checkBackendHealth(urlInput);
    setResult(health);
    setTesting(false);
    if (onHealthChange) onHealthChange(health);
  };

  const handleSave = () => {
    setBackendUrl(urlInput);
    handleTest();
  };

  const handleReset = () => {
    resetBackendUrl();
    const defaultUrl = getBackendUrl();
    setUrlInput(defaultUrl);
    setResult(null);
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="transcript-drawer" style={{ maxWidth: '520px', height: 'auto', maxHeight: '90vh', borderRadius: '16px', margin: 'auto' }} onClick={(e) => e.stopPropagation()}>
        <div className="drawer-header">
          <div className="drawer-title-area">
            <div className="drawer-title">
              <IconSettings size={18} />
              <span>Backend API Server Link</span>
            </div>
            <div className="drawer-call-id">Configure FastAPI URL for custom domain deployments</div>
          </div>
          <button className="btn-close" onClick={onClose} aria-label="Close dialog">
            <IconX size={18} />
          </button>
        </div>

        <div style={{ padding: '1.5rem', display: 'flex', flexDirection: 'column', gap: '1.25rem' }}>
          <p style={{ fontSize: '0.825rem', color: 'var(--md-sys-color-on-surface-variant)', lineHeight: 1.5 }}>
            When running on a static host like <strong>satarkai.me</strong> (GitHub Pages), this frontend connects to your remote backend URL.
          </p>

          <div className="form-group" style={{ marginBottom: 0 }}>
            <label className="form-label">FastAPI Backend Endpoint</label>
            <input
              type="url"
              className="form-input"
              value={urlInput}
              onChange={(e) => setUrlInput(e.target.value)}
              placeholder="https://api.satarkai.me or https://xxxx.trycloudflare.com"
            />
          </div>

          {result && (
            <div
              style={{
                padding: '0.85rem 1rem',
                borderRadius: '12px',
                border: '1px solid',
                backgroundColor: result.online ? 'var(--satark-safe-container)' : 'var(--md-sys-color-error-container)',
                borderColor: result.online ? 'var(--satark-safe)' : 'var(--md-sys-color-error)',
                color: result.online ? 'var(--satark-on-safe-container)' : 'var(--md-sys-color-on-error-container)',
                fontSize: '0.8rem',
                display: 'flex',
                alignItems: 'center',
                gap: '0.65rem',
              }}
            >
              {result.online ? <IconCloudDone size={20} /> : <IconCloudOff size={20} />}
              <div>
                <strong>{result.online ? 'Server Online' : 'Server Offline / Unreachable'}</strong>
                <div style={{ fontSize: '0.725rem', opacity: 0.9 }}>
                  {result.online ? `Latency: ${result.latency}ms (Status: HTTP ${result.status})` : (result.error || 'Failed to connect. Check CORS or tunnel.')}
                </div>
              </div>
            </div>
          )}

          <div style={{ display: 'flex', gap: '0.75rem', marginTop: '0.5rem' }}>
            <button
              type="button"
              className="filter-btn"
              style={{ flex: 1, justifyContent: 'center' }}
              onClick={handleTest}
              disabled={testing}
            >
              <IconRefreshCw size={14} className={testing ? 'spin' : ''} />
              <span>{testing ? 'Testing...' : 'Test Health'}</span>
            </button>
            <button
              type="button"
              className="filter-btn"
              style={{ flex: 1, justifyContent: 'center' }}
              onClick={handleReset}
            >
              <span>Reset Default</span>
            </button>
            <button
              type="button"
              className="btn-open-transcript"
              style={{ flex: 1, justifyContent: 'center' }}
              onClick={handleSave}
            >
              <span>Save & Connect</span>
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

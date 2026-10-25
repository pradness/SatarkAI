const API_BASE = '';

export function getAuthToken() {
  return localStorage.getItem('satarkai_jwt_token');
}

export function setAuthToken(token) {
  if (token) {
    localStorage.getItem('satarkai_jwt_token', token);
    localStorage.setItem('satarkai_jwt_token', token);
  } else {
    localStorage.removeItem('satarkai_jwt_token');
  }
}

export function getCurrentUser() {
  const data = localStorage.getItem('satarkai_user');
  try {
    return data ? JSON.parse(data) : null;
  } catch (e) {
    return null;
  }
}

export function setCurrentUser(user) {
  if (user) {
    localStorage.setItem('satarkai_user', JSON.stringify(user));
  } else {
    localStorage.removeItem('satarkai_user');
  }
}

export function logout() {
  localStorage.removeItem('satarkai_jwt_token');
  localStorage.removeItem('satarkai_user');
}

async function request(endpoint, options = {}) {
  const token = getAuthToken();
  const headers = {
    'Content-Type': 'application/json',
    ...(options.headers || {}),
  };

  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }

  const response = await fetch(`${API_BASE}${endpoint}`, {
    ...options,
    headers,
  });

  const isJson = response.headers.get('content-type')?.includes('application/json');
  const data = isJson ? await response.json() : await response.text();

  if (!response.ok) {
    let errorDetail = 'Request failed';
    if (typeof data === 'object' && data !== null && data.detail) {
      errorDetail = data.detail;
    } else if (typeof data === 'string' && data.length > 0) {
      errorDetail = data;
    }
    const err = new Error(errorDetail);
    err.status = response.status;
    err.data = data;
    throw err;
  }

  return data;
}

// Auth Endpoints
export async function loginProfessional(email, password) {
  const res = await request('/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  });
  if (res.access_token) {
    setAuthToken(res.access_token);
    setCurrentUser(res.user);
  }
  return res;
}

export async function registerProfessional(payload) {
  return request('/auth/register', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

// Investigation Endpoints
export async function getStats() {
  return request('/investigation/stats');
}

export async function getInvestigationCalls({ filterType = 'all', search = '', sortBy = 'date_desc', page = 1, limit = 15 } = {}) {
  const params = new URLSearchParams();
  if (filterType) params.append('filter_type', filterType);
  if (search && search.trim()) params.append('search', search.trim());
  if (sortBy) params.append('sort_by', sortBy);
  if (page) params.append('page', page);
  if (limit) params.append('limit', limit);

  return request(`/investigation/calls?${params.toString()}`);
}

export async function getCallDetails(callId) {
  return request(`/investigation/calls/${callId}`);
}

export async function getCallTranscripts(callId) {
  return request(`/investigation/calls/${callId}/transcripts`);
}

export async function getMonitoredUsers({ search = '', page = 1, limit = 20 } = {}) {
  const params = new URLSearchParams();
  if (search && search.trim()) params.append('search', search.trim());
  if (page) params.append('page', page);
  if (limit) params.append('limit', limit);

  return request(`/investigation/users?${params.toString()}`);
}

export async function getUserProfile(userId) {
  return request(`/investigation/users/${userId}`);
}

export async function getInvestigatorsList(statusFilter = 'all') {
  return request(`/admin/investigators?status_filter=${statusFilter}`);
}

export async function updateInvestigatorStatus(userId, newStatus) {
  return request(`/admin/investigators/${userId}`, {
    method: 'PATCH',
    body: JSON.stringify({ new_status: newStatus }),
  });
}

(function (global) {
  'use strict';

  function postCredentials(route, name, password) {
    return fetch(route, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: name, password: password })
    });
  }

  async function errorOf(response) {
    const body = await response.json().catch(function () { return {}; });
    return new Error(body.error || '요청을 처리하지 못했습니다.');
  }

  async function login(name, password) {
    const response = await postCredentials('/api/auth/login', name, password);
    if (!response.ok) throw new Error('이름 또는 비밀번호가 올바르지 않습니다.');
  }

  global.HomeSecondBrainAuth = Object.freeze({
    restore: function () {
      return fetch('/api/auth/session');
    },
    login: login,
    signUp: async function (name, password) {
      const response = await postCredentials('/api/auth/signup', name, password);
      if (!response.ok) throw await errorOf(response);
      await login(name, password);
    },
    logout: function () {
      return fetch('/api/auth/logout', { method: 'POST' });
    }
  });
})(window);

(function (global) {
  'use strict';

  const sessionRoute = '/api/auth/session';

  global.HomeSecondBrainAuth = Object.freeze({
    establish: function (token) {
      return fetch(sessionRoute, {
        method: 'POST',
        headers: { Authorization: 'Bearer ' + token }
      });
    },
    restore: function () {
      return fetch(sessionRoute);
    }
  });
})(window);

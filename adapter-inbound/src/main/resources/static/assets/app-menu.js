// Adds the shared feature menu to the start of the page's <main>. Needs /assets/http-auth-session.js.
(function (global, document) {
  'use strict';

  const FEATURES = [
    { path: '/conversation', name: '대화' },
    { path: '/finance', name: '지출' },
    { path: '/family', name: '가족' },
    { path: '/knowledge', name: '지식 주입' }
  ];

  const MENU_HTML =
    '<div class="menu-bar">' +
    '  <button type="button" class="menu-toggle" aria-label="메뉴 열기" aria-controls="menu" aria-expanded="false">' +
    '    <svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">' +
    '      <path d="M4 6h16M4 12h16M4 18h16"/>' +
    '    </svg>' +
    '  </button>' +
    '</div>' +
    '<dialog id="menu" class="drawer" aria-label="주요 기능">' +
    '  <button type="button" class="menu-close">닫기</button>' +
    '  <nav><ul></ul></nav>' +
    '  <div class="menu-account" hidden><button type="button" class="menu-logout">로그아웃</button></div>' +
    '</dialog>';

  function featureItem(feature) {
    const item = document.createElement('li');
    const link = document.createElement('a');
    link.href = feature.path;
    link.textContent = feature.name;
    if (global.location.pathname === feature.path) link.setAttribute('aria-current', 'page');
    item.appendChild(link);
    return item;
  }

  // Shows the logout button only while the session is valid, checked each time the menu opens.
  async function showAccountOf(account) {
    const response = await global.HomeSecondBrainAuth.restore();
    account.hidden = !response.ok;
  }

  async function logout() {
    await global.HomeSecondBrainAuth.logout();
    global.location.reload();
  }

  function installMenu() {
    const container = document.createElement('div');
    container.innerHTML = MENU_HTML;
    const toggle = container.querySelector('.menu-toggle');
    const menu = container.querySelector('.drawer');
    const account = container.querySelector('.menu-account');
    container.querySelector('ul').append(...FEATURES.map(featureItem));

    toggle.addEventListener('click', function () {
      menu.showModal();
      toggle.setAttribute('aria-expanded', 'true');
      showAccountOf(account);
    });
    container.querySelector('.menu-close').addEventListener('click', function () {
      menu.close();
    });
    menu.addEventListener('click', function (event) {
      if (event.clientX > menu.getBoundingClientRect().right) menu.close();
    });
    menu.addEventListener('close', function () {
      toggle.setAttribute('aria-expanded', 'false');
    });
    container.querySelector('.menu-logout').addEventListener('click', logout);

    document.querySelector('main').prepend(...container.children);
  }

  installMenu();
})(window, document);

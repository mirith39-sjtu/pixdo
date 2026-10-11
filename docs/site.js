/* Pixdo 站点脚本：移动端侧边抽屉导航 + Windows / Android 双端自适应
   预览指定平台：在任意页面地址后加 ?os=windows 或 ?os=android */
(function () {
  'use strict';

  var EXE_URL = 'https://pixdo-download.oss-cn-hangzhou.aliyuncs.com/pixdo-v1.3.exe';
  var APK_URL = 'https://gh-proxy.com/https://github.com/mirith39-sjtu/pixdo/releases/download/v1.3/pixdo-v1.3.apk';
  var OS_NAME = { windows: 'Windows', android: 'Android' };

  /* ---------- 设备识别 ---------- */
  function detectOS() {
    var forced = null;
    try { forced = new URLSearchParams(window.location.search).get('os'); } catch (e) { /* 忽略 */ }
    if (forced === 'windows' || forced === 'android') return forced;
    var ua = (navigator.userAgent || '').toLowerCase();
    if (ua.indexOf('android') !== -1) return 'android';
    if (ua.indexOf('windows') !== -1) return 'windows';
    if (ua.indexOf('iphone') !== -1 || ua.indexOf('ipad') !== -1 || ua.indexOf('ipod') !== -1) return 'ios';
    return 'other';
  }

  var os = detectOS();
  document.documentElement.setAttribute('data-os', os);

  /* ---------- 移动端侧边抽屉 ---------- */
  var toggle = document.querySelector('.nav-toggle');
  var drawer = document.getElementById('site-drawer');
  var scrim = document.getElementById('drawer-scrim');

  function setOpen(open) {
    document.body.classList.toggle('nav-open', open);
    if (toggle) toggle.setAttribute('aria-expanded', open ? 'true' : 'false');
    if (drawer) drawer.setAttribute('aria-hidden', open ? 'false' : 'true');
  }

  if (toggle && drawer && scrim) {
    toggle.addEventListener('click', function () {
      setOpen(!document.body.classList.contains('nav-open'));
    });
    scrim.addEventListener('click', function () { setOpen(false); });
    drawer.addEventListener('click', function (event) {
      if (event.target.closest('a, [data-drawer-close]')) setOpen(false);
    });
    document.addEventListener('keydown', function (event) {
      if (event.key === 'Escape') setOpen(false);
    });
    var wide = window.matchMedia('(min-width: 861px)');
    var onWide = function (event) { if (event.matches) setOpen(false); };
    if (wide.addEventListener) wide.addEventListener('change', onWide);
    else if (wide.addListener) wide.addListener(onWide);
  }

  /* ---------- 双端自适应（下载页 / 使用页 / 首屏按钮） ---------- */
  var device = OS_NAME[os];
  if (!device) return;

  document.querySelectorAll('[data-os]').forEach(function (element) {
    if (element.getAttribute('data-os') === os) element.classList.add('is-you');
  });

  var note = document.querySelector('[data-device-note]');
  if (note) {
    note.textContent = (note.getAttribute('data-device-note') || '').replace('{os}', device);
    note.hidden = false;
  }

  var heroBtn = document.querySelector('[data-hero-dl]');
  if (heroBtn) {
    heroBtn.href = os === 'windows' ? EXE_URL : APK_URL;
    heroBtn.textContent = '下载 ' + device + ' 版';
    var heroMore = document.querySelector('[data-hero-more]');
    if (heroMore) heroMore.hidden = false;
  }
})();

/**
 * 管理后台公共鉴权脚本（10 个后台页面共用）。
 *
 * 职责：
 *  1. 包装 window.fetch，接口返回 401（登录态已过期）时统一跳回登录页；
 *  2. 把当前管理员用户名填进导航栏的管理员槽位。
 *
 * 说明：管理员槽位的 DOM（用户名占位 + 退出按钮）由模板片段
 * templates/admin/fragments/nav.html 直接渲染，本脚本不再创建元素，
 * 只负责把异步取到的用户名填进去，因此页面不会出现「先空后闪」。
 * 「退出登录」按钮通过 onclick 直接调用本文件导出的 window.adminLogout。
 *
 * 页面访问控制由后端 SecurityConfig 负责（未登录访问 /admin/** 会被重定向到
 * /admin/login），本脚本只处理「页面已打开但登录态失效」的情况与登录态展示。
 */
(function () {
  'use strict';

  var LOGIN_URL = 'login.html';

  /* ---------- 1. 401 统一跳登录页 ---------- */
  if (typeof window.fetch === 'function') {
    var rawFetch = window.fetch.bind(window);
    window.fetch = function (input, init) {
      return rawFetch(input, init).then(function (response) {
        if (response.status === 401) {
          var url = typeof input === 'string' ? input : (input && input.url ? input.url : '');
          // 登录/登出接口自身的 401 交给调用方处理，避免死循环
          if (url.indexOf('/api/admin/auth/') !== 0) {
            window.location.replace(LOGIN_URL);
          }
        }
        return response;
      });
    };
  }

  /* ---------- 2. 退出登录 ---------- */
  window.adminLogout = function () {
    window.fetch('/api/admin/auth/logout', { method: 'POST', credentials: 'same-origin' })
      .catch(function () { /* 忽略网络异常，本地跳转即可 */ })
      .then(function () {
        window.location.replace(LOGIN_URL);
      });
  };

  /* ---------- 3. 填充导航栏中的管理员用户名 ---------- */
  function fillAdminName() {
    var node = document.getElementById('adminAuthName');
    if (!node) {
      return;
    }

    window.fetch('/api/admin/auth/me', { credentials: 'same-origin' })
      .then(function (response) {
        if (!response.ok) {
          return null;
        }
        return response.json();
      })
      .then(function (result) {
        var data = result && result.data;
        var label = data && (data.nickname || data.username);
        if (label) {
          node.textContent = label;
        }
      })
      .catch(function () { /* 忽略：服务端已保证页面只有管理员可见 */ });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', fillAdminName);
  } else {
    fillAdminName();
  }
})();

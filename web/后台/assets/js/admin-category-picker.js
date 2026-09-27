/* ==========================================================================
   书籍分类级联选择器 —— 全项目唯一实现
   --------------------------------------------------------------------------
   用途：管理后台所有「设置/筛选书籍分类」的位置（数据导入页、书籍管理页）。
   结构：主分类（单选下拉）+ 子分类（多选 chip）+ 下拉里的「+ 添加新分类…」
   数据源：GET /api/books/category-tree（主分类/子分类/书库中未登记的子分类名）
   新增分类：POST /api/admin/categories {name, parentId|parent}

   用法（声明式，动态渲染的行渲染完记得再调一次 initAll）：
     <div data-cat-picker data-main="男生" data-subs='["玄幻"]' data-compact="1"></div>
     CategoryPicker.initAll(rootEl)          // 返回实例数组
     CategoryPicker.of('filterPicker').getValue()   // 字符串按 id 查（也可直接传元素）
     CategoryPicker.of('filterPicker').getSubsJson()
     CategoryPicker.refreshAll()             // 分类树变化后刷新所有已挂载实例
   ========================================================================== */
window.CategoryPicker = (function () {
  'use strict';

  var NEW_VALUE = '__cp_new__';
  var MANAGER_VALUE = '__cp_manager__';
  var tree = { mains: [], librarySubs: [] };
  var treePromise = null;
  var mounted = [];

  function getJson(url) {
    return fetch(url, { headers: { 'Accept': 'application/json' } }).then(function (r) {
      return r.json();
    });
  }

  /** 拉取分类树（带缓存；force=true 强制重取） */
  function load(force) {
    if (treePromise && !force) return treePromise;
    treePromise = getJson('/api/books/category-tree').then(function (d) {
      var data = (d && d.data) || {};
      tree = {
        mains: (data.mains || []).map(function (m) {
          return { id: m.id, name: m.name, subs: (m.subs || []).slice() };
        }),
        librarySubs: (data.librarySubs || []).slice()
      };
      return tree;
    }).catch(function () {
      // 分类树取不到不该让整页不可用：首次退化为「只有添加新分类」的空树；
      // 已经有数据时保留原样 —— 刷新失败不能把选择框里的选项清空。
      if (!tree.mains.length) {
        tree = { mains: [], librarySubs: [] };
      }
      return tree;
    });
    return treePromise;
  }

  function mainNames() {
    return tree.mains.map(function (m) { return m.name; });
  }

  function findMain(name) {
    for (var i = 0; i < tree.mains.length; i++) {
      if (tree.mains[i].name === name) return tree.mains[i];
    }
    return null;
  }

  function subsOf(mainName) {
    var m = findMain(mainName);
    return m ? m.subs.slice() : [];
  }

  /** 容错解析初始子分类：JSON 数组 / 逗号分隔 / 空 */
  function parseSubs(raw) {
    if (!raw) return [];
    var s = String(raw).trim();
    if (!s) return [];
    if (s.charAt(0) === '[') {
      try {
        var arr = JSON.parse(s);
        if (Array.isArray(arr)) return arr.map(String).filter(Boolean);
      } catch (e) { /* 退化为逗号切分 */ }
      s = s.replace(/^\[|\]$/g, '');
    }
    return s.split(/[,，;；]/).map(function (x) {
      return x.replace(/"/g, '').trim();
    }).filter(Boolean);
  }

  function uniq(arr) {
    var seen = {}, out = [];
    arr.forEach(function (x) {
      if (x && !seen[x]) { seen[x] = 1; out.push(x); }
    });
    return out;
  }

  /**
   * 某个主分类下可选的子分类：
   * 分类表里登记的 + 书库中出现过但未登记的（老数据迁移来的值必须还能选/能取消）
   */
  function subOptions(mainName, selected) {
    var list = subsOf(mainName);
    if (!list.length) list = [];
    var extra = tree.librarySubs.filter(function (n) { return list.indexOf(n) < 0; });
    return uniq(list.concat(extra).concat(selected));
  }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  /* ====================== 单个选择器实例 ====================== */

  function create(el) {
    var state = {
      main: el.dataset.main || '',
      subs: parseSubs(el.dataset.subs)
    };
    var option = {
      compact: el.dataset.compact === '1',
      withEmpty: el.dataset.withEmpty !== '0',
      emptyText: el.dataset.emptyText || '未指定主分类',
      onChange: null
    };
    var expanded = false;   // compact 模式下子分类 chips 是否展开
    var newPanelOpen = false;

    function fire() {
      if (typeof option.onChange === 'function') option.onChange(state.main, state.subs.slice());
    }

    function mainOptionsHtml() {
      var html = '';
      if (option.withEmpty) {
        html += '<option value=""' + (state.main ? '' : ' selected') + '>' + esc(option.emptyText) + '</option>';
      }
      var known = mainNames();
      known.forEach(function (name) {
        html += '<option value="' + esc(name) + '"' + (state.main === name ? ' selected' : '') + '>'
          + esc(name) + '</option>';
      });
      // 库里已有但分类表未登记的主分类值（例如手工改库留下的），也要能显示出来
      if (state.main && known.indexOf(state.main) < 0) {
        html += '<option value="' + esc(state.main) + '" selected>' + esc(state.main) + '</option>';
      }
      html += '<option value="' + NEW_VALUE + '">+ 添加新分类…</option>';
      html += '<option value="' + MANAGER_VALUE + '">分类管理（改名 / 删除）…</option>';
      return html;
    }

    function subsHtml() {
      var list = subOptions(state.main, state.subs);
      if (!list.length) {
        return '<span class="cp-hint">先选主分类，再选子分类</span>';
      }
      return list.map(function (name) {
        var on = state.subs.indexOf(name) >= 0;
        return '<label class="cp-chip' + (on ? ' on' : '') + '">'
          + '<input type="checkbox" value="' + esc(name) + '"' + (on ? ' checked' : '') + '>'
          + '<span>' + esc(name) + '</span></label>';
      }).join('');
    }

    function subsSummary() {
      if (!state.subs.length) return '子分类：未选';
      if (state.subs.length <= 2) return '子分类：' + state.subs.join('、');
      return '子分类：' + state.subs.slice(0, 2).join('、') + ' +' + (state.subs.length - 2);
    }

    function newPanelHtml() {
      var asChild = !!state.main;
      var html = '<div class="cp-new-row">'
        + '<input type="text" class="form-control form-control-sm cp-new-name" maxlength="50" placeholder="新分类名称">'
        + '<select class="form-select form-select-sm cp-new-scope">';
      if (asChild) {
        html += '<option value="sub">作为「' + esc(state.main) + '」的子分类</option>';
      }
      html += '<option value="main"' + (asChild ? '' : ' selected') + '>作为新的主分类</option>';
      html += '</select>'
        + '<button type="button" class="btn btn-sm btn-primary cp-new-ok">添加</button>'
        + '<button type="button" class="btn btn-sm btn-outline-secondary cp-new-cancel">取消</button>'
        + '<div class="cp-new-msg"></div>'
        + '</div>';
      html += '<div class="form-text cp-new-tip">新分类会立即出现在所有分类选择框里；主分类会同步成为书城「本站藏书」的一个子页面。</div>';
      return html;
    }

    function render() {
      // 重画会重建 DOM，正在输入的新分类名会丢 —— 先记下来再还原。
      // refreshAll() 会在分类树变化时重画所有实例（含窗口重新获得焦点时的自动同步）。
      var active = document.activeElement;
      var keepNew = !!active && el.contains(active) && active.classList.contains('cp-new-name');
      var keepValue = keepNew ? active.value : null;

      var html = '<div class="cp-row">'
        + '<select class="form-select form-select-sm cp-main">' + mainOptionsHtml() + '</select>';
      if (option.compact) {
        html += '<button type="button" class="btn btn-sm btn-outline-secondary cp-subs-toggle">'
          + esc(subsSummary()) + '</button>';
      }
      html += '</div>';
      html += '<div class="cp-subs' + (option.compact && !expanded ? ' d-none' : '') + '">' + subsHtml() + '</div>';
      html += '<div class="cp-new' + (newPanelOpen ? '' : ' d-none') + '">' + newPanelHtml() + '</div>';
      el.className = 'cat-picker' + (option.compact ? ' cat-picker-compact' : '');
      el.innerHTML = html;
      if (keepNew) {
        var input = el.querySelector('.cp-new-name');
        if (input) { input.value = keepValue; input.focus(); }
      }
      if (el.dataset.disabled === '1') setDisabled(true);
    }

    function setDisabled(disabled) {
      el.dataset.disabled = disabled ? '1' : '0';
      el.querySelectorAll('select, input, button').forEach(function (node) {
        node.disabled = !!disabled;
      });
    }

    function onMainChange(value) {
      if (value === MANAGER_VALUE) {
        // 「分类管理」不是分类值：开新标签页去管理页，下拉回到原选项
        window.open('categories.html', '_blank');
        render();
        return;
      }
      if (value === NEW_VALUE) {
        newPanelOpen = true;
        render();
        var input = el.querySelector('.cp-new-name');
        if (input) input.focus();
        return;
      }
      state.main = value;
      // 换主分类时保留仍在该主分类下的子分类，其余丢弃（避免出现「男生」挂着「宫斗宅斗」这种脏组合）
      var allowed = subOptions(value, []);
      state.subs = state.subs.filter(function (n) { return allowed.indexOf(n) >= 0; });
      expanded = !option.compact ? true : expanded;
      render();
      fire();
    }

    function toggleSub(name, checked) {
      state.subs = parseSubs(JSON.stringify(state.subs));
      var i = state.subs.indexOf(name);
      if (checked && i < 0) state.subs.push(name);
      if (!checked && i >= 0) state.subs.splice(i, 1);
      render();
      fire();
    }

    function openNewPanel() {
      newPanelOpen = true;
      render();
      var input = el.querySelector('.cp-new-name');
      if (input) input.focus();
    }

    function submitNew() {
      var input = el.querySelector('.cp-new-name');
      var scope = el.querySelector('.cp-new-scope');
      var msg = el.querySelector('.cp-new-msg');
      var name = input ? input.value.replace(/["\\]/g, '').trim() : '';
      var asSub = scope ? scope.value === 'sub' : false;
      if (!name) {
        if (msg) msg.textContent = '请输入分类名称';
        return;
      }
      var payload = asSub && state.main ? { name: name, parent: state.main } : { name: name };
      var okBtn = el.querySelector('.cp-new-ok');
      if (okBtn) { okBtn.disabled = true; okBtn.textContent = '添加中…'; }
      if (msg) msg.textContent = '';

      fetch('/api/admin/categories', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      }).then(function (r) { return r.json(); }).then(function (d) {
        if (!d || !d.success) {
          if (msg) msg.textContent = (d && d.message) || '添加失败';
          if (okBtn) { okBtn.disabled = false; okBtn.textContent = '添加'; }
          return;
        }
        // 成功：刷新分类树 → 同步所有选择框 → 自动选中新分类
        load(true).then(function () {
          if (asSub) {
            if (state.subs.indexOf(name) < 0) state.subs.push(name);
          } else {
            state.main = name;
            state.subs = [];
          }
          newPanelOpen = false;
          refreshAll();
          render();
          fire();
        });
      }).catch(function () {
        if (msg) msg.textContent = '网络异常，添加失败';
        if (okBtn) { okBtn.disabled = false; okBtn.textContent = '添加'; }
      });
    }

    el.addEventListener('change', function (e) {
      if (e.target.classList.contains('cp-main')) onMainChange(e.target.value);
      if (e.target.type === 'checkbox' && e.target.closest('.cp-subs')) {
        toggleSub(e.target.value, e.target.checked);
      }
    });

    el.addEventListener('click', function (e) {
      if (e.target.closest('.cp-subs-toggle')) {
        expanded = !expanded;
        render();
        return;
      }
      if (e.target.closest('.cp-new-ok')) { submitNew(); return; }
      if (e.target.closest('.cp-new-cancel')) {
        newPanelOpen = false;
        render();
        return;
      }
      if (e.target.closest('.cp-new-name') || e.target.closest('.cp-new-scope')) return;
    });

    el.addEventListener('keydown', function (e) {
      if (e.target.classList.contains('cp-new-name') && e.key === 'Enter') {
        e.preventDefault();
        submitNew();
      }
    });

    var instance = {
      el: el,
      option: option,
      getValue: function () { return { main: state.main, subs: state.subs.slice() }; },
      getMain: function () { return state.main; },
      getSubs: function () { return state.subs.slice(); },
      getSubsJson: function () { return JSON.stringify(state.subs); },
      setValue: function (main, subs) {
        state.main = main || '';
        state.subs = uniq(subs || []);
        render();
      },
      reset: function () { state.main = ''; state.subs = []; newPanelOpen = false; render(); },
      /** 分类树变化后只重画选项，保留当前值 */
      refresh: function () { render(); },
      setDisabled: setDisabled,
      disable: function () { setDisabled(true); },
      enable: function () { setDisabled(false); }
    };
    el.__catPicker = instance;
    mounted.push(instance);
    render();
    return instance;
  }

  function initAll(root) {
    var scope = root || document;
    var list = [];
    scope.querySelectorAll('[data-cat-picker]').forEach(function (el) {
      if (!el.__catPicker) list.push(create(el));
    });
    return list;
  }

  function refreshAll() {
    mounted = mounted.filter(function (inst) { return document.body.contains(inst.el); });
    mounted.forEach(function (inst) { inst.refresh(); });
  }

  function of(el) {
    // 传字符串按 id 取（调用点写的是 CategoryPicker.of('filterPicker') 这种裸 id，
    // 直接丢给 querySelector 会被当成标签名，恒返回 null —— 曾导致筛选不生效、保存丢分类）
    if (typeof el === 'string') {
      el = document.getElementById(el) || document.querySelector(el);
    }
    return el ? el.__catPicker : null;
  }

  /** 分类树指纹：用来判断刷新前后有没有变化，没变就不重画（大清单里重画很贵） */
  function treeSignature() {
    return tree.mains.map(function (m) {
      return m.id + ':' + m.name + ':' + m.subs.join(',');
    }).join('|') + '#' + tree.librarySubs.join(',');
  }

  // 「分类管理」通常开在另一个标签页：回到本页时同步一次分类树，
  // 免得选择框里还挂着已改名/已删除的分类。输入框内容由 render() 保护。
  window.addEventListener('focus', function () {
    if (!treePromise) return;   // 还没加载过就别抢跑
    var before = treeSignature();
    load(true).then(function () {
      if (treeSignature() !== before) refreshAll();
    });
  });

  return {
    load: load,
    initAll: initAll,
    refreshAll: refreshAll,
    of: of,
    mainNames: mainNames,
    subsOf: subsOf,
    esc: esc
  };
})();

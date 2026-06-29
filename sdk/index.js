/**
 * 青云扑克策略引擎 SDK — V1.0
 * 真正加载策略引擎、mock环境、执行preF()/postF()/decide()、断言决策
 * 运行: node sdk/test.js
 */

'use strict';

var fs = require('fs');
var path = require('path');

// ============================================
// 1. Mock环境（模拟浏览器）
// ============================================

// localStorage mock
var _store = {};
var localStorage = {
  getItem: function(k) { return _store[k] !== undefined ? _store[k] : null; },
  setItem: function(k, v) { _store[k] = String(v); },
  removeItem: function(k) { delete _store[k]; },
  clear: function() { _store = {}; },
  key: function(i) { var keys = Object.keys(_store); return keys[i] || null; },
  get length() { return Object.keys(_store).length; }
};
global.localStorage = localStorage;

// Mock element factory
function mockElement(id) {
  var el = {
    id: id || '',
    textContent: '',
    innerHTML: '',
    className: '',
    value: '',
    checked: false,
    style: {},
    classList: {
      add: function() {},
      remove: function() {},
      toggle: function() {},
      contains: function() { return false; }
    },
    addEventListener: function() {},
    removeEventListener: function() {},
    click: function() {},
    appendChild: function() { return null; },
    removeChild: function() { return null; },
    setAttribute: function() {},
    getAttribute: function() { return ''; },
    removeAttribute: function() {},
    querySelectorAll: function() { return []; },
    querySelector: function() { return null; },
    children: [],
    parentNode: null,
    offsetWidth: 100,
    offsetHeight: 100,
    style: { display: '', cssText: '' }
  };
  return el;
}

// document mock
var _elements = {};
var document = {
  createElement: function(tag) { return mockElement(); },
  getElementById: function(id) {
    if (!_elements[id]) _elements[id] = mockElement(id);
    return _elements[id];
  },
  querySelector: function(sel) { return mockElement(); },
  querySelectorAll: function(sel) { return []; },
  body: mockElement('body'),
  head: mockElement('head'),
  documentElement: mockElement('html'),
  addEventListener: function() {},
  removeEventListener: function() {},
  createTextNode: function(t) { return { textContent: t }; },
  readyState: 'complete'
};
global.document = document;

// window mock
global.window = {
  addEventListener: function() {},
  removeEventListener: function() {},
  location: { href: '', search: '' },
  innerWidth: 1080,
  innerHeight: 2400,
  setTimeout: setTimeout,
  clearTimeout: clearTimeout,
  setInterval: setInterval,
  clearInterval: clearInterval,
  alert: function() {},
  confirm: function() { return true; },
  prompt: function() { return ''; },
  fetch: function() { return Promise.resolve({ ok: false }); }
};

// URL.createObjectURL / revokeObjectURL mock (Worker相关)
global.URL = {
  createObjectURL: function() { return 'blob:mock'; },
  revokeObjectURL: function() {}
};

// Blob mock (Worker相关)
global.Blob = function(parts, opts) {
  this.parts = parts;
  this.opts = opts;
};

// Worker mock — 直接同步执行，避免异步MC
global.Worker = function(url) {
  this.onmessage = null;
  this.onerror = null;
  // 不真正创建Worker，MC会降级到同步
};

// XMLHttpRequest mock
global.XMLHttpRequest = function() {
  this.open = function() {};
  this.send = function() {};
  this.setRequestHeader = function() {};
  this.onreadystatechange = null;
  this.readyState = 4;
  this.status = 200;
  this.responseText = '{}';
};

// navigator mock (global.navigator is read-only getter in Node 22)
try { global.navigator = { userAgent: 'Node.js SDK', onLine: true }; } catch(e) {
  Object.defineProperty(global, 'navigator', { value: { userAgent: 'Node.js SDK', onLine: true }, writable: true, configurable: true });
}

// Image mock
global.Image = function() { this.src = ''; this.onload = null; this.onerror = null; };

// ============================================
// 2. 加载策略引擎
// ============================================

var HTML_PATH = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'poker_helper.html');

function loadEngine() {
  var content = fs.readFileSync(HTML_PATH, 'utf8');
  
  // 策略引擎在第二个<script>块（L426~L8989）
  // 直接按行号提取，避免正则匹配问题
  var lines = content.split('\n');
  
  // 找到第二个<script>的起始行
  var scriptCount = 0;
  var startLine = -1;
  var endLine = -1;
  for (var i = 0; i < lines.length; i++) {
    if (lines[i].indexOf('<script>') !== -1) {
      scriptCount++;
      if (scriptCount === 2) startLine = i + 1; // 跳过<script>行本身
    }
    if (scriptCount === 2 && lines[i].indexOf('</script>') !== -1 && i > startLine) {
      endLine = i;
      break;
    }
  }
  
  if (startLine < 0 || endLine < 0) throw new Error('Cannot find second <script> block');
  
  var jsCode = lines.slice(startLine, endLine).join('\n');
  return jsCode;
}

// 执行引擎代码到全局作用域
var _engineLoaded = false;
function initEngine() {
  if (_engineLoaded) return;
  
  // 重置localStorage
  _store = {};
  
  // 重置elements缓存
  _elements = {};
  
  var jsCode = loadEngine();
  
  // 策略引擎的var/function在Node.js require()模块作用域下不会进global
  // 方案：
  // 1. 只替换var声明为global赋值（不会导致ASI问题）
  // 2. function声明保持原样，在代码末尾追加 global.xxx=xxx; 导出
  //    （避免function→赋值表达式后}紧跟(被解析为函数调用的ASI陷阱）
  
  // 收集所有顶层函数名（用于末尾导出）
  var topFnNames = [];
  var fnRe = /^function\s+(\w+)\s*\(/gm;
  var fm;
  while ((fm = fnRe.exec(jsCode)) !== null) topFnNames.push(fm[1]);
  
  // 只替换var声明
  var jlines = jsCode.split('\n');
  var processed = [];
  for (var i = 0; i < jlines.length; i++) {
    var line = jlines[i];
    line = line.replace(/^var\s+(\w+)\s*=/, 'global.$1=');
    processed.push(line);
  }
  
  var globalJsCode = processed.join('\n');
  
  // 在代码末尾追加：把function声明挂到global上
  var exportLines = ['// SDK: 导出function声明到global'];
  topFnNames.forEach(function(name) {
    exportLines.push('global.' + name + '=' + name + ';');
  });
  globalJsCode += '\n' + exportLines.join('\n') + '\n';
  
  // 补充第一个<script>块中的API_BASE常量
  globalJsCode = "var API_BASE=\"http://127.0.0.1:8666\";\n" + globalJsCode;
  
  // 写临时文件用require加载（eval在严格模式下有限制）
  var tmpFile = path.join(__dirname, '_engine_tmp.js');
  fs.writeFileSync(tmpFile, globalJsCode, 'utf8');
  
  try {
    // 清除require缓存，确保重新加载
    delete require.cache[require.resolve(tmpFile)];
    require(tmpFile);
    _engineLoaded = true;
    
    // 验证关键全局变量
    if (!global.G) throw new Error('G not defined after engine load');
    if (!global.preF) throw new Error('preF not defined after engine load');
    
    console.log('[SDK] 引擎加载成功 — G.phase=' + global.G.phase + ', preF=' + typeof global.preF + ', postF=' + typeof global.postF);
  } catch (e) {
    console.error('[SDK] 引擎加载失败:', e.message);
    console.error('[SDK] 堆栈:', e.stack ? e.stack.split('\n').slice(0, 5).join('\n') : '');
    throw e;
  }
}

// ============================================
// 3. 场景构造工具
// ============================================

/**
 * 设置G对象（全局游戏状态）
 * @param {Object} opts - 覆盖G的字段
 */
function setupG(opts) {
  // 引擎变量在global上（vm.runInThisContext声明）
  var g = global.G;
  if (!g) throw new Error('G not defined - call initEngine() first');
  
  // 默认5-max GG扑克 200/500盲注场景
  var defaults = {
    phase: 'pre',
    scene: 'open',
    tt: 5,
    pos: 'btn',
    act: 5,
    opp: 'unknown',
    stk: 10000,
    pot: 750,
    bet: 0,
    ante: 50,
    hole: [null, null],
    comm: [null, null, null, null, null],
    buttons: [],
    limpers: 0,
    players: [],
    _playersCrossCheck: null,
    oppSeats: [],
    _oppProfiles: {},
    _seatRoles: {},
    _facing3bet: false,
    _raiserRole: 'unknown',
    _raiserStackType: 'unknown',
    _heroDid4bet: false,
    _slowplayed: false
  };
  
  var merged = Object.assign({}, defaults, opts);
  Object.keys(merged).forEach(function(k) {
    g[k] = merged[k];
  });
}

/**
 * 创建手牌对象
 * @param {string} rank1 - 如'A','K','Q','J','T','9','8','7','6','5','4','3','2'
 * @param {string} suit1 - 如'h','d','c','s'
 * @param {string} rank2
 * @param {string} suit2
 */
function card(rank, suit) {
  return { rank: rank, suit: suit };
}

/**
 * 从手牌key解析手牌 (如 'AKs', 'T9o', 'AA')
 */
function handFromKey(key) {
  var RANKS = 'AKQJT98765432';
  var r1 = key[0], r2 = key[1];
  var suited = key.indexOf('s') !== -1;
  var offsuit = key.indexOf('o') !== -1;
  var paired = r1 === r2;
  
  if (suited) {
    return [card(r1, 'h'), card(r2, 'h')];
  } else if (offsuit) {
    return [card(r1, 'h'), card(r2, 'd')];
  } else if (paired) {
    return [card(r1, 'h'), card(r2, 'd')];
  }
  // 默认offsuit
  return [card(r1, 'h'), card(r2, 'd')];
}

/**
 * 设置翻前场景
 * @param {string} handKey - 手牌key如'AKs','72o','AA'
 * @param {Object} opts - G对象覆盖
 */
function setupPreF(handKey, opts) {
  var hole = handFromKey(handKey);
  var gOpts = Object.assign({
    phase: 'pre',
    scene: 'open',
    hole: hole,
    comm: [null, null, null, null, null]
  }, opts || {});
  setupG(gOpts);
  return hole;
}

/**
 * 设置翻后场景
 * @param {string} handKey - 手牌key
 * @param {Array} community - 公共牌数组 [{rank,suit},...]
 * @param {Object} opts - G对象覆盖
 */
function setupPostF(handKey, community, opts) {
  var hole = handFromKey(handKey);
  var comm = [null, null, null, null, null];
  for (var i = 0; i < community.length && i < 5; i++) {
    comm[i] = community[i];
  }
  var gOpts = Object.assign({
    phase: community.length === 3 ? 'flop' : community.length === 4 ? 'turn' : 'river',
    hole: hole,
    comm: comm,
    scene: 'check'
  }, opts || {});
  setupG(gOpts);
  return hole;
}

/**
 * 重置对手画像
 */
function resetOppProfiler() {
  if (global.OppProfiler && global.OppProfiler.reset) {
    global.OppProfiler.reset();
  }
}

/**
 * 重置所有状态
 */
function resetAll() {
  _store = {};
  _elements = {};
  setupG({});
  resetOppProfiler();
  if (global.SelfTiltGuard && global.SelfTiltGuard.reset) global.SelfTiltGuard.reset();
  if (global.HandClassifier && global.HandClassifier.reset) global.HandClassifier.reset();
  if (global.TableQualityMeter && global.TableQualityMeter.reset) global.TableQualityMeter.reset();
}

// ============================================
// 4. 断言工具
// ============================================

var _results = { passed: 0, failed: 0, errors: [] };

function assert(condition, msg) {
  if (!condition) {
    _results.failed++;
    _results.errors.push(msg);
    console.log('  ❌ ' + msg);
    return false;
  }
  _results.passed++;
  console.log('  ✅ ' + msg);
  return true;
}

function assertAction(result, expected, msg) {
  var actual = result && result.a ? result.a : (result && result.action ? result.action : 'undefined');
  var ok = actual === expected;
  if (!ok) {
    _results.failed++;
    _results.errors.push(msg + ' — expected action=' + expected + ', got=' + actual);
    console.log('  ❌ ' + msg + ' — expected action=' + expected + ', got=' + actual + 
      (result ? ' | r=' + result.r + ' eq=' + result.eq + ' hClass=' + (result.hClass ? result.hClass.name : '?') : ''));
  } else {
    _results.passed++;
    console.log('  ✅ ' + msg + ' (action=' + actual + ')');
  }
  return ok;
}

function assertNotAction(result, unexpected, msg) {
  var actual = result && result.a ? result.a : 'undefined';
  var ok = actual !== unexpected;
  if (!ok) {
    _results.failed++;
    _results.errors.push(msg + ' — should NOT be action=' + unexpected);
    console.log('  ❌ ' + msg + ' — got unexpected action=' + actual);
  } else {
    _results.passed++;
    console.log('  ✅ ' + msg + ' (action=' + actual + ', not ' + unexpected + ')');
  }
  return ok;
}

function assertInActions(result, expectedActions, msg) {
  var actual = result && result.a ? result.a : 'undefined';
  var ok = expectedActions.indexOf(actual) !== -1;
  if (!ok) {
    _results.failed++;
    _results.errors.push(msg + ' — expected one of [' + expectedActions.join('/') + '], got=' + actual);
    console.log('  ❌ ' + msg + ' — expected [' + expectedActions.join('/') + '], got=' + actual +
      (result ? ' | r=' + result.r + ' eq=' + result.eq : ''));
  } else {
    _results.passed++;
    console.log('  ✅ ' + msg + ' (action=' + actual + ')');
  }
  return ok;
}

// ============================================
// 5. 导出
// ============================================

module.exports = {
  initEngine: initEngine,
  setupG: setupG,
  setupPreF: setupPreF,
  setupPostF: setupPostF,
  card: card,
  handFromKey: handFromKey,
  resetAll: resetAll,
  resetOppProfiler: resetOppProfiler,
  assert: assert,
  assertAction: assertAction,
  assertNotAction: assertNotAction,
  assertInActions: assertInActions,
  get results() { return _results; },
  get store() { return _store; },
  // 引擎加载后可访问的全局变量引用
  G: function() { return global.G || null; },
  getPreF: function() { return global.preF || null; },
  getPostF: function() { return global.postF || null; },
  getDecide: function() { return global.decide || null; },
  getHandClassify: function() { return global.handClassify || null; },
  getShouldThreebet: function() { return global.shouldThreebet || null; },
  getOppProfiler: function() { return global.OppProfiler || null; },
  getCacheManager: function() { return global.CacheManager || null; }
};

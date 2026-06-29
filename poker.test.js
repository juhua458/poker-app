/**
 * V2.9.43 青云策略引擎验证测试
 * 运行: node poker.test.js
 */
'use strict';

// === 模拟DOM/环境 ===
var document = {
  createElement: function() { return { style: {}, classList: { add: function(){}, remove: function(){}, toggle: function(){} } }; },
  getElementById: function(id) {
    var el = {
      textContent: '', innerHTML: '', className: '', classList: {
        add: function(){this._add=this._add||[];this._add.push(Array.prototype.slice.call(arguments));},
        remove: function(){this._rem=this._rem||[];this._rem.push(Array.prototype.slice.call(arguments));},
        toggle: function(){},
      },
      value: '',
      checked: false,
      style: {},
      addEventListener: function(){},
      click: function(){},
      appendChild: function(){},
      removeChild: function(){},
      setAttribute: function(){},
      getAttribute: function(){return '';},
    };
    // 模拟特定元素行为
    if (id === 'res') {
      el.innerHTML = '<div class="r-a">加注</div><div class="r-v">EV+12.3%</div>';
    }
    if (id === 'adv') {
      el.className = '';
    }
    return el;
  },
  querySelectorAll: function() { return []; },
  body: { classList: { add: function(){}, remove: function(){} }, className: '' },
};

// 模拟console
var console_messages = [];
global.console = {
  log: function() {
    var args = Array.prototype.slice.call(arguments);
    console_messages.push(args.join(' '));
  },
  warn: function() {},
  error: function() {}
};

// === 辅助函数 ===
function assert(condition, message) {
  if (!condition) {
    throw new Error('FAIL: ' + message);
  }
  console.log('  ✓ ' + message);
}

function assertEqual(actual, expected, message) {
  if (actual !== expected) {
    throw new Error('FAIL: ' + message + ' - expected ' + expected + ', got ' + actual);
  }
  console.log('  ✓ ' + message + ' (actual=' + actual + ')');
}

// === 测试计数器 ===
var passed = 0;
var failed = 0;
var tests = [];

function test(name, fn) {
  tests.push({name: name, fn: fn});
}

// === 测试 1: 5人桌CO位范围表扩充 ===
test('O5.co范围表扩充验证', function() {
  // 从poker_helper.html中解析O5.co
  var fs = require('fs');
  var content = fs.readFileSync('app/src/main/assets/poker_helper.html', 'utf8');
  
  var line527 = content.split('\n').find(function(l) { return l.indexOf("var O5=") >= 0 && l.indexOf("utg:") >= 0; });
  if (!line527) throw new Error('O5 not found');
  
  var co_start = line527.indexOf("'co:[") + 5;
  var co_end = line527.indexOf('],btn:');
  var co_section = line527.substring(co_start, co_end);
  
  var hands = co_section.match(/'([^']+)'/g) || [];
  hands = hands.map(function(h) { return h.replace(/'/g, ''); });
  
  assertEqual(hands.length, 77, 'O5.co应为77手（59→77，新增18手）');
  
  // 验证关键新增手牌存在
  var keyNew = ['22', 'K6s', 'K5s', 'Q7s', 'J7s', 'T7s', 'T6s', '96s', '85s', 'A7o', 'K8o', 'Q9o', 'JTo', 'J9o', 'T9o'];
  keyNew.forEach(function(h) {
    assert(hands.indexOf(h) >= 0, 'O5.co应包含新增手牌 ' + h);
  });
  
  // 验证原有核心手牌仍在
  var core = ['AA', 'KK', 'QQ', 'AKs', 'AQs', 'AJs', 'QJs', 'JTs', 'T9s', 'AKo', 'AQo', 'AJo'];
  core.forEach(function(h) {
    assert(hands.indexOf(h) >= 0, 'O5.co应保留核心手牌 ' + h);
  });
  
  console.log('    O5.co实际手牌: ' + hands.join(', '));
});

// === 测试 2: 全局G对象ante字段 ===
test('G对象包含ante字段', function() {
  // 提取G对象定义
  var fs = require('fs');
  var content = fs.readFileSync('app/src/main/assets/poker_helper.html', 'utf8');
  
  var gMatch = content.match(/var G=\{([^}]+)\}/);
  assert(gMatch !== null, 'G对象定义存在');
  assert(gMatch[1].indexOf('ante:0') >= 0, 'G对象包含ante字段(初始值0)');
});

// === 测试 3: detectSceneFromButtons Straddle检测 ===
test('detectSceneFromButtons Straddle检测', function() {
  var fs = require('fs');
  var content = fs.readFileSync('app/src/main/assets/poker_helper.html', 'utf8');
  
  // 检查straddle检测代码存在
  assert(content.indexOf("hasStraddle") >= 0, 'Straddle检测变量存在');
  assert(content.indexOf("straddle") >= 0 || content.indexOf("睡牌") >= 0, 'Straddle关键词检测存在');
  assert(content.indexOf("'raise'") >= 0, 'Straddle返回raise场景');
});

// === 测试 4: sTT自动同步 ===
test('onVisionResult中sTT自动同步', function() {
  var fs = require('fs');
  var content = fs.readFileSync('app/src/main/assets/poker_helper.html', 'utf8');
  
  assert(content.indexOf("typeof sTT==='function'") >= 0, 'sTT函数调用存在');
  assert(content.indexOf("自动同步人桌UI") >= 0, '自动同步注释存在');
});

// === 测试 5: recInfo使用G.tt ===
test('recInfo使用已校验的G.tt', function() {
  var fs = require('fs');
  var content = fs.readFileSync('app/src/main/assets/poker_helper.html', 'utf8');
  
  assert(content.indexOf("G.tt+'人桌'") >= 0, 'recInfo使用G.tt显示桌型');
  assert(content.indexOf("用已校验的G.tt") >= 0, '使用G.tt而非data.total_players');
});

// === 测试 6: GG Rake折扣 ===
test('GG Rake折扣逻辑存在', function() {
  var fs = require('fs');
  var content = fs.readFileSync('app/src/main/assets/poker_helper.html', 'utf8');
  
  assert(content.indexOf("rakeDiscount") >= 0 || content.indexOf("GG Rake") >= 0, 'Rake折扣代码存在');
  assert(content.indexOf("边缘跟注") >= 0 || content.indexOf("边缘场景") >= 0, '边缘场景检测存在');
  assert(content.indexOf("nq-5") >= 0, 'pot odds边界判断存在');
});

// === 测试 7: Ante修正 ===
test('Ante识别和G.ante保存', function() {
  var fs = require('fs');
  var content = fs.readFileSync('app/src/main/assets/poker_helper.html', 'utf8');
  
  assert(content.indexOf("G.ante=parseInt") >= 0, 'Ante保存到G.ante');
  assert(content.indexOf("Ante识别") >= 0, 'Ante识别日志存在');
});

// === 测试 8: FloatingService.kt版本号 ===
test('FloatingService.kt版本v2.9.43', function() {
  var fs = require('fs');
  var content = fs.readFileSync('app/src/main/java/com/pokerhelper/app/FloatingService.kt', 'utf8');
  
  assert(content.indexOf('"青云 v2.9.43"') >= 0, '版本文字更新为v2.9.43');
  assert(content.indexOf("tvRecDetail") >= 0, 'tvRecDetail变量声明存在');
  assert(content.indexOf("tvRecDetail?") >= 0, 'tvRecDetail使用存在(空安全调用)');
});

// === 测试 9: HttpServerService.kt版本号 ===
test('HttpServerService.kt版本v2.9.43', function() {
  var fs = require('fs');
  var content = fs.readFileSync('app/src/main/java/com/pokerhelper/app/HttpServerService.kt', 'utf8');
  
  var count = (content.match(/"2\.9\.43"/g) || []).length;
  assert(count >= 3, 'HttpServerService有3处以上v2.9.43');
});

// === 测试 10: build.gradle版本号 ===
test('build.gradle版本v2.9.43', function() {
  var fs = require('fs');
  var content = fs.readFileSync('app/build.gradle', 'utf8');
  
  assert(content.indexOf('versionCode 56') >= 0, 'versionCode更新为56');
  assert(content.indexOf('versionName "2.9.43"') >= 0, 'versionName更新为v2.9.43');
});

// === 测试 11: activity_main.xml版本号 ===
test('activity_main.xml版本v2.9.43', function() {
  var fs = require('fs');
  var content = fs.readFileSync('app/src/main/res/layout/activity_main.xml', 'utf8');
  
  assert(content.indexOf('V2.9.43') >= 0, 'activity_main版本更新为V2.9.43');
  assert(content.indexOf('GG策略升级') >= 0, '标题包含GG策略升级');
});

// === 测试 12: build.yml版本号 ===
test('build.yml版本v2.9.43', function() {
  var fs = require('fs');
  var content = fs.readFileSync('.github/workflows/build.yml', 'utf8');
  
  assert(content.indexOf('tag_name: v2.9.43') >= 0, 'tag_name为v2.9.43');
  assert(content.indexOf('name: "V2.9.43') >= 0, 'name包含V2.9.43');
});

// === 测试 13: poker_helper.html标题版本 ===
test('poker_helper.html标题v2.9.43', function() {
  var fs = require('fs');
  var content = fs.readFileSync('app/src/main/assets/poker_helper.html', 'utf8');
  
  assert(content.indexOf('青云 v2.9.43') >= 0, '标题包含v2.9.43');
});

// === 测试 14: tvRecDetail组件定义 ===
test('FloatingService.kt tvRecDetail组件', function() {
  var fs = require('fs');
  var content = fs.readFileSync('app/src/main/java/com/pokerhelper/app/FloatingService.kt', 'utf8');
  
  // 检查定义存在
  assert(content.indexOf('tvRecDetail = TextView(this).apply') >= 0, 'tvRecDetail初始化存在');
  assert(content.indexOf("tvRecDetail?.text = detailText") >= 0, 'tvRecDetail文本设置存在');
  assert(content.indexOf("tvRecDetail?.visibility = View.VISIBLE") >= 0, 'tvRecDetail显示存在');
  assert(content.indexOf("tvRecDetail?.text = \"\"") >= 0, 'tvRecDetail清空存在');
  assert(content.indexOf("tvRecDetail?.visibility = View.GONE") >= 0, 'tvRecDetail隐藏存在');
  assert(content.indexOf("container.addView(tvRecDetail!!") >= 0, 'tvRecDetail加入容器存在');
  assert(content.indexOf("textSize = 8f") >= 0, 'tvRecDetail字号8f');
  assert(content.indexOf("textSize = 11f") >= 0, 'tvRecResult字号11f(加大)');
});

// === 测试 15: 识别结果分两行显示 ===
test('悬浮窗识别结果分两行', function() {
  var fs = require('fs');
  var content = fs.readFileSync('app/src/main/java/com/pokerhelper/app/FloatingService.kt', 'utf8');
  
  assert(content.indexOf("BB=${result.blindBB}") >= 0 || content.indexOf("BB=${result.blindBB}") >= 0, 'BB盲注显示存在');
  assert(content.indexOf("底池${result.potSize}") >= 0, '底池显示存在');
  assert(content.indexOf("跟注${result.toCall}") >= 0, '跟注显示存在');
  assert(content.indexOf("分两行显示") >= 0, '分两行显示注释存在');
});

// === 测试 16: 豪哥分析——O5.co 5-max CO范围 ===
test('豪哥分析：O5.co应为"新BTN"级别(73-77手)', function() {
  var fs = require('fs');
  var content = fs.readFileSync('app/src/main/assets/poker_helper.html', 'utf8');
  
  var line527 = content.split('\n').find(function(l) { return l.indexOf("var O5=") >= 0 && l.indexOf("utg:") >= 0; });
  var co_start = line527.indexOf("'co:[") + 5;
  var co_end = line527.indexOf('],btn:');
  var co_section = line527.substring(co_start, co_end);
  var hands = co_section.match(/'([^']+)'/g) || [];
  hands = hands.map(function(h) { return h.replace(/'/g, ''); });
  
  // 验证O5.co比O5.utg更宽（CO应该比UTG宽）
  var utg_start = line527.indexOf("'utg:[") + 6;
  var utg_end = line527.indexOf("], co:");
  var utg_section = line527.substring(utg_start, utg_end);
  var utg_hands = utg_section.match(/'([^']+)'/g) || [];
  utg_hands = utg_hands.map(function(h) { return h.replace(/'/g, ''); });
  
  assert(hands.length > utg_hands.length, 'O5.co(' + hands.length + ')应比O5.utg(' + utg_hands.length + ')更宽');
  assert(hands.length >= 70, 'O5.co应>=70手(新BTN理论)');
  
  // 验证新增的关键加牌
  var addedPairs = hands.indexOf('22') >= 0;
  var addedK6s = hands.indexOf('K6s') >= 0;
  var addedQ7s = hands.indexOf('Q7s') >= 0;
  assert(addedPairs && addedK6s && addedQ7s, 'O5.co包含豪哥建议的关键新增牌');
});

// === 测试 17: 5人桌SB位范围包含22 ===
test('O5.sb包含小对子22', function() {
  var fs = require('fs');
  var content = fs.readFileSync('app/src/main/assets/poker_helper.html', 'utf8');
  
  var line527 = content.split('\n').find(function(l) { return l.indexOf("var O5=") >= 0 && l.indexOf("utg:") >= 0; });
  var sb_start = line527.indexOf("'sb:[") + 5;
  var sb_end = line527.indexOf('], bb:');
  var sb_section = line527.substring(sb_start, sb_end);
  var hands = sb_section.match(/'([^']+)'/g) || [];
  hands = hands.map(function(h) { return h.replace(/'/g, ''); });
  
  assert(hands.indexOf('22') >= 0, 'O5.sb包含小对子22');
});

// === 测试 18: 版本号一致性检查 ===
test('所有13处版本号一致性', function() {
  var fs = require('fs');
  var files = [
    'app/build.gradle',
    'app/src/main/java/com/pokerhelper/app/FloatingService.kt',
    'app/src/main/java/com/pokerhelper/app/HttpServerService.kt',
    'app/src/main/res/layout/activity_main.xml',
    '.github/workflows/build.yml',
    'app/src/main/assets/poker_helper.html',
  ];
  
  var counts = {};
  files.forEach(function(f) {
    var content = fs.readFileSync(f, 'utf8');
    var matches = content.match(/2\.9\.43/g) || [];
    counts[f.split('/').pop()] = matches.length;
  });
  
  var total = Object.values(counts).reduce(function(a, b) { return a + b; }, 0);
  console.log('  各文件v2.9.43出现次数:');
  Object.keys(counts).forEach(function(f) {
    console.log('    ' + f + ': ' + counts[f]);
  });
  assert(total >= 10, '总版本号出现次数>=10(包含多处通知文字)');
});

// === 测试 19: Straddle检测多关键词 ===
test('Straddle检测支持多种变体', function() {
  var fs = require('fs');
  var content = fs.readFileSync('app/src/main/assets/poker_helper.html', 'utf8');
  
  // 从detectSceneFromButtons函数中提取straddle检测代码
  var idx = content.indexOf("hasStraddle");
  assert(idx >= 0, 'hasStraddle变量存在');
  var snippet = content.substring(idx - 10, idx + 200);
  
  assert(snippet.indexOf('straddle') >= 0, '英文straddle检测');
  assert(snippet.indexOf('Straddle') >= 0, '首字母大写Straddle检测');
  assert(snippet.indexOf('睡牌') >= 0, '中文睡牌检测');
});

// === 测试 20: Ante自动保存到G对象 ===
test('onVisionResult中Ante自动保存', function() {
  var fs = require('fs');
  var content = fs.readFileSync('app/src/main/assets/poker_helper.html', 'utf8');
  
  // 检查G.ante赋值
  assert(content.indexOf("G.ante=parseInt") >= 0, 'G.ante赋值存在');
  // 检查初始值在G对象定义中
  var gMatch = content.match(/var G=\{([^}]+)\}/);
  assert(gMatch !== null, 'G对象可解析');
  assert(gMatch[1].indexOf('ante:0') >= 0, 'G.ante初始值为0');
});

// === 测试 21: Rake折扣仅边缘场景应用 ===
test('GG Rake折扣仅在边缘场景应用', function() {
  var fs = require('fs');
  var content = fs.readFileSync('app/src/main/assets/poker_helper.html', 'utf8');
  
  var idx = content.indexOf("rakeDiscount");
  assert(idx >= 0, 'rakeDiscount变量存在');
  var snippet = content.substring(idx - 100, idx + 200);
  
  assert(snippet.indexOf('bet>0') >= 0, '仅面对bet时应用');
  assert(snippet.indexOf('nq-5') >= 0 && snippet.indexOf('nq+5') >= 0, '边界判断在pot odds ±5%范围内');
  assert(snippet.indexOf('Math.max(5,eq-') >= 0, 'eq下限保护(不得低于5%)');
});

// === 测试 22: O5.btn包含O5.co不包含的手牌 ===
test('O5.btn比O5.co更宽(含O5.co没有的最小手牌)', function() {
  var fs = require('fs');
  var content = fs.readFileSync('app/src/main/assets/poker_helper.html', 'utf8');
  
  var line527 = content.split('\n').find(function(l) { return l.indexOf("var O5=") >= 0 && l.indexOf("utg:") >= 0; });
  
  var btn_start = line527.indexOf("'btn:[") + 6;
  var btn_end = line527.indexOf('], sb:');
  var btn_section = line527.substring(btn_start, btn_end);
  var btn_hands = (btn_section.match(/'([^']+)'/g) || []).map(function(h) { return h.replace(/'/g, ''); });
  
  var co_start = line527.indexOf("'co:[") + 5;
  var co_end = line527.indexOf('],btn:');
  var co_section = line527.substring(co_start, co_end);
  var co_hands = (co_section.match(/'([^']+)'/g) || []).map(function(h) { return h.replace(/'/g, ''); });
  
  // O5.btn应有更多手牌
  assert(btn_hands.length > co_hands.length, 'O5.btn(' + btn_hands.length + ') > O5.co(' + co_hands.length + ')');
});

// === 测试 23: HTML中没有v2.9.42残留 ===
test('HTML中无v2.9.42残留', function() {
  var fs = require('fs');
  var content = fs.readFileSync('app/src/main/assets/poker_helper.html', 'utf8');
  
  var old = content.match(/2\.9\.42/g) || [];
  assert(old.length === 0, 'HTML中v2.9.42出现' + old.length + '次(应为0)');
});

// === 测试 24: FloatingService无v2.9.42残留 ===
test('FloatingService.kt无v2.9.42残留', function() {
  var fs = require('fs');
  var content = fs.readFileSync('app/src/main/java/com/pokerhelper/app/FloatingService.kt', 'utf8');
  
  var old = content.match(/2\.9\.42/g) || [];
  assert(old.length === 0, 'FloatingService中v2.9.42出现' + old.length + '次(应为0)');
});

// === 测试 25: build.gradle无v2.9.42残留 ===
test('build.gradle无v2.9.42残留', function() {
  var fs = require('fs');
  var content = fs.readFileSync('app/build.gradle', 'utf8');
  
  var old = content.match(/2\.9\.42/g) || [];
  assert(old.length === 0, 'build.gradle中v2.9.42出现' + old.length + '次(应为0)');
});

// === 运行所有测试 ===
console.log('========================================');
console.log(' V2.9.43 青云策略引擎验证测试');
console.log('========================================');
console.log('');

tests.forEach(function(t, i) {
  try {
    t.fn();
    passed++;
  } catch (e) {
    console.log('  ✗ ' + t.name + ': ' + e.message);
    failed++;
  }
});

console.log('');
console.log('========================================');
console.log(' 结果: ' + passed + ' passed, ' + failed + ' failed, ' + tests.length + ' total');
console.log('========================================');

if (failed > 0) {
  process.exit(1);
}

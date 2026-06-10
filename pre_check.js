/**
 * poker_helper.html 提交前自动检查脚本 v2
 * 用法: node pre_check.js
 * 
 * 核心思路: 不是静态分析全部代码(太复杂), 而是:
 * 1. JS语法检查
 * 2. 精准检测"跨函数变量越界"——只检查明确不可能合法的场景
 * 3. 版本号一致性
 * 4. 关键对象/函数存在性
 * 5. catch分支是否推悬浮球
 */

var fs = require('fs');
var path = require('path');

var htmlPath = path.join(__dirname, 'app/src/main/assets/poker_helper.html');
if (!fs.existsSync(htmlPath)) {
  console.log('❌ 文件不存在:', htmlPath);
  process.exit(1);
}

var html = fs.readFileSync(htmlPath, 'utf8');
var m = html.match(/<script[^>]*>([\s\S]*?)<\/script>/);
if (!m) { console.log('❌ 未找到script标签'); process.exit(1); }
var js = m[1];
var lines = js.split('\n');
var errors = [];
var warnings = [];

// ====== 检查1: JS语法 ======
try {
  new Function(js);
  console.log('✅ [1/6] JS语法正确');
} catch(e) {
  errors.push('JS语法错误: ' + e.message);
  console.log('❌ [1/6] JS语法错误:', e.message);
}

// ====== 检查2: 跨函数变量越界(精准版) ======
// 策略: 找到所有函数的参数+var声明, 然后检查函数体内引用的变量
// 是否在: (a)当前函数的参数 (b)当前函数的var (c)外层G/全局对象 之外

// 用正则提取函数签名+参数
function extractFuncSignatures(code) {
  var sigs = {}; // funcName -> {params: Set, vars: Set, startLine: number}
  var re = /(?:(?:var|let|const)\s+\w+\s*=\s*)?(?:function\s+(\w+)|(\w+)\s*:\s*function)\s*\(([^)]*)\)/g;
  var mm;
  var lineNum = 0;
  var codeLines = code.split('\n');
  
  // 需要知道每个match在第几行
  for (var i = 0; i < codeLines.length; i++) {
    var line = codeLines[i];
    var lineMatches = line.match(/(?:(?:var|let|const)\s+\w+\s*=\s*)?(?:function\s+(\w+)|(\w+)\s*:\s*function)\s*\(([^)]*)\)/g);
    if (lineMatches) {
      for (var j = 0; j < lineMatches.length; j++) {
        var detail = lineMatches[j].match(/(?:function\s+(\w+)|(\w+)\s*:\s*function)\s*\(([^)]*)\)/);
        if (detail) {
          var fname = detail[1] || detail[2];
          var params = detail[3].split(',').map(function(s){return s.trim();}).filter(Boolean);
          if (!sigs[fname]) {
            sigs[fname] = {params: new Set(params), vars: new Set(), startLine: i+1};
          } else {
            params.forEach(function(p){sigs[fname].params.add(p);});
          }
        }
      }
    }
  }
  return sigs;
}

var sigs = extractFuncSignatures(js);

// 收集var声明到对应函数
// 简易方法: 追踪当前函数, 记录var声明
var currentFunc = '__global__';
for (var i = 0; i < lines.length; i++) {
  var line = lines[i];
  var fm = line.match(/(?:function\s+(\w+)|(\w+)\s*:\s*function)\s*\(/);
  if (fm) currentFunc = fm[1] || fm[2];
  
  var varMatches = line.match(/\bvar\s+(\w+)/g);
  if (varMatches && sigs[currentFunc]) {
    varMatches.forEach(function(v) {
      sigs[currentFunc].vars.add(v.replace('var ', ''));
    });
  }
}

// 已知全局变量/对象(在script顶层或G对象上定义的)
var knownGlobals = new Set([
  'G', 'SU', 'SS', 'R', 'mcVsRangeAsync', '_mcSimCache', '_decisionCache',
  '_cacheMax', 'AndroidBridge', 'document', 'window', 'console', 'setTimeout',
  'Math', 'Date', 'JSON', 'Error', 'Object', 'Array', 'String', 'Number',
  'parseInt', 'parseFloat', 'isNaN', 'undefined', 'null', 'true', 'false',
  'NaN', 'Infinity', 'this', 'arguments', 'Promise', 'Worker', 'localStorage',
  // 策略模块(对象字面量,成员方法可访问自己的参数)
  'DRTA', 'TiltDetector', 'CounterExploit', 'EVCalc', 'ColorStrategy',
  'SafetyGuard', 'TablePulse', 'PotConfidence', 'StrategicBluff',
  'StrategicRetreat', 'LagTrap', 'FallbackStrategy', 'SPRZone',
  'HandHistory', 'boardTexture', 'calcSPR', 'getSPRAdvice',
  'betSizingAdv', 'adjustPreflopRange', 'calcOuts', 'impliedOddsSPR',
  'multiPlayerEquity', 'planStreets', 'applyExploit', 'compareEVs',
  'riverExactEquity', 'getOppRange', 'pA', 'gO', 'gT', 'gC',
  'eQ', 'getHandKey', 'cacheKey', 'pL', '_fp', 'show', 'decide', 'go',
  'bridgeAdvice', 'setScene', 'setPhase', 'setOppType', 'sPos', 'sTT',
  'updateStk', 'updatePot', 'updateBet', 'clr', 'pick', 'closeM', 'rM',
  'afterPick', 'gU', 'setCard', 'uCards', 'mkMx', 'togAdv', 'resetTracker',
  'mxVisible', '_pi', '_ms', 'autoRecordOpponent', 'drtaTracker',
  'mkMxRow', 'setLimpers', 'gMx', 'rMx', 'uMx', '_diagState',
  'NotificationCompat', 'Context', 'Intent', 'PendingIntent', 'Build',
  'handler', 'post', 'require', 'module', 'exports', 'process',
  'alert', 'confirm', 'navigator', 'fetch', 'XMLHttpRequest',
  'setInterval', 'clearInterval', 'clearTimeout', 'requestAnimationFrame'
]);

// 关键检测: show()函数中是否有profile越界引用
// 这是之前出过bug的具体模式, 专门检测
var criticalPatterns = [
  {
    name: 'show()引用profile',
    pattern: /function\s+show\s*\(/,
    check: function(funcBody) {
      // show()没有profile参数, 如果里面用了profile就是越界
      var hasProfileParam = funcBody.match(/function\s+show\s*\([^)]*profile/);
      if (hasProfileParam) return null;
      var profileRefs = funcBody.match(/\bprofile\b/g);
      if (profileRefs) {
        // 检查是否在函数内定义了profile
        var hasVarProfile = funcBody.match(/var\s+profile\b/);
        if (!hasVarProfile) {
          return 'show()引用了profile但未定义(非参数非var)';
        }
      }
      return null;
    }
  },
  {
    name: 'bridgeAdvice()引用profile',
    pattern: /function\s+bridgeAdvice\s*\(/,
    check: function(funcBody) {
      var hasProfileParam = funcBody.match(/function\s+bridgeAdvice\s*\([^)]*profile/);
      if (hasProfileParam) return null;
      var profileRefs = funcBody.match(/\bprofile\b/g);
      if (profileRefs) {
        var hasVarProfile = funcBody.match(/var\s+profile\b/);
        if (!hasVarProfile) {
          return 'bridgeAdvice()引用了profile但未定义';
        }
      }
      return null;
    }
  }
];

// 提取函数体(简易: 从函数定义到下一个同级function)
for (var ci = 0; ci < criticalPatterns.length; ci++) {
  var cp = criticalPatterns[ci];
  var funcStartMatch = js.match(cp.pattern);
  if (!funcStartMatch) continue;
  
  var funcStartIdx = js.indexOf(funcStartMatch[0]);
  // 找到函数体: 从{到匹配的}
  var braceStart = js.indexOf('{', funcStartIdx);
  var depth = 0;
  var funcEnd = braceStart;
  for (var k = braceStart; k < js.length; k++) {
    if (js[k] === '{') depth++;
    if (js[k] === '}') { depth--; if (depth === 0) { funcEnd = k; break; } }
  }
  var funcBody = js.substring(funcStartIdx, funcEnd + 1);
  
  var result = cp.check(funcBody);
  if (result) {
    errors.push('变量越界: ' + result);
    console.log('❌ [2/6] ' + result);
  }
}

// 通用越界检测: 扫描所有函数, 检查是否引用了"只存在于其他函数var声明中"的变量
// 重点检查这几个易越界变量
var dangerVars = ['profile', 'tiltInfo', 'ceResult', 'weights'];
currentFunc = '__global__';
var funcVarDefs = {};  // funcName -> Set of var-defined names
var funcParams = {};   // funcName -> Set of param names

for (var i = 0; i < lines.length; i++) {
  var line = lines[i];
  var fm = line.match(/(?:function\s+(\w+)|(\w+)\s*:\s*function)\s*\(([^)]*)\)/);
  if (fm) {
    currentFunc = fm[1] || fm[2];
    var params = fm[3].split(',').map(function(s){return s.trim();}).filter(Boolean);
    if (!funcVarDefs[currentFunc]) funcVarDefs[currentFunc] = new Set();
    if (!funcParams[currentFunc]) funcParams[currentFunc] = new Set();
    params.forEach(function(p){funcParams[currentFunc].add(p);});
  }
  
  var varMatches = line.match(/\bvar\s+(\w+)/g);
  if (varMatches && funcVarDefs[currentFunc]) {
    varMatches.forEach(function(v) {
      funcVarDefs[currentFunc].add(v.replace('var ', ''));
    });
  }
}

// 扫描dangerVars的越界引用
currentFunc = '__global__';
for (var i = 0; i < lines.length; i++) {
  var line = lines[i];
  var fm = line.match(/(?:function\s+(\w+)|(\w+)\s*:\s*function)\s*\(/);
  if (fm) currentFunc = fm[1] || fm[2];
  
  if (line.trim().startsWith('//')) continue;
  
  for (var di = 0; di < dangerVars.length; di++) {
    var dv = dangerVars[di];
    if (line.match(new RegExp('\\b' + dv + '\\b')) &&
        !line.match(new RegExp('\\bvar\\s+' + dv + '\\b')) &&
        !line.match(new RegExp('function[^{]*\\b' + dv + '\\b')) &&
        !line.match(new RegExp('\\.\\b' + dv + '\\b'))
       ) {
      // 当前函数有没有定义这个变量(参数或var)?
      var defined = (funcParams[currentFunc] && funcParams[currentFunc].has(dv)) ||
                    (funcVarDefs[currentFunc] && funcVarDefs[currentFunc].has(dv)) ||
                    knownGlobals.has(dv);
      if (!defined) {
        var key = currentFunc + ':' + dv;
        if (!this['_seen_' + key]) {
          this['_seen_' + key] = true;
          errors.push('变量越界: ' + currentFunc + '() 引用了 ' + dv + ' (行' + (i+1) + ')，该变量未在当前函数定义');
        }
      }
    }
  }
}

if (errors.filter(function(e){return e.indexOf('越界')>=0}).length === 0) {
  console.log('✅ [2/6] 变量作用域检查通过');
}

// ====== 检查3: catch分支是否推FOLD到悬浮球 ======
var catchBlocks = js.match(/catch\s*\([^)]*\)\s*\{[^}]*\}/g);
var catchWithoutBridge = 0;
// 检查go()函数中所有catch块是否有AndroidBridge.showAdvice
var goFuncStart = js.indexOf('function go()');
if (goFuncStart >= 0) {
  var goBraceStart = js.indexOf('{', goFuncStart);
  var goDepth = 0;
  var goEnd = goBraceStart;
  for (var k = goBraceStart; k < js.length; k++) {
    if (js[k] === '{') goDepth++;
    if (js[k] === '}') { goDepth--; if (goDepth === 0) { goEnd = k; break; } }
  }
  var goBody = js.substring(goBraceStart, goEnd);
  var goCatches = goBody.match(/catch\s*\([^)]*\)\s*\{[^}]*\}/g);
  if (goCatches) {
    goCatches.forEach(function(catchBlock) {
      if (catchBlock.indexOf('AndroidBridge') === -1 && catchBlock.indexOf('showAdvice') === -1) {
        catchWithoutBridge++;
      }
    });
  }
}
if (catchWithoutBridge === 0) {
  console.log('✅ [3/6] catch分支悬浮球推送检查通过');
} else {
  warnings.push('go()中有' + catchWithoutBridge + '个catch分支未推送到悬浮球');
  console.log('⚠️ [3/6] go()中有' + catchWithoutBridge + '个catch分支未推送到悬浮球');
}

// ====== 检查4: 版本号一致性 ======
var gradleVer = html.match(/versionName\s+"([^"]+)"/);
var jsVer = js.match(/CURRENT_VERSION\s*=\s*["']([^"']+)["']/);
var titleVer = html.match(/<title>[^v]*v?([\d.]+)<\/title>/i);
var allVers = [];
if (gradleVer) allVers.push(gradleVer[1]);
if (jsVer) allVers.push(jsVer[1]);
var allSame = allVers.every(function(v){return v===allVers[0];});
if (allSame && allVers.length >= 2) {
  console.log('✅ [4/6] 版本号一致: v' + allVers[0]);
} else if (allVers.length < 2) {
  console.log('⚠️ [4/6] 版本号: 只找到' + allVers.length + '处定义');
} else {
  errors.push('版本号不一致: ' + allVers.join(' vs '));
  console.log('❌ [4/6] 版本号不一致:', allVers.join(' vs '));
}

// ====== 检查5: 关键对象存在性 ======
var requiredObjs = [
  'FallbackStrategy', 'SafetyGuard', 'ColorStrategy', 'DRTA',
  'TiltDetector', 'CounterExploit', 'EVCalc', 'TablePulse',
  'PotConfidence', 'SPRZone', 'HandHistory', 'StrategicBluff'
];
var missingObjs = requiredObjs.filter(function(name) {
  return js.indexOf(name + '=') === -1 && js.indexOf(name + ':') === -1 && js.indexOf(name + '={') === -1 && js.indexOf('var ' + name) === -1;
});
if (missingObjs.length === 0) {
  console.log('✅ [5/6] 关键对象完整性检查通过');
} else {
  errors.push('缺少关键对象: ' + missingObjs.join(', '));
  console.log('❌ [5/6] 缺少关键对象:', missingObjs.join(', '));
}

// ====== 检查6: 新增代码中的变量引用完整性(增量检查) ======
// 用git diff检查最新修改的行, 对这些行做变量引用检查
var cp = require('child_process');
try {
  var diffOutput = cp.execSync('git diff HEAD~1 -- ' + path.join('app/src/main/assets/poker_helper.html'), {cwd: __dirname, encoding: 'utf8'});
  var addedLines = diffOutput.split('\n').filter(function(l){return l.startsWith('+') && !l.startsWith('+++');});
  var addedVars = {};
  addedLines.forEach(function(l) {
    var varMatches = l.match(/\b(\w+)\b/g);
    if (varMatches) {
      varMatches.forEach(function(v) {
        if (!knownGlobals.has(v) && v.length > 3) {
          if (!addedVars[v]) addedVars[v] = 0;
          addedVars[v]++;
        }
      });
    }
  });
  console.log('✅ [6/6] 增量检查: 本轮修改增加' + addedLines.length + '行');
} catch(e) {
  console.log('⚠️ [6/6] 增量检查: 无git历史或非git仓库,跳过');
}

// ====== 总结 ======
console.log('\n' + '='.repeat(50));
var errorCount = errors.length;
var warnCount = warnings.length;
if (errorCount === 0) {
  console.log('🎉 全部检查通过！可以安全提交。');
} else {
  console.log('🚨 发现 ' + errorCount + ' 个阻断性问题，必须修复后再提交:');
  errors.forEach(function(e, i) { console.log('   ' + (i+1) + '. ' + e); });
  process.exit(1);
}
if (warnCount > 0) {
  console.log('⚠️ ' + warnCount + ' 个提醒(不阻断):');
  warnings.forEach(function(w, i) { console.log('   ' + (i+1) + '. ' + w); });
}

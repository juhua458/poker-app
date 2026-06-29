/**
 * StrategyEngine V2.9.157 专项测试
 * 覆盖: RFI频率表, 3Bet表, Facing3Bet表, CBet框架, River诈唬公式
 */
'use strict';

var fs = require('fs');
var path = require('path');

// 加载引擎
var sdk = require('./index.js');
sdk.initEngine();
var StrategyEngine = global.StrategyEngine;

var pass = 0, fail = 0;
var results = [];

function assert(cond, desc) {
  if (cond) { pass++; results.push('  ✅ ' + desc); }
  else { fail++; results.push('  ❌ ' + desc); }
}

// ============================================
// 1. RFI频率表测试
// ============================================
results.push('\n═══ 1. RFI频率表 ═══');

// AA必须开池
assert(StrategyEngine.getRFI('UTG1').AA === 1, 'UTG1 AA freq=1');
assert(StrategyEngine.getRFI('MP').AA === 1, 'MP AA freq=1');
assert(StrategyEngine.getRFI('CO').AA === 1, 'CO AA freq=1');
assert(StrategyEngine.getRFI('BTN').AA === 1, 'BTN AA freq=1');

// A5s混合频率(诈唬3bet候选)
assert(StrategyEngine.getRFI('UTG1').A5s === 0.6, 'UTG1 A5s freq=0.6 (混合)');
assert(StrategyEngine.getRFI('CO').A5s === 1, 'CO A5s freq=1 (高频)');

// 72o不在范围
assert(!StrategyEngine.getRFI('UTG1').hasOwnProperty('72o'), 'UTG1 72o不在RFI');
assert(!StrategyEngine.getRFI('MP').hasOwnProperty('72o'), 'MP 72o不在RFI');

// 33小对子混合频率
assert(StrategyEngine.getRFI('UTG1')['33'] === 0.3, 'UTG1 33 freq=0.3 (低频)');
assert(StrategyEngine.getRFI('CO')['33'] === 0.5, 'CO 33 freq=0.5 (混合)');

// K9s范围扩展
assert(StrategyEngine.getRFI('UTG1').K9s === 0.5, 'UTG1 K9s freq=0.5');
assert(StrategyEngine.getRFI('CO').K9s === 0.9, 'CO K9s freq=0.9');

// 位置范围宽度递增
var utg1Count = Object.values(StrategyEngine.getRFI('UTG1')).filter(function(v){return v>0;}).length;
var mpCount = Object.values(StrategyEngine.getRFI('MP')).filter(function(v){return v>0;}).length;
var coCount = Object.values(StrategyEngine.getRFI('CO')).filter(function(v){return v>0;}).length;
var btnCount = Object.values(StrategyEngine.getRFI('BTN')).filter(function(v){return v>0;}).length;
assert(utg1Count < mpCount, 'RFI范围: UTG1(' + utg1Count + ') < MP(' + mpCount + ')');
assert(mpCount < coCount, 'RFI范围: MP(' + mpCount + ') < CO(' + coCount + ')');
assert(coCount <= btnCount, 'RFI范围: CO(' + coCount + ') <= BTN(' + btnCount + ')');

// ============================================
// 2. 3Bet表测试
// ============================================
results.push('\n═══ 2. 3Bet表 ═══');

var btnVsCO = StrategyEngine.get3B('vs_CO', 'from_BTN');
assert(btnVsCO !== null, 'BTN vs CO 3bet表存在');
assert(btnVsCO.AA.a === '3b', 'BTN vs CO AA → 3bet');
assert(btnVsCO.AA.f === 1, 'BTN vs CO AA 3bet freq=1');
assert(btnVsCO['99'].a === 'c', 'BTN vs CO 99 → call');
assert(btnVsCO.A5s.a === '3b', 'BTN vs CO A5s → 3bet(诈唬)');
assert(btnVsCO.A5s.f === 0.5, 'BTN vs CO A5s 3bet freq=0.5');

var sbVsUTG1 = StrategyEngine.get3B('vs_UTG1', 'from_SB');
assert(sbVsUTG1 !== null, 'SB vs UTG1 3bet表存在');
assert(sbVsUTG1.AA.a === '3b', 'SB vs UTG1 AA → 3bet');
// SB vs UTG1: 紧3bet
assert(sbVsUTG1.A5s.f === 0.3, 'SB vs UTG1 A5s freq=0.3 (紧)');

var bbVsBTN = StrategyEngine.get3B('vs_BTN', 'from_BB');
assert(bbVsBTN !== null, 'BB vs BTN 3bet表存在');
assert(bbVsBTN['88'].a === 'c', 'BB vs BTN 88 → call');
assert(bbVsBTN.A5s.a === '3b', 'BB vs BTN A5s → 3bet');

// ============================================
// 3. Facing3Bet表测试
// ============================================
results.push('\n═══ 3. Facing3Bet表 ═══');

var f3bCO = StrategyEngine.getF3B('CO');
assert(f3bCO !== null, 'CO facing 3bet表存在');
assert(f3bCO.AA.a === '4b', 'CO vs 3bet AA → 4bet');
assert(f3bCO.AA.f === 1, 'CO vs 3bet AA 4bet freq=1');
assert(f3bCO.QQ.a === '4b', 'CO vs 3bet QQ → 4bet(混合)');
assert(f3bCO.QQ.f === 0.7, 'CO vs 3bet QQ 4bet freq=0.7');
assert(f3bCO.QQ.s.a === 'c', 'CO vs 3bet QQ secondary → call');
assert(f3bCO.JJ.a === 'c', 'CO vs 3bet JJ → call');
assert(f3bCO.A5s.a === '4b', 'CO vs 3bet A5s → 4bet诈唬');

var f3bBTN = StrategyEngine.getF3B('BTN');
assert(f3bBTN !== null, 'BTN facing 3bet表存在');
assert(f3bBTN.JJ.a === '4b', 'BTN vs 3bet JJ → 4bet(混合)');
assert(f3bBTN.JJ.f === 0.4, 'BTN vs 3bet JJ 4bet freq=0.4');

// ============================================
// 4. CBet框架测试
// ============================================
results.push('\n═══ 4. CBet框架 ═══');

var cbetIPDry = StrategyEngine.getCBetIP('0');  // 干燥高牌
assert(cbetIPDry !== null, 'IP CBet 干燥高牌表存在');
assert(cbetIPDry[0][0] === 1, 'IP CBet 干燥面 NUTS freq=1');
assert(cbetIPDry[0][1] === 0.9, 'IP CBet 干燥面 NUTS 小尺度=90%');
assert(cbetIPDry[15][0] === 0.3, 'IP CBet 干燥面 AIR freq=0.3');

var cbetIPWet = StrategyEngine.getCBetIP('2');  // 湿润连接
assert(cbetIPWet !== null, 'IP CBet 湿润面表存在');
assert(cbetIPWet[0][1] === 0.2, 'IP CBet 湿润面 NUTS 小尺度=20%(大尺度)');
assert(cbetIPWet[15][0] === 0.1, 'IP CBet 湿润面 AIR freq=0.1');

var cbetOOPDry = StrategyEngine.getCBetOOP('0');  // OOP干燥
assert(cbetOOPDry !== null, 'OOP CBet 干燥面表存在');
assert(cbetOOPDry[0][0] === 1, 'OOP CBet 干燥面 NUTS freq=1');
assert(cbetOOPDry[15][0] === 0.2, 'OOP CBet 干燥面 AIR freq=0.2');

// IP比OOP频率高
assert(cbetIPDry[4][0] > cbetOOPDry[4][0], 'IP CBet TP_GOOD freq > OOP');

// ============================================
// 5. 决策集成测试(模拟场景)
// ============================================
results.push('\n═══ 5. 决策集成测试 ═══');

// 5a. 翻前开池: AA开池
sdk.setupG({hole:[{rank:'A',suit:'s'},{rank:'A',suit:'h'}], comm:[], pos:'btn', scene:'check', pot:500, bet:0, stk:100000, tt:5, opp:'unknown', phase:'pre'});
var r1 = StrategyEngine.decidePreflop('AA');
assert(r1 !== null, 'SE翻前AA返回非null');
if(r1) {
  assert(r1.a === 'raise', 'SE翻前AA → raise (got: ' + r1.a + ')');
  assert(r1._se === true, 'SE标记存在');
  assert(r1._seFreq === 1, 'SE频率=1');
}

// 5b. 翻前开池: 72o弃牌
sdk.setupG({hole:[{rank:'7',suit:'d'},{rank:'2',suit:'c'}], comm:[], pos:'utg1', scene:'check', pot:500, bet:0, stk:100000, tt:5, opp:'unknown', phase:'pre'});
var r2 = StrategyEngine.decidePreflop('72o');
assert(r2 !== null, 'SE翻前72o返回非null');
if(r2) {
  assert(r2.a === 'fold', 'SE翻前72o → fold (got: ' + r2.a + ')');
}

// 5c. 翻前面对开池: KK 3bet
sdk.setupG({hole:[{rank:'K',suit:'s'},{rank:'K',suit:'h'}], comm:[], pos:'btn', scene:'raise', pot:1500, bet:1200, stk:100000, tt:5, opp:'unknown', _raiserRole:'co', phase:'pre'});
var r3 = StrategyEngine.decidePreflop('KK');
assert(r3 !== null, 'SE翻前KK vs CO raise返回非null');
if(r3) {
  assert(r3.a === 'raise', 'SE翻前KK vs CO → 3bet (got: ' + r3.a + ')');
}

// 5d. 翻前面对3bet: AA 4bet
sdk.setupG({hole:[{rank:'A',suit:'s'},{rank:'A',suit:'h'}], comm:[], pos:'co', scene:'reraise', pot:4500, bet:3600, stk:100000, tt:5, opp:'unknown', phase:'pre'});
var r4 = StrategyEngine.decidePreflop('AA');
assert(r4 !== null, 'SE翻前AA vs 3bet返回非null');
if(r4) {
  assert(r4.a === 'raise', 'SE翻前AA vs 3bet → 4bet (got: ' + r4.a + ')');
}

// 5e. isEnabled测试
var origTT = global.G.tt;
global.G.tt = 5;
assert(StrategyEngine.isEnabled() === true, '5-max SE启用');
global.G.tt = 6;
assert(StrategyEngine.isEnabled() === false, '6-max SE未启用');
global.G.tt = origTT;

// 5f. 版本
assert(StrategyEngine.getVersion() === '2.9.157', 'SE版本=2.9.155');

// ============================================
// 汇总
// ============================================
results.push('\n═══════════════════════════════════════════════════');
results.push(' StrategyEngine V2.9.157 测试汇总');
results.push('═══════════════════════════════════════════════════');
results.push('');
results.push('  通过: ' + pass + '/' + (pass+fail));
results.push('  失败: ' + fail + '/' + (pass+fail));
results.push('');
if(fail === 0) results.push('  🎉 全部通过！');
else results.push('  ⚠️ 有失败项');

console.log(results.join('\n'));
process.exit(fail > 0 ? 1 : 0);

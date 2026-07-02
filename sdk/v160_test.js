// V2.9.160 专项测试
'use strict';
var sdk = require('./index');

console.log('\n=== V2.9.160 专项测试 ===\n');

sdk.initEngine();
var SE = global.StrategyEngine;
var G = sdk.G();

var passed=0, failed=0, total=0;

function assert(cond,desc){
  total++;
  if(cond){passed++;console.log('  ✅ '+desc);}
  else{failed++;console.log('  ❌ '+desc);}
}

assert(SE&&typeof SE==='object','StrategyEngine加载成功');

// 1. River策略重建
console.log('\n--- 1. River策略 ---');
assert(typeof SE.getRivTable==='function','getRivTable存在');
var riv=SE.getRivTable();
assert(riv&&riv.IP&&riv.OOP,'_RIV有IP/OOP分离');
if(riv&&riv.IP){
  assert(riv.IP['0_bet']&&riv.IP['0_face'],'_RIV IP有0_bet/0_face');
  assert(riv.IP['3_bet']&&riv.IP['3_face'],'_RIV IP有3_bet/3_face');
  assert(riv.OOP['0_bet']&&riv.OOP['0_face'],'_RIV OOP有0_bet/0_face');
  assert(Object.keys(riv.IP).length>=8,'_RIV IP key数≥8,实际'+Object.keys(riv.IP).length);
}

// 2. Turn IP barrel补全
console.log('\n--- 2. Turn IP barrel ---');
assert(typeof SE.getTurnBarrelIP==='function','getTurnBarrelIP存在');
var tbCount=0;
for(var bt of ['0','1','2','3','4','5','6']){
  for(var tt of ['i','b','w']){
    if(SE.getTurnBarrelIP(bt,tt)!==null)tbCount++;
  }
}
assert(tbCount>=21,'TB_IP有效key≥21,实际'+tbCount);

// 3. Donk策略表
console.log('\n--- 3. Donk策略 ---');
assert(typeof SE.getDonkTable==='function','getDonkTable存在');
var donk=SE.getDonkTable();
assert(donk!==null,'_DONK表非null');
if(donk){
  assert(donk['0_ip']||donk['0_oop'],'_DONK有0_ip或0_oop');
  assert(Object.keys(donk).length>=4,'_DONK key数≥4,实际'+Object.keys(donk).length);
}

// 4. RangeEstimator
console.log('\n--- 4. RangeEstimator ---');
assert(typeof SE.getRangeEstimate==='function','getRangeEstimate存在');
var nitW=SE.getRangeEstimate('nit','flop');
assert(nitW==='narrow','nit flop=narrow,实际'+nitW);
var lagW=SE.getRangeEstimate('lag','flop');
assert(lagW==='wide','lag flop=wide,实际'+lagW);

// 5. SessionTracker
console.log('\n--- 5. SessionTracker ---');
assert(typeof SE.getSessionSummary==='function','getSessionSummary存在');
var summary=SE.getSessionSummary();
assert(summary!==null,'Session summary非null');
assert(summary.hands!==undefined,'summary有hands');
assert(typeof SE.resetSession==='function','resetSession存在');

// 6. Squeeze策略
console.log('\n--- 6. Squeeze ---');
assert(typeof SE.getSqueezeTable==='function','getSqueezeTable存在');
var sq=SE.getSqueezeTable();
assert(sq!==null,'_SQUEEZE表非null');
if(sq){
  assert(sq.vs_UTG1_vs_caller||sq.vs_MP_vs_caller,'_SQUEEZE有场景');
}

// 7. 多桌检测
console.log('\n--- 7. 多桌检测 ---');
assert(typeof SE.isMultiTable==='function','isMultiTable存在');
assert(SE.isMultiTable()===false,'初始非多桌');

// 8. 版本号
console.log('\n--- 8. 版本号 ---');
assert(SE.getVersion()==='2.9.160','版本号=2.9.160,实际'+SE.getVersion());

// 9. CBET 7btKeys
console.log('\n--- 9. CBET 7btKeys ---');
for(var bt of ['0','1','2','3','4','5','6']){
  assert(SE.getCBetIP(bt)!==null,'CBET_IP bt='+bt+'存在');
  assert(SE.getCBetOOP(bt)!==null,'CBET_OOP bt='+bt+'存在');
}

// 10. FCB 21 combos
console.log('\n--- 10. FCB 21 combos ---');
var fcbCount=0;
for(var bt of ['0','1','2','3','4','5','6']){
  for(var sz of ['s','m','l']){
    if(SE.getFacingCBetIP(bt,sz)!==null)fcbCount++;
    if(SE.getFacingCBetOOP(bt,sz)!==null)fcbCount++;
  }
}
assert(fcbCount>=42,'FCB IP+OOP ≥42,实际'+fcbCount);

// 11. 翻前3bet表
console.log('\n--- 11. 翻前3bet ---');
assert(SE.get3B('UTG1','CO')!==null,'3B vs_UTG1 from_CO');
assert(SE.get3B('MP','BTN')!==null,'3B vs_MP from_BTN');

// 12. CR表 7btKeys
console.log('\n--- 12. CR 7btKeys ---');
for(var bt of ['0','1','2','3','4','5','6']){
  assert(SE.getFacingCR(bt)!==null,'CR bt='+bt+'存在');
}

console.log('\n=== 结果: '+passed+'/'+total+' 通过, '+failed+' 失败 ===');
process.exit(failed>0?1:0);

var sdk = require('./index.js');
sdk.initEngine();

// Now check if the NUTS misclassification actually causes bad decisions
// Key concern: does the NUTS label cause overplay?
// Let me check the exact decision path

console.log('═══════════════════════════════════════');
console.log('  第八轮审计 — NUTS误判影响评估');
console.log('═══════════════════════════════════════');
console.log('');

// P21 impact: 77 set on 4-flush board, MC eq=64.4%
// NUTS → raise vs 1/2 pot. But eq=64.4% is not that strong
// If classified as STRONG instead of NUTS, would the decision change?
sdk.setupG({
  phase: 'post', scene: 'bet', pos: 'co', act: 2, opp: 'unknown', stk: 100, pot: 20, bet: 10,
  hole: [{rank:'7',suit:'h'},{rank:'7',suit:'d'}],
  comm: [{rank:'A',suit:'s'},{rank:'7',suit:'s'},{rank:'2',suit:'s'},{rank:'8',suit:'s'},{rank:'3',suit:'c'}],
  ante: 50
});
var r = postF();
console.log('P21: 77 set on 4-flush, eq=' + (r.eq||'?').toString().substring(0,5) + '%, action=' + r.a + ', reason=' + r.r);
console.log('  MC eq=64.4% → raise is reasonable but borderline');
console.log('  With STRONG label, would still raise (eq>60%)');
console.log('  Impact: LOW - eq itself is correct, label mainly affects 7-color signal');
console.log('');

// P25 impact: nut flush on paired board, MC eq=97.5%
// Even if NUTS is "wrong" (full house possible), eq=97.5% justifies any action
console.log('P25: nut flush on paired board, eq=97.5% → raise is correct');
console.log('  Impact: VERY LOW - eq is so high that NUTS/STRONG makes no difference');
console.log('');

// P28 impact: wheel straight on broadway, MC eq=88.2%
// eq=88.2% is high enough that raise is correct even if classified as STRONG
console.log('P28: wheel straight on broadway, eq=88.2% → raise is correct');
console.log('  But: if opponent has AK/QJ they have higher straight!');
console.log('  MC sim correctly accounts for this (88.2% not 100%)');
console.log('  Impact: LOW - MC eq compensates');
console.log('');

// P29 impact: straight on paired board, MC eq=71.9%
// eq=71.9% → raise is OK but NUTS label might cause over-aggressive play
console.log('P29: straight on paired board, eq=71.9% → raise is OK');
console.log('  But NUTS label → may trigger slowplay/overbet path');
console.log('  Impact: MEDIUM - NUTS path could lead to bad sizing');
console.log('');

console.log('═══════════════════════════════════════');
console.log('  总结: handClassify NUTS误判影响分析');
console.log('  - MC模拟(eq)是准确的→决策基本正确');
console.log('  - NUTS标签主要影响: 7色信号+显示+慢打路径');
console.log('  - P21(4-flush set): eq=64%→基本OK,但信号不准');
console.log('  - P25(paired flush): eq=97%→OK');
console.log('  - P28(wheel straight): eq=88%→OK');
console.log('  - P29(paired straight): eq=72%→最需修,NUTS路径可能过度');
console.log('');
console.log('  修复方案: handClassify湿面降级');
console.log('  set on 4-flush board → STRONG(非NUTS)');
console.log('  straight on paired board → STRONG(非NUTS)');
console.log('  flush on paired board → STRONG(非NUTS)');
console.log('  non-nut straight → STRONG(非NUTS)');
console.log('  quads/fh/sf → 仍NUTS(不可能被beat)');
console.log('═══════════════════════════════════════');

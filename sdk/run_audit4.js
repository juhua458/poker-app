var sdk = require('./index.js');
sdk.initEngine();

// Additional checks beyond handClassify - check actual decision outcomes

console.log('═══════════════════════════════════════');
console.log('  第八轮审计 — 决策层面验证');
console.log('═══════════════════════════════════════');
console.log('');

// P26: set on 4-flush board - does the engine overplay?
// Setup: 77 on A♠7♠2♠8♠3♣ board, facing half-pot bet
sdk.setupG({
  phase: 'post', scene: 'bet', pos: 'co', act: 2, opp: 'unknown', stk: 100, pot: 20, bet: 10,
  hole: [{rank:'7',suit:'h'},{rank:'7',suit:'d'}],
  comm: [{rank:'A',suit:'s'},{rank:'7',suit:'s'},{rank:'2',suit:'s'},{rank:'8',suit:'s'},{rank:'3',suit:'c'}],
  ante: 50
});
var r26 = postF();
console.log('P26: 77 set on 4-flush board vs 1/2 pot bet → ' + r26.a + ' (hClass=' + (r26.hClass ? r26.hClass.name : '?') + ')');
console.log('  如果是raise→可能过度激进(set不是nuts, 对手可能有flush)');
console.log('');

// P27: flush on paired board - does the engine overplay?
sdk.setupG({
  phase: 'post', scene: 'bet', pos: 'co', act: 2, opp: 'unknown', stk: 100, pot: 20, bet: 10,
  hole: [{rank:'A',suit:'s'},{rank:'K',suit:'s'}],
  comm: [{rank:'Q',suit:'s'},{rank:'8',suit:'s'},{rank:'5',suit:'s'},{rank:'8',suit:'h'},{rank:'2',suit:'c'}],
  ante: 50
});
var r27 = postF();
console.log('P27: nut flush on paired board vs 1/2 pot bet → ' + r27.a + ' (hClass=' + (r27.hClass ? r27.hClass.name : '?') + ')');
console.log('');

// P28: wheel straight on board with higher straight possible
sdk.setupG({
  phase: 'post', scene: 'bet', pos: 'co', act: 2, opp: 'unknown', stk: 100, pot: 20, bet: 10,
  hole: [{rank:'5',suit:'h'},{rank:'4',suit:'d'}],
  comm: [{rank:'A',suit:'s'},{rank:'2',suit:'c'},{rank:'3',suit:'d'},{rank:'K',suit:'c'},{rank:'T',suit:'c'}],
  ante: 50
});
var r28 = postF();
console.log('P28: wheel straight on broadway board → ' + r28.a + ' (hClass=' + (r28.hClass ? r28.hClass.name : '?') + ')');
console.log('');

// P29: straight on paired board
sdk.setupG({
  phase: 'post', scene: 'bet', pos: 'co', act: 2, opp: 'unknown', stk: 100, pot: 20, bet: 10,
  hole: [{rank:'9',suit:'h'},{rank:'T',suit:'d'}],
  comm: [{rank:'J',suit:'c'},{rank:'Q',suit:'s'},{rank:'K',suit:'c'},{rank:'K',suit:'h'},{rank:'2',suit:'c'}],
  ante: 50
});
var r29 = postF();
console.log('P29: straight on paired board → ' + r29.a + ' (hClass=' + (r29.hClass ? r29.hClass.name : '?') + ')');
console.log('');

console.log('═══════════════════════════════════════');
console.log('  分析: MC模拟会给出正确的eq,但handClassify');
console.log('  给出错误的NUTS分类→影响show()显示和7色信号');
console.log('  实际决策中MC eq可能是准确的,但NUTS标签让');
console.log('  引擎走NUTS专用路径(如慢打/超池)→过度激进');
console.log('═══════════════════════════════════════');

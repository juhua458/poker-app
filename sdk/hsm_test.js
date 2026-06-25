/**
 * V2.9.154 HandStateMachine 自动化验证
 * 模拟完整牌局流程，验证HSM状态推断准确性
 * 运行: node sdk/hsm_test.js
 */

'use strict';

var path = require('path');
var sdk = require('./index.js');

// ============================================
// 初始化引擎
// ============================================
sdk.initEngine();
var G = global.G;
var HSM = global.HandStateMachine;

if (!HSM) {
  console.error('❌ HandStateMachine 未加载！检查 poker_helper.html');
  process.exit(1);
}

console.log('\n══════════════════════════════════════════════════');
console.log('  V2.9.154 HandStateMachine 自动化验证');
console.log('══════════════════════════════════════════════════\n');

var totalTests = 0;
var passedTests = 0;
var failedTests = 0;

function assert(condition, msg) {
  totalTests++;
  if (condition) {
    console.log('  ✅ ' + msg);
    passedTests++;
  } else {
    console.log('  ❌ ' + msg);
    failedTests++;
  }
}

function resetHSM() {
  HSM.reset();
  // Reset G to defaults
  G.phase = 'pre';
  G.scene = 'open';
  G.tt = 5;
  G.pos = 'btn';
  G.act = 5;
  G.opp = 'unknown';
  G.stk = 100;
  G.pot = 10;
  G.bet = 0;
  G.hole = [null, null];
  G.comm = [null, null, null, null, null];
  G.buttons = [];
  G._facing3bet = false;
  G._heroDid4bet = false;
  G._rankLockKey = '';
  if (global.ActionLine) global.ActionLine.startHand('');
}

// Helper: 模拟Vision数据并触发HSM
function feedVision(holeCards, commCards, buttons, street, scene, extraOpts) {
  var data = {
    hole_cards: holeCards,
    community_cards: commCards || [],
    buttons: buttons || [],
    street: street || 'preflop'
  };
  
  // Update G scene
  if (scene) G.scene = scene;
  
  // Update G hole/comm (simulate onVisionResult's data fill)
  G.hole = [];
  for (var i = 0; i < holeCards.length && i < 2; i++) {
    G.hole.push({rank: holeCards[i].rank, suit: holeCards[i].suit || ''});
  }
  while (G.hole.length < 2) G.hole.push(null);
  
  G.comm = [null, null, null, null, null];
  if (commCards) {
    for (var j = 0; j < commCards.length && j < 5; j++) {
      G.comm[j] = commCards[j] ? {rank: commCards[j].rank, suit: commCards[j].suit || ''} : null;
    }
  }
  
  // Update G.phase from street
  var st = (street || '').toLowerCase();
  if (st === 'preflop' || st === 'pre') {
    G.phase = 'pre';
  } else {
    G.phase = 'post';
  }
  
  // Apply extra opts
  if (extraOpts) {
    for (var k in extraOpts) {
      if (extraOpts.hasOwnProperty(k)) G[k] = extraOpts[k];
    }
  }
  
  // Trigger HSM
  HSM.processVisionData(data);
  
  return HSM.getSummary();
}

// ============================================
// Test Suite 1: 基础Phase检测
// ============================================
console.log('══════════════════════════════════════════════════');
console.log('  1. Phase状态检测');
console.log('══════════════════════════════════════════════════');

// 1.1 新手牌 → PREFLOP
resetHSM();
var s = feedVision(
  [{rank:'A',suit:'h'},{rank:'K',suit:'s'}],
  [],
  ['加注','弃牌'],
  'preflop',
  'open'
);
assert(s.phase === 'preflop', '新手牌 → phase=preflop (got: ' + s.phase + ')');

// 1.2 PREFLOP → FLOP (3张公共牌)
s = feedVision(
  [{rank:'A',suit:'h'},{rank:'K',suit:'s'}],
  [{rank:'Q',suit:'d'},{rank:'7',suit:'c'},{rank:'2',suit:'s'}],
  ['下注','过牌','弃牌'],
  'flop',
  'check'
);
assert(s.phase === 'flop', '3张公共牌 → phase=flop (got: ' + s.phase + ')');

// 1.3 FLOP → TURN (4张公共牌)
s = feedVision(
  [{rank:'A',suit:'h'},{rank:'K',suit:'s'}],
  [{rank:'Q',suit:'d'},{rank:'7',suit:'c'},{rank:'2',suit:'s'},{rank:'T',suit:'h'}],
  ['过牌','弃牌'],
  'turn',
  'check'
);
assert(s.phase === 'turn', '4张公共牌 → phase=turn (got: ' + s.phase + ')');

// 1.4 TURN → RIVER (5张公共牌)
s = feedVision(
  [{rank:'A',suit:'h'},{rank:'K',suit:'s'}],
  [{rank:'Q',suit:'d'},{rank:'7',suit:'c'},{rank:'2',suit:'s'},{rank:'T',suit:'h'},{rank:'5',suit:'d'}],
  ['跟注','弃牌'],
  'river',
  'bet'
);
assert(s.phase === 'river', '5张公共牌 → phase=river (got: ' + s.phase + ')');

// 1.5 新手牌 → reset
s = feedVision(
  [{rank:'J',suit:'h'},{rank:'T',suit:'s'}],
  [],
  ['跟注','加注','弃牌'],
  'preflop',
  'raise'
);
assert(s.phase === 'preflop', '新手牌 → phase=preflop (got: ' + s.phase + ')');
assert(s.handCount === 1, 'handCount=1 (new hand #1, got: ' + s.handCount + ')');

// ============================================
// Test Suite 2: HeroRole推断
// ============================================
console.log('\n══════════════════════════════════════════════════');
console.log('  2. HeroRole推断');
console.log('══════════════════════════════════════════════════');

// 2.1 开池场景 = 场景open，可能PFR
resetHSM();
s = feedVision(
  [{rank:'A',suit:'h'},{rank:'K',suit:'s'}],
  [],
  ['加注','弃牌'],
  'preflop',
  'open'
);
// 开池时heroRole可能还是unknown（需要decide后recordPreflopAction）
// 模拟decide返回raise → recordPreflopAction
HSM.recordPreflopAction('raise');
s = HSM.getSummary();
assert(s.heroRole === 'pfr', '开池+raise → heroRole=pfr (got: ' + s.heroRole + ')');

// 2.2 面对开池（跟注场景）
resetHSM();
s = feedVision(
  [{rank:'J',suit:'h'},{rank:'T',suit:'s'}],
  [],
  ['跟注','加注','弃牌'],
  'preflop',
  'raise'
);
// 有跟注按钮+加注按钮 = 面对开池
assert(s.heroRole === 'defender', '面对开池+跟注/加注按钮 → heroRole=defender (got: ' + s.heroRole + ')');

// 2.3 recordPreflopAction → call = Defender
resetHSM();
s = feedVision(
  [{rank:'Q',suit:'d'},{rank:'J',suit:'s'}],
  [],
  ['跟注','弃牌'],
  'preflop',
  'raise'
);
HSM.recordPreflopAction('call');
s = HSM.getSummary();
assert(s.heroRole === 'defender', 'call → heroRole=defender (got: ' + s.heroRole + ')');

// 2.4 recordPreflopAction → 3bet
resetHSM();
s = feedVision(
  [{rank:'A',suit:'h'},{rank:'A',suit:'s'}],
  [],
  ['跟注','再加注','弃牌'],
  'preflop',
  'raise'
);
HSM.recordPreflopAction('3bet');
s = HSM.getSummary();
assert(s.heroRole === 'three_bet', '3bet → heroRole=three_bet (got: ' + s.heroRole + ')');

// 2.5 recordPreflopAction → 4bet
resetHSM();
s = feedVision(
  [{rank:'K',suit:'h'},{rank:'K',suit:'s'}],
  [],
  ['全下','弃牌'],
  'preflop',
  'raise'
);
HSM.recordPreflopAction('4bet');
s = HSM.getSummary();
assert(s.heroRole === 'four_bet', '4bet → heroRole=four_bet (got: ' + s.heroRole + ')');

// ============================================
// Test Suite 3: 街变化检测
// ============================================
console.log('\n══════════════════════════════════════════════════');
console.log('  3. 街变化检测');
console.log('══════════════════════════════════════════════════');

// 3.1 PREFLOP→FLOP 街变化
resetHSM();
s = feedVision(
  [{rank:'A',suit:'h'},{rank:'K',suit:'s'}],
  [],
  ['加注','弃牌'],
  'preflop',
  'open'
);
HSM.recordPreflopAction('raise');
s = feedVision(
  [{rank:'A',suit:'h'},{rank:'K',suit:'s'}],
  [{rank:'Q',suit:'d'},{rank:'7',suit:'c'},{rank:'2',suit:'s'}],
  ['下注','过牌','弃牌'],
  'flop',
  'check'
);
var hist = HSM.getStreetHistory();
assert(hist.length === 1, '街变化记录1条 (got: ' + hist.length + ')');
assert(hist[0].from === 'preflop', '从preflop变化 (got: ' + hist[0].from + ')');
assert(hist[0].to === 'flop', '到flop (got: ' + hist[0].to + ')');

// 3.2 FLOP→TURN
s = feedVision(
  [{rank:'A',suit:'h'},{rank:'K',suit:'s'}],
  [{rank:'Q',suit:'d'},{rank:'7',suit:'c'},{rank:'2',suit:'s'},{rank:'T',suit:'h'}],
  ['过牌','弃牌'],
  'turn',
  'check'
);
hist = HSM.getStreetHistory();
assert(hist.length === 2, '街变化记录2条 (got: ' + hist.length + ')');
assert(hist[1].to === 'turn', '到turn (got: ' + hist[1].to + ')');

// 3.3 TURN→RIVER
s = feedVision(
  [{rank:'A',suit:'h'},{rank:'K',suit:'s'}],
  [{rank:'Q',suit:'d'},{rank:'7',suit:'c'},{rank:'2',suit:'s'},{rank:'T',suit:'h'},{rank:'5',suit:'d'}],
  ['过牌','弃牌'],
  'river',
  'check'
);
hist = HSM.getStreetHistory();
assert(hist.length === 3, '街变化记录3条 (got: ' + hist.length + ')');
assert(hist[2].to === 'river', '到river (got: ' + hist[2].to + ')');

// ============================================
// Test Suite 4: 策略路径推导
// ============================================
console.log('\n══════════════════════════════════════════════════');
console.log('  4. 策略路径推导');
console.log('══════════════════════════════════════════════════');

// 4.1 PFR翻前开池
resetHSM();
s = feedVision(
  [{rank:'A',suit:'h'},{rank:'K',suit:'s'}],
  [],
  ['加注','弃牌'],
  'preflop',
  'open'
);
HSM.recordPreflopAction('raise');
s = HSM.getSummary();
assert(s.strategyPath === 'preflop.pfr_open', 'PFR翻前路径=preflop.pfr_open (got: ' + s.strategyPath + ')');

// 4.2 PFR翻牌CBet
s = feedVision(
  [{rank:'A',suit:'h'},{rank:'K',suit:'s'}],
  [{rank:'Q',suit:'d'},{rank:'7',suit:'c'},{rank:'2',suit:'s'}],
  ['下注','过牌','弃牌'],
  'flop',
  'check'
);
assert(s.strategyPath === 'flop.pfr_cbet_plan', 'PFR翻牌CBet路径=flop.pfr_cbet_plan (got: ' + s.strategyPath + ')');

// 4.3 PFR翻牌面对Donk
resetHSM();
s = feedVision(
  [{rank:'A',suit:'h'},{rank:'K',suit:'s'}],
  [],
  ['加注','弃牌'],
  'preflop',
  'open'
);
HSM.recordPreflopAction('raise');
s = feedVision(
  [{rank:'A',suit:'h'},{rank:'K',suit:'s'}],
  [{rank:'Q',suit:'d'},{rank:'7',suit:'c'},{rank:'2',suit:'s'}],
  ['跟注','加注','弃牌'],
  'flop',
  'bet'
);
assert(s.strategyPath === 'flop.pfr_vs_donk', 'PFR面对Donk=flop.pfr_vs_donk (got: ' + s.strategyPath + ')');

// 4.4 PFR翻牌面对Check-Raise (scene=raise, same street)
resetHSM();
s = feedVision(
  [{rank:'A',suit:'h'},{rank:'K',suit:'s'}],
  [],
  ['加注','弃牌'],
  'preflop',
  'open'
);
HSM.recordPreflopAction('raise');
// Flop: 直接面对加注场景(scene=raise)
s = feedVision(
  [{rank:'A',suit:'h'},{rank:'K',suit:'s'}],
  [{rank:'Q',suit:'d'},{rank:'7',suit:'c'},{rank:'2',suit:'s'}],
  ['跟注','加注','弃牌'],
  'flop',
  'raise'
);
assert(s.strategyPath === 'flop.pfr_vs_cr', 'PFR面对CR=flop.pfr_vs_cr (got: ' + s.strategyPath + ')');

// 4.5 Defender翻后面对CBet
resetHSM();
s = feedVision(
  [{rank:'J',suit:'h'},{rank:'T',suit:'s'}],
  [],
  ['跟注','弃牌'],
  'preflop',
  'raise'
);
HSM.recordPreflopAction('call');
s = feedVision(
  [{rank:'J',suit:'h'},{rank:'T',suit:'s'}],
  [{rank:'Q',suit:'d'},{rank:'7',suit:'c'},{rank:'2',suit:'s'}],
  ['跟注','加注','弃牌'],
  'flop',
  'bet'
);
assert(s.strategyPath === 'flop.defend_vs_cbet', 'Defender面对CBet=flop.defend_vs_cbet (got: ' + s.strategyPath + ')');

// 4.6 Defender翻后主动Probe
resetHSM();
s = feedVision(
  [{rank:'J',suit:'h'},{rank:'T',suit:'s'}],
  [],
  ['跟注','弃牌'],
  'preflop',
  'raise'
);
HSM.recordPreflopAction('call');
s = feedVision(
  [{rank:'J',suit:'h'},{rank:'T',suit:'s'}],
  [{rank:'Q',suit:'d'},{rank:'7',suit:'c'},{rank:'2',suit:'s'}],
  ['下注','过牌','弃牌'],
  'flop',
  'check'
);
assert(s.strategyPath === 'flop.defend_probe', 'Defender Probe=flop.defend_probe (got: ' + s.strategyPath + ')');

// 4.7 3Bettor翻牌CBet
resetHSM();
s = feedVision(
  [{rank:'A',suit:'h'},{rank:'A',suit:'s'}],
  [],
  ['跟注','再加注','弃牌'],
  'preflop',
  'raise'
);
HSM.recordPreflopAction('3bet');
s = feedVision(
  [{rank:'A',suit:'h'},{rank:'A',suit:'s'}],
  [{rank:'Q',suit:'d'},{rank:'7',suit:'c'},{rank:'2',suit:'s'}],
  ['下注','过牌','弃牌'],
  'flop',
  'check'
);
assert(s.strategyPath === 'flop.3better_cbet', '3Bettor CBet=flop.3better_cbet (got: ' + s.strategyPath + ')');

// ============================================
// Test Suite 5: 完整牌局流程
// ============================================
console.log('\n══════════════════════════════════════════════════');
console.log('  5. 完整牌局流程模拟');
console.log('══════════════════════════════════════════════════');

// 5.1 完整PFR流程：开池→CBet→Barrel→河牌
resetHSM();
console.log('  --- 5.1 PFR完整流程 ---');

s = feedVision([{rank:'A',suit:'h'},{rank:'K',suit:'s'}], [], ['加注','弃牌'], 'preflop', 'open');
HSM.recordPreflopAction('raise');
s = HSM.getSummary();
assert(s.phase === 'preflop' && s.heroRole === 'pfr', '翻前: phase=preflop role=pfr');

s = feedVision([{rank:'A',suit:'h'},{rank:'K',suit:'s'}], [{rank:'Q',suit:'d'},{rank:'7',suit:'c'},{rank:'2',suit:'s'}], ['下注','过牌','弃牌'], 'flop', 'check');
assert(s.phase === 'flop' && s.strategyPath === 'flop.pfr_cbet_plan', '翻牌: phase=flop path=pfr_cbet_plan');

s = feedVision([{rank:'A',suit:'h'},{rank:'K',suit:'s'}], [{rank:'Q',suit:'d'},{rank:'7',suit:'c'},{rank:'2',suit:'s'},{rank:'T',suit:'h'}], ['下注','过牌','弃牌'], 'turn', 'check');
assert(s.phase === 'turn' && s.strategyPath === 'turn.pfr_cbet_plan', '转牌: phase=turn path=pfr_cbet_plan');

s = feedVision([{rank:'A',suit:'h'},{rank:'K',suit:'s'}], [{rank:'Q',suit:'d'},{rank:'7',suit:'c'},{rank:'2',suit:'s'},{rank:'T',suit:'h'},{rank:'5',suit:'d'}], ['下注','过牌','弃牌'], 'river', 'check');
assert(s.phase === 'river' && s.strategyPath === 'river.pfr_cbet_plan', '河牌: phase=river path=pfr_cbet_plan');

// 5.2 Defender流程：跟注→vs CBet→vs Barrel→河牌
resetHSM();
console.log('  --- 5.2 Defender完整流程 ---');

s = feedVision([{rank:'J',suit:'h'},{rank:'T',suit:'s'}], [], ['跟注','弃牌'], 'preflop', 'raise');
HSM.recordPreflopAction('call');
s = HSM.getSummary();
assert(s.phase === 'preflop' && s.heroRole === 'defender', '翻前: role=defender');

s = feedVision([{rank:'J',suit:'h'},{rank:'T',suit:'s'}], [{rank:'Q',suit:'d'},{rank:'7',suit:'c'},{rank:'2',suit:'s'}], ['跟注','加注','弃牌'], 'flop', 'bet');
assert(s.phase === 'flop' && s.strategyPath === 'flop.defend_vs_cbet', '翻牌: path=defend_vs_cbet');

s = feedVision([{rank:'J',suit:'h'},{rank:'T',suit:'s'}], [{rank:'Q',suit:'d'},{rank:'7',suit:'c'},{rank:'2',suit:'s'},{rank:'T',suit:'h'}], ['跟注','弃牌'], 'turn', 'bet');
assert(s.phase === 'turn' && s.strategyPath === 'turn.defend_vs_cbet', '转牌: path=defend_vs_cbet');

s = feedVision([{rank:'J',suit:'h'},{rank:'T',suit:'s'}], [{rank:'Q',suit:'d'},{rank:'7',suit:'c'},{rank:'2',suit:'s'},{rank:'T',suit:'h'},{rank:'5',suit:'d'}], ['跟注','弃牌'], 'river', 'bet');
assert(s.phase === 'river' && s.strategyPath === 'river.defend_vs_cbet', '河牌: path=defend_vs_cbet');

// 5.3 新手牌重置
resetHSM();
s = feedVision([{rank:'A',suit:'h'},{rank:'K',suit:'s'}], [], ['加注','弃牌'], 'preflop', 'open');
HSM.recordPreflopAction('raise');
s = feedVision([{rank:'A',suit:'h'},{rank:'K',suit:'s'}], [{rank:'Q',suit:'d'},{rank:'7',suit:'c'},{rank:'2',suit:'s'}], ['下注','过牌','弃牌'], 'flop', 'check');
assert(s.phase === 'flop' && s.heroRole === 'pfr', '翻牌: 仍是PFR');

// 新手牌
s = feedVision([{rank:'9',suit:'h'},{rank:'8',suit:'s'}], [], ['加注','弃牌'], 'preflop', 'open');
assert(s.phase === 'preflop', '新手牌: phase=preflop');
assert(s.heroRole === 'unknown', '新手牌: role重置为unknown (got: ' + s.heroRole + ')');
assert(HSM.getStreetHistory().length === 0, '新手牌: 街变化历史清空');

// ============================================
// Test Suite 6: SubState推断
// ============================================
console.log('\n══════════════════════════════════════════════════');
console.log('  6. PostflopSubState推断');
console.log('══════════════════════════════════════════════════');

// 6.1 PFR有主动权
resetHSM();
s = feedVision([{rank:'A',suit:'h'},{rank:'K',suit:'s'}], [], ['加注','弃牌'], 'preflop', 'open');
HSM.recordPreflopAction('raise');
s = feedVision([{rank:'A',suit:'h'},{rank:'K',suit:'s'}], [{rank:'Q',suit:'d'},{rank:'7',suit:'c'},{rank:'2',suit:'s'}], ['下注','过牌','弃牌'], 'flop', 'check');
assert(s.subState === 'initiative', 'PFR翻牌check → subState=initiative (got: ' + s.subState + ')');

// 6.2 面对下注
resetHSM();
s = feedVision([{rank:'J',suit:'h'},{rank:'T',suit:'s'}], [], ['跟注','弃牌'], 'preflop', 'raise');
HSM.recordPreflopAction('call');
s = feedVision([{rank:'J',suit:'h'},{rank:'T',suit:'s'}], [{rank:'Q',suit:'d'},{rank:'7',suit:'c'},{rank:'2',suit:'s'}], ['跟注','弃牌'], 'flop', 'bet');
assert(s.subState === 'facing_bet', 'Defender面对下注 → subState=facing_bet (got: ' + s.subState + ')');

// 6.3 面对加注 (scene=raise)
resetHSM();
s = feedVision([{rank:'A',suit:'h'},{rank:'K',suit:'s'}], [], ['加注','弃牌'], 'preflop', 'open');
HSM.recordPreflopAction('raise');
s = feedVision([{rank:'A',suit:'h'},{rank:'K',suit:'s'}], [{rank:'Q',suit:'d'},{rank:'7',suit:'c'},{rank:'2',suit:'s'}], ['跟注','加注','弃牌'], 'flop', 'raise');
assert(s.subState === 'facing_raise', '面对加注(scene=raise) → subState=facing_raise (got: ' + s.subState + ')');

// 6.4 Defender check scene = probe opportunity (initiative, not check_behind)
// In real poker: opponent checks → we have initiative to probe or check behind
resetHSM();
s = feedVision([{rank:'J',suit:'h'},{rank:'T',suit:'s'}], [], ['跟注','弃牌'], 'preflop', 'raise');
HSM.recordPreflopAction('call');
s = feedVision([{rank:'J',suit:'h'},{rank:'T',suit:'s'}], [{rank:'Q',suit:'d'},{rank:'7',suit:'c'},{rank:'2',suit:'s'}], ['过牌','弃牌'], 'flop', 'check');
assert(s.subState === 'initiative', 'Defender check scene → subState=initiative (probe机会) (got: ' + s.subState + ')');

// ============================================
// Test Suite 7: Check-Check连续让牌 → free card
// ============================================
console.log('\n══════════════════════════════════════════════════');
console.log('  7. Check-Check → free card');
console.log('══════════════════════════════════════════════════');

resetHSM();
s = feedVision([{rank:'A',suit:'h'},{rank:'K',suit:'s'}], [], ['加注','弃牌'], 'preflop', 'open');
HSM.recordPreflopAction('raise');

// Flop: 双方check
s = feedVision([{rank:'A',suit:'h'},{rank:'K',suit:'s'}], [{rank:'Q',suit:'d'},{rank:'7',suit:'c'},{rank:'2',suit:'s'}], ['过牌','弃牌'], 'flop', 'check');
assert(s.subState === 'initiative', 'PFR翻牌check → 仍有initiative (got: ' + s.subState + ')');

// Turn: free card后
s = feedVision([{rank:'A',suit:'h'},{rank:'K',suit:'s'}], [{rank:'Q',suit:'d'},{rank:'7',suit:'c'},{rank:'2',suit:'s'},{rank:'T',suit:'h'}], ['下注','过牌','弃牌'], 'turn', 'check');
assert(s.phase === 'turn' && s.heroRole === 'pfr', 'Turn: 仍是PFR');
assert(s.strategyPath === 'turn.pfr_cbet_plan', 'Turn: PFR仍有CBet计划路径 (got: ' + s.strategyPath + ')');

// ============================================
// Test Suite 8: Vision API comm过滤验证
// ============================================
console.log('\n══════════════════════════════════════════════════');
console.log('  8. HandHistory comm过滤验证');
console.log('══════════════════════════════════════════════════');

resetHSM();
G.hole = [{rank:'A',suit:'h'},{rank:'K',suit:'s'}];
G.comm = [{rank:'Q',suit:'d'},{rank:'7',suit:'c'},{rank:'2',suit:'s'},null,null]; // 3有效+2null

var HandHistory = global.HandHistory;
HandHistory.records = [];

// 模拟一个结果
var fakeResult = {a:'raise', r:'test', eq:65, c:'h', hClass:{name:'STRONG',desc:'test',outs:0}};

// 手动设置导出需要的变量
global.window._goStartTime = Date.now() - 50;
global.window._lastVisionPot = 5000;
global.window._lastVisionChips = 3000;
global.window._lastVisionBB = 500;

HandHistory.add(fakeResult, true);

var lastRec = HandHistory.records[0];
assert(lastRec !== undefined, 'HandHistory有记录');
if (lastRec) {
  // comm应该只有3张有效牌
  var hasNull = lastRec.comm.some(function(c) { return c === '?' || c === null || c === ''; });
  assert(!hasNull, 'comm无null/问号 (got: ' + JSON.stringify(lastRec.comm) + ')');
  assert(lastRec.comm.length === 3, 'comm只有3张 (got: ' + lastRec.comm.length + '张)');
}

// ============================================
// 结果汇总
// ============================================
console.log('\n══════════════════════════════════════════════════');
console.log('  测试汇总');
console.log('══════════════════════════════════════════════════\n');
console.log('  通过: ' + passedTests + '/' + totalTests);
console.log('  失败: ' + failedTests + '/' + totalTests);

if (failedTests === 0) {
  console.log('\n  🎉 全部通过！HSM Observer模式验证完成');
} else {
  console.log('\n  ⚠️ 有失败项，需检查');
  process.exit(1);
}

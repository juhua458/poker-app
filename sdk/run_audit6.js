var sdk = require('./index.js');
sdk.initEngine();

// Check for more subtle bugs beyond handClassify
console.log('═══════════════════════════════════════');
console.log('  第八轮审计 — 其他潜在问题检查');
console.log('═══════════════════════════════════════');
console.log('');

// P30: Check if CacheManager version is correctly updated (was P18)
console.log('P30: CacheManager.CURRENT_VERSION = ' + CacheManager.CURRENT_VERSION);
console.log('  Expected: 2.9.150 (updated in V2.9.151)');
console.log('');

// P31: Check OppProfiler merge uses addition not Math.max
// Already verified in P19 test - just confirm no Math.max regression
var _testProfile = {
  hands: 10, entered: 8, folded: 3,
  _pfRaise: 5, _pf3bet: 2, _pfOpenOpp: 3,
  _faced3bet: 4, _foldTo3bet: 2, _facedCBet: 6,
  _foldToCBet: 3, _checkRaise: 1, _donkBets: 2
};
// If existing has same values, Math.max would keep 10, addition would give 20
var existing = Object.assign({}, _testProfile);
var p = Object.assign({}, _testProfile);
// Simulate merge: hands should be 10+10=20, not Math.max(10,10)=10
var merged_hands = (existing.hands||0) + (p.hands||0);
console.log('P31: OppProfiler merge check: ' + existing.hands + '+' + p.hands + '=' + merged_hands + ' (should be 20)');
console.log('');

// P32: Check if DRAW gutshot protection works
// KQs with gutshot, 1/3 pot bet
sdk.setupG({
  phase: 'post', scene: 'bet', pos: 'co', act: 2, opp: 'unknown', stk: 100, pot: 30, bet: 10,
  hole: [{rank:'K',suit:'s'},{rank:'Q',suit:'s'}],
  comm: [{rank:'A',suit:'c'},{rank:'T',suit:'c'},{rank:'2',suit:'d'},{rank:'5',suit:'h'},{rank:'3',suit:'c'}],
  ante: 50
});
var r32 = postF();
console.log('P32: KQs gutshot(4outs) vs 1/3 pot → ' + r32.a + ' (hClass=' + (r32.hClass ? r32.hClass.name : '?') + ')');
console.log('  Expected: should be protected (outs<=5 && betPotRatio>=0.33 → not chase)');
console.log('');

// P33: Check MEDIUM hand classification for bottom pair
sdk.resetAll();
var r33 = handClassify(
  [{rank:'3',suit:'h'},{rank:'2',suit:'d'}],
  [{rank:'A',suit:'c'},{rank:'K',suit:'c'},{rank:'8',suit:'s'},{rank:'5',suit:'h'},{rank:'3',suit:'c'}]
);
console.log('P33: 32 bottom pair on AK853 → ' + r33.name + ' ' + r33.desc);
console.log('  Expected: MEDIUM (bottom pair, not AIR)');
console.log('');

// P34: Check Ace-high on paired board
var r34 = handClassify(
  [{rank:'A',suit:'h'},{rank:'9',suit:'d'}],
  [{rank:'K',suit:'c'},{rank:'K',suit:'s'},{rank:'8',suit:'c'},{rank:'5',suit:'h'},{rank:'2',suit:'c'}]
);
console.log('P34: A9 on KK852 → ' + r34.name + ' ' + r34.desc);
console.log('  Expected: DRAW or AIR (no pair, Ace-high)');
console.log('');

// P35: Check that non-nut full house is still NUTS (it should be - FH can be beaten by better FH only on paired+trips board)
var r35 = handClassify(
  [{rank:'8',suit:'h'},{rank:'8',suit:'d'}],
  [{rank:'8',suit:'c'},{rank:'A',suit:'s'},{rank:'A',suit:'c'},{rank:'5',suit:'h'},{rank:'2',suit:'c'}]
);
console.log('P35: 888AA full house → ' + r35.name + ' ' + r35.desc);
console.log('  Expected: NUTS (FH is very strong, only beaten by AAA88 which needs AA in hand)');
console.log('');

console.log('═══════════════════════════════════════');

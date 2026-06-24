var sdk = require('./index.js');
sdk.initEngine();

var _totalTests = 0;
var _failCount = 0;

function test(name, hole, comm, expectedNot, expectedYes) {
  var result = handClassify(hole, comm);
  var passed = true;
  if (expectedNot && result.name === expectedNot) passed = false;
  if (expectedYes && result.name !== expectedYes) passed = false;
  var status = passed ? '✅' : '❌';
  console.log('  ' + status + ' ' + name + ': ' + result.name + ' ' + result.desc + 
    (expectedNot ? ' (should not be ' + expectedNot + ')' : '') +
    (expectedYes ? ' (expected ' + expectedYes + ')' : ''));
  if (!passed) _failCount++;
  _totalTests++;
  return result;
}

console.log('═══════════════════════════════════════');
console.log('  第八轮审计 — handClassify BUG验证');
console.log('═══════════════════════════════════════');
console.log('');

// P21: set on board with 4-flush → set NUTS is wrong, opponent can have flush
test('P21: set on 4-flush board', 
  [{rank:'7',suit:'h'},{rank:'7',suit:'d'}],
  [{rank:'A',suit:'s'},{rank:'7',suit:'s'},{rank:'2',suit:'s'},{rank:'8',suit:'s'},{rank:'3',suit:'c'}],
  'NUTS', null);
// Expected: STRONG (not NUTS, because flush possible)

// P22: non-nut straight (wheel A-5 on board with higher straight possible)
test('P22: wheel straight on broadway board',
  [{rank:'5',suit:'h'},{rank:'4',suit:'d'}],
  [{rank:'A',suit:'s'},{rank:'2',suit:'c'},{rank:'3',suit:'d'},{rank:'K',suit:'c'},{rank:'T',suit:'c'}],
  'NUTS', null);
// Expected: STRONG (not NUTS, because AKQJT higher straight possible)

// P24: straight on paired board (full house possible)
test('P24: straight on paired board',
  [{rank:'9',suit:'h'},{rank:'T',suit:'d'}],
  [{rank:'J',suit:'c'},{rank:'Q',suit:'s'},{rank:'K',suit:'c'},{rank:'K',suit:'h'},{rank:'2',suit:'c'}],
  'NUTS', null);

// P25: flush on paired board (full house possible)
test('P25: flush on paired board (full house possible)',
  [{rank:'A',suit:'s'},{rank:'K',suit:'s'}],
  [{rank:'Q',suit:'s'},{rank:'8',suit:'s'},{rank:'5',suit:'s'},{rank:'8',suit:'h'},{rank:'2',suit:'c'}],
  'NUTS', null);

// Control1: set on completely dry board → should be NUTS
test('C1: set on dry rainbow board',
  [{rank:'7',suit:'h'},{rank:'7',suit:'d'}],
  [{rank:'A',suit:'c'},{rank:'7',suit:'s'},{rank:'2',suit:'c'},{rank:'8',suit:'c'},{rank:'3',suit:'d'}],
  null, 'NUTS');

// Control2: nut flush on non-paired non-paired → NUTS
test('C2: nut flush on clean board',
  [{rank:'A',suit:'s'},{rank:'K',suit:'s'}],
  [{rank:'Q',suit:'s'},{rank:'8',suit:'s'},{rank:'5',suit:'s'},{rank:'2',suit:'c'},{rank:'3',suit:'d'}],
  null, 'NUTS');

// Control3: quads always NUTS
test('C3: quads',
  [{rank:'7',suit:'h'},{rank:'7',suit:'d'}],
  [{rank:'7',suit:'c'},{rank:'7',suit:'s'},{rank:'A',suit:'c'},{rank:'K',suit:'c'},{rank:'2',suit:'c'}],
  null, 'NUTS');

// Control4: full house always NUTS (even on wet board)
test('C4: full house on wet board',
  [{rank:'7',suit:'h'},{rank:'7',suit:'d'}],
  [{rank:'7',suit:'s'},{rank:'A',suit:'s'},{rank:'A',suit:'c'},{rank:'K',suit:'s'},{rank:'2',suit:'c'}],
  null, 'NUTS');

// Control5: set on straight-possible board (3-connected)
test('C5: set on 3-connected board',
  [{rank:'7',suit:'h'},{rank:'7',suit:'d'}],
  [{rank:'7',suit:'c'},{rank:'6',suit:'c'},{rank:'5',suit:'c'},{rank:'2',suit:'d'},{rank:'K',suit:'h'}],
  null, 'NUTS');

console.log('');
console.log('═══════════════════════════════════════');
console.log('  结果: ' + (_totalTests - _failCount) + '/' + _totalTests + ' 通过, ' + _failCount + ' 失败');
if(_failCount > 0) {
  console.log('  ⚠️ 发现 ' + _failCount + ' 个BUG！');
  console.log('  根因: handClassify L4241把set/straight/flush一律判NUTS');
  console.log('  没有检查湿面(paired/4-flush/高顺)降级');
}
console.log('═══════════════════════════════════════');

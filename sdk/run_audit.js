var sdk = require('./index.js');
sdk.initEngine();

var _totalTests = 0;
var _failCount = 0;

// P21: set on wet board should NOT be NUTS
(function(){
  var hole = [{rank:'7',suit:'h'},{rank:'7',suit:'d'}];
  var comm = [{rank:'A',suit:'s'},{rank:'7',suit:'s'},{rank:'2',suit:'s'},{rank:'8',suit:'c'},{rank:'3',suit:'c'}];
  var result = handClassify(hole, comm);
  var passed = result.name !== 'NUTS';
  console.log(passed ? '  ✅ P21: set on flush board → ' + result.name : '  ❌ P21: set on flush board → ' + result.name + ' (BUG!)');
  if(!passed) _failCount++;
  _totalTests++;
})();

// P22: non-nut straight should NOT be NUTS
(function(){
  var hole = [{rank:'5',suit:'h'},{rank:'4',suit:'d'}];
  var comm = [{rank:'A',suit:'s'},{rank:'2',suit:'c'},{rank:'3',suit:'d'},{rank:'K',suit:'c'},{rank:'T',suit:'c'}];
  var result = handClassify(hole, comm);
  var passed = result.name !== 'NUTS';
  console.log(passed ? '  ✅ P22: wheel straight on broadway → ' + result.name : '  ❌ P22: wheel straight → ' + result.name + ' (BUG!)');
  if(!passed) _failCount++;
  _totalTests++;
})();

// P23: flush on paired board should NOT be NUTS
(function(){
  var hole = [{rank:'A',suit:'s'},{rank:'K',suit:'s'}];
  var comm = [{rank:'Q',suit:'s'},{rank:'8',suit:'s'},{rank:'8',suit:'h'},{rank:'5',suit:'c'},{rank:'2',suit:'c'}];
  var result = handClassify(hole, comm);
  var passed = result.name !== 'NUTS';
  console.log(passed ? '  ✅ P23: flush on paired board → ' + result.name : '  ❌ P23: flush on paired board → ' + result.name + ' (BUG!)');
  if(!passed) _failCount++;
  _totalTests++;
})();

// P24: straight on paired board
(function(){
  var hole = [{rank:'9',suit:'h'},{rank:'T',suit:'d'}];
  var comm = [{rank:'J',suit:'c'},{rank:'Q',suit:'s'},{rank:'K',suit:'c'},{rank:'K',suit:'h'},{rank:'2',suit:'c'}];
  var result = handClassify(hole, comm);
  var passed = result.name !== 'NUTS';
  console.log(passed ? '  ✅ P24: straight on paired board → ' + result.name : '  ❌ P24: straight on paired board → ' + result.name + ' (BUG!)');
  if(!passed) _failCount++;
  _totalTests++;
})();

// Control1: set on dry board SHOULD be NUTS
(function(){
  var hole = [{rank:'7',suit:'h'},{rank:'7',suit:'d'}];
  var comm = [{rank:'A',suit:'c'},{rank:'7',suit:'s'},{rank:'2',suit:'c'},{rank:'8',suit:'c'},{rank:'3',suit:'d'}];
  var result = handClassify(hole, comm);
  var passed = result.name === 'NUTS' || result.name === 'STRONG';
  console.log(passed ? '  ✅ C1: set on dry board → ' + result.name : '  ❌ C1: set on dry board → ' + result.name);
  if(!passed) _failCount++;
  _totalTests++;
})();

// Control2: nut flush on non-paired SHOULD be NUTS
(function(){
  var hole = [{rank:'A',suit:'s'},{rank:'K',suit:'s'}];
  var comm = [{rank:'Q',suit:'s'},{rank:'8',suit:'s'},{rank:'5',suit:'c'},{rank:'2',suit:'c'},{rank:'3',suit:'d'}];
  var result = handClassify(hole, comm);
  var passed = result.name === 'NUTS';
  console.log(passed ? '  ✅ C2: nut flush non-paired → ' + result.name : '  ❌ C2: nut flush non-paired → ' + result.name);
  if(!passed) _failCount++;
  _totalTests++;
})();

// Control3: quads always NUTS
(function(){
  var hole = [{rank:'7',suit:'h'},{rank:'7',suit:'d'}];
  var comm = [{rank:'7',suit:'c'},{rank:'7',suit:'s'},{rank:'A',suit:'c'},{rank:'K',suit:'c'},{rank:'2',suit:'c'}];
  var result = handClassify(hole, comm);
  var passed = result.name === 'NUTS';
  console.log(passed ? '  ✅ C3: quads → ' + result.name : '  ❌ C3: quads → ' + result.name);
  if(!passed) _failCount++;
  _totalTests++;
})();

// Info: set on straight possible board
(function(){
  var hole = [{rank:'7',suit:'h'},{rank:'7',suit:'d'}];
  var comm = [{rank:'8',suit:'c'},{rank:'7',suit:'s'},{rank:'6',suit:'c'},{rank:'5',suit:'c'},{rank:'2',suit:'c'}];
  var result = handClassify(hole, comm);
  console.log('  ℹ️  Info: set on straight board → ' + result.name + ' ' + result.desc);
  _totalTests++;
})();

console.log('');
console.log('═══════════════════════════════════════');
console.log('  第八轮审计 handClassify 结果');
console.log('  通过: ' + (_totalTests - _failCount) + '/' + _totalTests);
console.log('  失败: ' + _failCount);
if(_failCount > 0) {
  console.log('  ⚠️  发现BUG！');
}
console.log('═══════════════════════════════════════');

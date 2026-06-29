
// === 第八轮审计场景 ===
// P21: set on wet board should NOT be NUTS
(function(){
  var hole = [{rank:'7',suit:'h'},{rank:'7',suit:'d'}];
  var comm = [{rank:'A',suit:'s'},{rank:'7',suit:'s'},{rank:'2',suit:'s'},{rank:'8',suit:'c'},{rank:'3',suit:'c'}];
  var result = handClassify(hole, comm);
  // 777 set on 3-spade board -> set is NOT nuts (flush possible)
  var passed = result.name !== 'NUTS';
  console.log(passed ? '  ✅ P21: set on flush board → ' + result.name + ' (expected not NUTS)' : '  ❌ P21: set on flush board → ' + result.name + ' (BUG: should not be NUTS!)');
  if(!passed) _failCount++;
  _totalTests++;
})();

// P22: non-nut straight on broadway board should NOT be NUTS
(function(){
  var hole = [{rank:'5',suit:'h'},{rank:'4',suit:'d'}];
  var comm = [{rank:'A',suit:'s'},{rank:'2',suit:'c'},{rank:'3',suit:'d'},{rank:'K',suit:'c'},{rank:'T',suit:'c'}];
  var result = handClassify(hole, comm);
  // A-5 wheel straight, but AK/AQ etc have higher straight
  var passed = result.name !== 'NUTS';
  console.log(passed ? '  ✅ P22: wheel straight on broadway → ' + result.name + ' (expected not NUTS)' : '  ❌ P22: wheel straight on broadway → ' + result.name + ' (BUG: should not be NUTS!)');
  if(!passed) _failCount++;
  _totalTests++;
})();

// P23: flush on paired board should NOT be NUTS (full house possible)
(function(){
  var hole = [{rank:'A',suit:'s'},{rank:'K',suit:'s'}];
  var comm = [{rank:'Q',suit:'s'},{rank:'8',suit:'s'},{rank:'8',suit:'h'},{rank:'5',suit:'c'},{rank:'2',suit:'c'}];
  var result = handClassify(hole, comm);
  // Nut flush on paired board - full house possible
  var passed = result.name !== 'NUTS';
  console.log(passed ? '  ✅ P23: flush on paired board → ' + result.name + ' (expected not NUTS)' : '  ❌ P23: flush on paired board → ' + result.name + ' (BUG: should not be NUTS!)');
  if(!passed) _failCount++;
  _totalTests++;
})();

// P24: straight on paired board - could be beaten by full house
(function(){
  var hole = [{rank:'9',suit:'h'},{rank:'T',suit:'d'}];
  var comm = [{rank:'J',suit:'c'},{rank:'Q',suit:'s'},{rank:'K',suit:'c'},{rank:'K',suit:'h'},{rank:'2',suit:'c'}];
  var result = handClassify(hole, comm);
  // 9-K straight on KK board - full house possible
  var passed = result.name !== 'NUTS';
  console.log(passed ? '  ✅ P24: straight on paired board → ' + result.name + ' (expected not NUTS)' : '  ❌ P24: straight on paired board → ' + result.name + ' (BUG: should not be NUTS!)');
  if(!passed) _failCount++;
  _totalTests++;
})();

// Control: set on dry board SHOULD be NUTS (or at least STRONG)
(function(){
  var hole = [{rank:'7',suit:'h'},{rank:'7',suit:'d'}];
  var comm = [{rank:'A',suit:'c'},{rank:'7',suit:'s'},{rank:'2',suit:'c'},{rank:'8',suit:'c'},{rank:'3',suit:'d'}];
  var result = handClassify(hole, comm);
  // 777 set on dry rainbow board -> should be NUTS/STRONG
  var passed = result.name === 'NUTS' || result.name === 'STRONG';
  console.log(passed ? '  ✅ Control: set on dry board → ' + result.name + ' (OK)' : '  ❌ Control: set on dry board → ' + result.name + ' (unexpected!)');
  if(!passed) _failCount++;
  _totalTests++;
})();

// Control: nut flush on non-paired board SHOULD be NUTS
(function(){
  var hole = [{rank:'A',suit:'s'},{rank:'K',suit:'s'}];
  var comm = [{rank:'Q',suit:'s'},{rank:'8',suit:'s'},{rank:'5',suit:'c'},{rank:'2',suit:'c'},{rank:'3',suit:'d'}];
  var result = handClassify(hole, comm);
  var passed = result.name === 'NUTS';
  console.log(passed ? '  ✅ Control: nut flush on non-paired → ' + result.name + ' (OK)' : '  ❌ Control: nut flush on non-paired → ' + result.name + ' (should be NUTS!)');
  if(!passed) _failCount++;
  _totalTests++;
})();

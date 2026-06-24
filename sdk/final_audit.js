var sdk = require('./index.js');
sdk.initEngine();

var _t=0, _f=0, _bugs=[];

function ck(n,ok,detail) {
  _t++;
  if(ok) console.log('  ✅ '+n);
  else { console.log('  ❌ '+n+(detail?' -- '+detail:'')); _f++; _bugs.push(n); }
}

console.log('');
console.log('第八轮SDK审计 - 全量结果');
console.log('');

// P21-P25: handClassify NUTS misclassification on wet boards
var r21=handClassify([{rank:'7',suit:'h'},{rank:'7',suit:'d'}],[{rank:'A',suit:'s'},{rank:'7',suit:'s'},{rank:'2',suit:'s'},{rank:'8',suit:'s'},{rank:'3',suit:'c'}]);
ck('P21: set+4flush not NUTS', r21.name!=='NUTS', 'got '+r21.name);

var r22=handClassify([{rank:'5',suit:'h'},{rank:'4',suit:'d'}],[{rank:'A',suit:'s'},{rank:'2',suit:'c'},{rank:'3',suit:'d'},{rank:'K',suit:'c'},{rank:'T',suit:'c'}]);
ck('P22: wheel+higher possible not NUTS', r22.name!=='NUTS', 'got '+r22.name);

var r24=handClassify([{rank:'9',suit:'h'},{rank:'T',suit:'d'}],[{rank:'J',suit:'c'},{rank:'Q',suit:'s'},{rank:'K',suit:'c'},{rank:'K',suit:'h'},{rank:'2',suit:'c'}]);
ck('P24: straight+paired not NUTS', r24.name!=='NUTS', 'got '+r24.name);

var r25=handClassify([{rank:'A',suit:'s'},{rank:'K',suit:'s'}],[{rank:'Q',suit:'s'},{rank:'8',suit:'s'},{rank:'5',suit:'s'},{rank:'8',suit:'h'},{rank:'2',suit:'c'}]);
ck('P25: flush+paired not NUTS', r25.name!=='NUTS', 'got '+r25.name);

// Controls - things that SHOULD be NUTS
var c1=handClassify([{rank:'7',suit:'h'},{rank:'7',suit:'d'}],[{rank:'A',suit:'c'},{rank:'7',suit:'s'},{rank:'2',suit:'c'},{rank:'8',suit:'c'},{rank:'3',suit:'d'}]);
ck('C1: dry set=NUTS', c1.name==='NUTS', 'got '+c1.name);

var c2=handClassify([{rank:'A',suit:'s'},{rank:'K',suit:'s'}],[{rank:'Q',suit:'s'},{rank:'8',suit:'s'},{rank:'5',suit:'s'},{rank:'2',suit:'c'},{rank:'3',suit:'d'}]);
ck('C2: clean flush=NUTS', c2.name==='NUTS', 'got '+c2.name);

var c3=handClassify([{rank:'7',suit:'h'},{rank:'7',suit:'d'}],[{rank:'7',suit:'c'},{rank:'7',suit:'s'},{rank:'A',suit:'c'},{rank:'K',suit:'c'},{rank:'2',suit:'c'}]);
ck('C3: quads=NUTS', c3.name==='NUTS', 'got '+c3.name);

var c4=handClassify([{rank:'8',suit:'h'},{rank:'8',suit:'d'}],[{rank:'8',suit:'c'},{rank:'A',suit:'s'},{rank:'A',suit:'c'},{rank:'5',suit:'h'},{rank:'2',suit:'c'}]);
ck('C4: full house=NUTS', c4.name==='NUTS', 'got '+c4.name);

// P18 regression
var ver = CacheManager.CURRENT_VERSION;
ck('P18: CacheManager ok', ver === '2.9.152', 'got '+ver);

// Bottom pair
var bp=handClassify([{rank:'3',suit:'h'},{rank:'2',suit:'d'}],[{rank:'A',suit:'c'},{rank:'K',suit:'c'},{rank:'8',suit:'s'},{rank:'5',suit:'h'},{rank:'3',suit:'c'}]);
ck('底对=MEDIUM', bp.name==='MEDIUM', 'got '+bp.name);

console.log('');
console.log('═══════════════════════════════════════');
console.log('  结果: '+(_t-_f)+'/'+_t+' 通过, '+_f+' 失败');
if(_f>0) {
  console.log('');
  console.log('  BUG清单:');
  _bugs.forEach(function(b){console.log('    - '+b);});
  console.log('');
  console.log('  根因: handClassify L4241 set/straight/flush一律判NUTS');
  console.log('  没有检测湿面(4-flush/paired/非坚果顺)降级为STRONG');
  console.log('');
  console.log('  影响:');
  console.log('  - MC eq准确→决策基本正确');
  console.log('  - NUTS标签→触发慢打/超池路径→可能过度激进');
  console.log('  - 7色信号误判(显示坚果实际可被beat)');
  console.log('');
  console.log('  修复: handClassify增加湿面检测,降级NUTS→STRONG');
  console.log('  优先级: P29(顺+对) > P21(set+4花) > P24 > P22 > P25');
} else {
  console.log('  🎉 全部通过！');
}
console.log('═══════════════════════════════════════');

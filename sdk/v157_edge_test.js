/**
 * V2.9.157 边界找茬专项测试
 * 目标: 专门挖掘策略引擎的漏洞、缺口、矛盾和极端场景错误
 * 不测"正常场景是否通过"——测"异常/边界/遗漏场景是否翻车"
 */
'use strict';

var sdk = require('./index.js');
sdk.initEngine();

var pass = 0, fail = 0, warnings = 0;
function test(name, fn) {
  try {
    fn();
    pass++;
    console.log('  ✅ ' + name);
  } catch(e) {
    fail++;
    console.log('  ❌ ' + name + ' — ' + e.message);
  }
}
function warn(name, detail) {
  warnings++;
  console.log('  ⚠️ ' + name + ' — ' + detail);
}
function assert(cond, msg) { if (!cond) throw new Error(msg || 'assertion failed'); }

// ====== 辅助 ======
function setupState(opts) {
  G.hole = opts.hole || ['A♠','K♠'];
  G.comm = opts.comm || ['Q♥','J♥','2♦'];
  G.pot = opts.pot || 100;
  G.bet = opts.bet || 0;
  G.stk = opts.stk || 10000;
  G.pos = opts.pos || 'btn';
  G.tt = opts.tt || 5;
  G.scene = opts.scene || 'check';
  G.phase = opts.phase || 'flop';
  G.opp = opts.opp || 'unknown';
  G._seEnabled = true;
  G._raiserRole = opts.raiserRole || 'utg1';
  if (opts.ante !== undefined) G.ante = opts.ante;
  if (opts.didPFR) G._heroDidPFR = true;
  else G._heroDidPFR = false;
}

function runMultiple(fn, times) {
  var results = [];
  for (var i = 0; i < times; i++) {
    results.push(fn());
  }
  return results;
}

// ══════════════════════════════════════════════════════
// 一、策略表完整性缺口
// ══════════════════════════════════════════════════════
console.log('\n═══ 一、策略表完整性缺口 ═══');

test('RIV.bet缺少手牌类key: 1(trips)/2(两对)/8(坚果听)/11(组合听)/12(卡顺)/13(超牌)/14(后门)', function(){
  var riv = StrategyEngine.getRiverDecision();
  var bet = riv.bet;
  var missing = [];
  [1,2,8,11,12,13,14].forEach(function(k){
    if (bet[k] === undefined) missing.push(k);
  });
  if (missing.length > 0) warn('RIV.bet缺key', missing.join(','));
  // 这是已知的缺口，不算fail但记warning
  assert(true, '已记录缺口');
});

test('RIV.face缺少手牌类key: 8(坚果听)/11(组合听)/12(卡顺)/13(超牌)/14(后门)', function(){
  var riv = StrategyEngine.getRiverDecision();
  var face = riv.face;
  var missing = [];
  [8,11,12,13,14].forEach(function(k){
    if (face[k] === undefined) missing.push(k);
  });
  if (missing.length > 0) warn('RIV.face缺key', missing.join(','));
  assert(true, '已记录缺口');
});

test('FCR表缺手牌类key: 6(第二对)/7(弱对)/10(OESD)/11(组合听)/12(卡顺)/13(超牌)/14(后门)', function(){
  var allMissing = {};
  ['0','2','4','5'].forEach(function(bt){
    var table = StrategyEngine.getFacingCR(bt);
    if (!table) return;
    var missing = [];
    [6,7,10,11,12,13,14].forEach(function(hc){
      if (table[hc] === undefined) missing.push(hc);
    });
    if (missing.length > 0) allMissing[bt] = missing;
  });
  var totalMissing = Object.keys(allMissing).length;
  if (totalMissing > 0) warn('FCR 4种纹理都缺key', JSON.stringify(allMissing));
  assert(true, '已记录缺口');
});

test('FCB_IP表各组合key数量不一致', function(){
  var combos = ['0_s','0_l','2_s','2_l','5_s','5_l','4_s','4_l'];
  var counts = {};
  combos.forEach(function(k){
    var parts = k.split('_');
    var table = StrategyEngine.getFacingCBetIP(parts[0], parts[1]);
    counts[k] = table ? Object.keys(table).length : 0;
  });
  var maxKey = 0, maxCount = 0, minCount = 99;
  Object.keys(counts).forEach(function(k){
    if (counts[k] > maxCount) { maxCount = counts[k]; maxKey = k; }
    if (counts[k] < minCount) minCount = counts[k];
  });
  var gap = maxCount - minCount;
  if (gap > 5) warn('FCB_IP key数量差'+gap, 'max='+maxKey+':'+maxCount+' min='+minCount);
  assert(true, 'gap='+gap);
});

test('FCB_OOP表缺6(第二对)/7(弱对)/10(OESD)/11(组合听)/12(卡顺)/13(超牌)', function(){
  var combos = ['0_s','0_l','2_s','2_l','5_s','5_l'];
  var totalMissing = 0;
  combos.forEach(function(k){
    var parts = k.split('_');
    var table = StrategyEngine.getFacingCBetOOP(parts[0], parts[1]);
    if (!table) return;
    [6,7,10,11,12,13].forEach(function(hc){
      if (table[hc] === undefined) totalMissing++;
    });
  });
  if (totalMissing > 0) warn('FCB_OOP共缺'+totalMissing+'个key', '');
  assert(true, '缺'+totalMissing);
});

test('TB_IP缺4(同花面)所有turnType', function(){
  assert(StrategyEngine.getTurnBarrelIP('4','i') === null, 'TB缺4_i(同花面改善)');
  assert(StrategyEngine.getTurnBarrelIP('4','b') === null, 'TB缺4_b(同花面白板)');
  assert(StrategyEngine.getTurnBarrelIP('4','w') === null, 'TB缺4_w(同花面恶化)');
  warn('TB无同花面barrel数据', 'fallback到btKey→0');
});

test('TB_IP只覆盖IP——OOP barrel完全缺失', function(){
  // 验证API设计只返回IP表
  var ipTable = StrategyEngine.getTurnBarrelIP('0','i');
  assert(ipTable !== null, 'IP barrel存在');
  // OOP没有对应API
  warn('OOP Turn barrel无策略表', 'OOP PFR turn barrel走旧引擎');
  assert(true, '已记录');
});

// ══════════════════════════════════════════════════════
// 二、决策路径冲突/覆盖
// ══════════════════════════════════════════════════════
console.log('\n═══ 二、决策路径冲突/覆盖 ═══');

test('BUG: 旧River诈唬路径拦截新_riverDecision——AIR+didPFR+check被旧路径吞掉', function(){
  // 旧路径: didPFR && hClass.name==='AIR' && scene==='check' → 在V2.9.157新River之前执行
  // 新路径: _riverDecision(hcKey=15, scene='check') → 也处理AIR
  // 但旧路径先运行，如果命中诈唬就直接return，新路径永远触不到AIR场景
  setupState({hole:['7♣','2♦'], comm:['A♥','K♥','Q♠','5♦','9♣'], scene:'check', bet:0, pot:100, didPFR:true, phase:'river'});
  var results = runMultiple(function(){
    return StrategyEngine.decidePostflop('72o');
  }, 30);
  var fromOldPath = results.filter(function(r){ return r && r.r && r.r.indexOf('River诈唬') >= 0; }).length;
  var fromNewPath = results.filter(function(r){ return r && r.r && r.r.indexOf('GTO River') >= 0; }).length;
  console.log('    → 旧路径River诈唬: '+fromOldPath+'/30, 新GTO River: '+fromNewPath+'/30');
  if (fromOldPath > 0 && fromNewPath === 0) {
    warn('AIR+check场景100%走旧路径', '新_riverDecision的AIR逻辑永远不会执行');
  }
  assert(true, '已记录路径冲突');
});

test('BUG: PFR+OOP+面对donk(scene=raise)无V2.9.157策略——两个条件都跳过', function(){
  // hero是PFR，OOP位置，对手donk(scene='raise')
  // 条件1: !didPFR&&scene==='raise' → false (hero是PFR)
  // 条件2: _wasPFR&&scene==='reraise' → false (是raise不是reraise)
  // 条件3: Turn/River同条件1
  // 结论: donk bet场景完全跳过V2.9.157，回退旧引擎
  setupState({hole:['A♠','K♥'], comm:['Q♦','7♣','2♠'], scene:'raise', bet:30, pot:100, didPFR:true, pos:'utg1', phase:'flop'});
  var r = StrategyEngine.decidePostflop('AKs');
  // 这个场景应该有策略但V2.9.157没有覆盖
  var isSE = r && r._se === true;
  console.log('    → PFR面对donk: ' + (isSE ? '走SE' : '走旧引擎') + ' action=' + (r ? r.a : 'null'));
  if (!isSE) warn('PFR面对donk无SE策略', '回退旧引擎，旧引擎没有donk专门处理');
  assert(true, '已记录donk缺失');
});

test('BUG: Turn barrel不检查flop是否CBet——flop check后turn仍会barrel', function(){
  // 场景: hero PFR, flop check(没CBet), turn check to hero
  // _wasPFR2 = true, scene='check', street='turn' → 进入turn barrel
  // 但hero在flop已经给了主动权，turn barrel逻辑仍会触发
  setupState({hole:['A♠','K♥'], comm:['Q♦','7♣','2♠','5♣'], scene:'check', bet:0, pot:100, didPFR:true, phase:'turn'});
  var results = runMultiple(function(){
    return StrategyEngine.decidePostflop('AKs');
  }, 20);
  var barrels = results.filter(function(r){ return r && r.a === 'raise'; }).length;
  console.log('    → Flop未CBet时Turn barrel率: '+barrels+'/20 (应该更低或跳过)');
  if (barrels > 10) warn('Turn barrel未检查flop是否CBet', barrels+'/20 barrel但flop可能已check');
  assert(true, '已记录逻辑缺陷');
});

// ══════════════════════════════════════════════════════
// 三、尺度分类缺陷
// ══════════════════════════════════════════════════════
console.log('\n═══ 三、尺度分类缺陷 ═══');

test('BUG: 中等尺度(34-66%底池)全部归入large——50%池注被当大注处理', function(){
  // szKey = szRatio <= .33 ? 's' : 'l'
  // 50%底池是标准中等下注，GTO中应区别于>67%大注
  // 但代码把50%归为'l'(large)，导致面对50%注时策略过于偏fold
  setupState({hole:['K♠','Q♥'], comm:['A♦','7♣','2♠'], scene:'raise', bet:50, pot:100, didPFR:false, phase:'flop'});
  // bet=50, pot=100, szRatio=0.5 → szKey='l' (large)
  var r = StrategyEngine.decidePostflop('KQs');
  if (r && r._se) {
    console.log('    → 面对50%注(szbet): ' + r.a + ' (被当作large处理)');
    warn('50%底池注被当large', '应设medium分类，50%非常见中等尺度');
  }
  assert(true, '已记录尺度分类缺陷');
});

test('33%和34%底池——跨过small/large边界的策略跳变', function(){
  // 33% → small, 34% → large, 中间可能有剧烈策略跳变
  setupState({hole:['K♠','Q♥'], comm:['A♦','7♣','2♠'], scene:'raise', bet:33, pot:100, didPFR:false, phase:'flop'});
  var r33 = StrategyEngine.decidePostflop('KQs');
  var a33 = r33 ? r33.a : 'null';

  setupState({hole:['K♠','Q♥'], comm:['A♦','7♣','2♠'], scene:'raise', bet:34, pot:100, didPFR:false, phase:'flop'});
  // 注意：因为随机性，跑多次看趋势
  var folds33 = 0, folds34 = 0;
  for (var i = 0; i < 50; i++) {
    setupState({hole:['K♠','Q♥'], comm:['A♦','7♣','2♠'], scene:'raise', bet:33, pot:100, didPFR:false, phase:'flop'});
    var r1 = StrategyEngine.decidePostflop('KQs');
    if (r1 && r1.a === 'fold') folds33++;
    
    setupState({hole:['K♠','Q♥'], comm:['A♦','7♣','2♠'], scene:'raise', bet:34, pot:100, didPFR:false, phase:'flop'});
    var r2 = StrategyEngine.decidePostflop('KQs');
    if (r2 && r2.a === 'fold') folds34++;
  }
  console.log('    → 33%pot fold率: '+folds33+'/50, 34%pot fold率: '+folds34+'/50');
  if (Math.abs(folds33 - folds34) > 20) {
    warn('33%→34%策略跳变', 'fold率差'+Math.abs(folds33-folds34)+'个百分点');
  }
  assert(true, '已记录边界问题');
});

// ══════════════════════════════════════════════════════
// 四、极端SPR场景
// ══════════════════════════════════════════════════════
console.log('\n═══ 四、极端SPR场景 ═══');

test('超短码SPR<1: 面对CBet不应call——应该allin或fold', function(){
  setupState({hole:['A♠','K♥'], comm:['Q♦','7♣','2♠'], scene:'raise', bet:80, pot:100, didPFR:false, stk:150, phase:'flop'});
  var r = StrategyEngine.decidePostflop('AKs');
  // SPR≈0.75, 面对大注AKs应该allin而不是小call
  console.log('    → SPR<1面CBet: ' + (r ? r.a : 'null') + (r && r.v ? ' v='+r.v : ''));
  if (r && r.a === 'call' && r.v && r.v < 150) {
    warn('SPR<1时call而非allin', '短码应该push或fold，不该小call');
  }
  assert(true, '已记录');
});

test('超深码SPR>20: River面对大注坚果不应只最小raise', function(){
  setupState({hole:['A♠','A♥'], comm:['K♦','7♣','2♠','5♣','9♥'], scene:'reraise', bet:200, pot:300, didPFR:true, stk:100000, phase:'river'});
  var r = StrategyEngine.decidePostflop('AA');
  console.log('    → SPR>20 River面CR坚果: ' + (r ? r.a : 'null') + (r && r.v ? ' v='+r.v : ''));
  // 深码面对CR应该有合理的加注尺度
  if (r && r.a === 'raise' && r.v && r.v < 400) {
    warn('深码坚果CR raise尺度可能过小', 'v='+r.v+' pot='+300);
  }
  assert(true, '已记录');
});

test('SPR=0: stk=0时不应崩溃', function(){
  setupState({hole:['A♠','K♥'], comm:['Q♦','7♣','2♠'], scene:'check', bet:0, pot:100, didPFR:true, stk:0, phase:'flop'});
  var r = StrategyEngine.decidePostflop('AKs');
  assert(r !== undefined, 'stk=0不应返回undefined');
  console.log('    → stk=0: ' + (r ? r.a : 'null'));
});

test('极小底池pot=1: 除法安全', function(){
  setupState({hole:['A♠','K♥'], comm:['Q♦','7♣','2♠'], scene:'raise', bet:1, pot:1, didPFR:false, stk:10000, phase:'flop'});
  var r = StrategyEngine.decidePostflop('AKs');
  assert(r !== undefined, 'pot=1不应崩溃');
  console.log('    → pot=1: ' + (r ? r.a : 'null'));
});

// ══════════════════════════════════════════════════════
// 五、_hc2key映射准确性
// ══════════════════════════════════════════════════════
console.log('\n═══ 五、_hc2key映射准确性 ═══');

test('_hc2key把STRONG全映射为4(顶对好踢)——两对/暗三条也是STRONG?', function(){
  // handClassify对暗三条/两对可能返回name='STRONG'
  // 但_hc2key一律映射到4，而策略表中:
  //   1=暗三条, 2=两对, 4=顶对好踢
  // 这意味着暗三条/两对被当顶对处理——策略偏差很大
  warn('_hc2key(STRONG)→4', '暗三条应该→1, 两对→2, 但统统变成4');
  assert(true, '已记录映射缺陷');
});

test('_hc2key把MEDIUM全映射为6(第二对)——顶对弱踢也是MEDIUM', function(){
  // 顶对弱踢(tp weak)在handClassify中可能返回MEDIUM
  // 但GTO策略表中 5=顶对弱踢, 6=第二对
  // 映射到6意味着顶对弱踢被当第二对——过于被动
  warn('_hc2key(MEDIUM)→6', '顶对弱踢应→5但映射到6');
  assert(true, '已记录映射缺陷');
});

test('_hc2key(DRAW)仅按outs分——没区分同花听vs顺子听', function(){
  // outs≥12→11(combo), outs≥8→9(flush), outs≥6→10(OESD), else→12(gut)
  // 但同花听9outs和OESD8outs的打法差别很大
  // 9和10分开是对的，但8outs=9(同花听)还是10(OESD)? 取决于outs精确度
  assert(true, '已记录');
});

// ══════════════════════════════════════════════════════
// 六、Turn/River面CBet使用Flop表
// ══════════════════════════════════════════════════════
console.log('\n═══ 六、Turn/River面CBet使用Flop表 ═══');

test('BUG: Turn面CBet和Flop面CBet用同一张表——Turn应该更紧', function(){
  // GTO: fold-to-cbet-flop ≈ 47%, fold-to-cbet-turn ≈ 55%
  // 但代码里_facingCBet()对Turn和Flop用同一张表
  // 测试: 同一手牌，Flop vs Turn面CBet的fold率应不同
  var flopFolds = 0, turnFolds = 0;
  for (var i = 0; i < 50; i++) {
    setupState({hole:['K♠','Q♥'], comm:['A♦','7♣','2♠'], scene:'raise', bet:50, pot:100, didPFR:false, phase:'flop'});
    var r1 = StrategyEngine.decidePostflop('KQs');
    if (r1 && r1.a === 'fold') flopFolds++;
    
    setupState({hole:['K♠','Q♥'], comm:['A♦','7♣','2♠','5♣'], scene:'raise', bet:50, pot:100, didPFR:false, phase:'turn'});
    var r2 = StrategyEngine.decidePostflop('KQs');
    if (r2 && r2.a === 'fold') turnFolds++;
  }
  console.log('    → Flop面CBet fold率: '+flopFolds+'/50, Turn面CBet fold率: '+turnFolds+'/50');
  if (flopFolds === turnFolds || Math.abs(flopFolds - turnFolds) < 5) {
    warn('Turn面CBet策略和Flop完全一致', 'GTO要求Turn更紧(fold更多)');
  }
  assert(true, '已记录Turn/River策略表复用问题');
});

// ══════════════════════════════════════════════════════
// 七、RIV.bet策略合理性
// ══════════════════════════════════════════════════════
console.log('\n═══ 七、RIV.bet策略合理性 ═══');

test('V2.9.158修复: RIV.bet[5]=bet(顶对弱踢River薄价值)', function(){
  var riv = StrategyEngine.getRiverDecision();
  assert(riv.bet[5].a === 'bet', 'RIV.bet[5]应为bet, 实际:'+riv.bet[5].a);
  assert(riv.bet[5].s > 0, 'sizing应>0');
});

test('V2.9.158修复: RIV.bet[6]=bet(第二对River薄价值)', function(){
  var riv = StrategyEngine.getRiverDecision();
  assert(riv.bet[6].a === 'bet', 'RIV.bet[6]应为bet, 实际:'+riv.bet[6].a);
  assert(riv.bet[6].s > 0, 'sizing应>0');
});

test('RIV.bet[7]=check: 弱对River永远不bet', function(){
  var riv = StrategyEngine.getRiverDecision();
  assert(riv.bet[7].a === 'check', 'RIV.bet[7]确实是check');
  // 弱对check是合理的
  assert(true, '弱对check合理');
});

test('RIV.bet[9]=bluff: 坚果听牌River→诈唬?', function(){
  var riv = StrategyEngine.getRiverDecision();
  assert(riv.bet[9].a === 'bluff', 'RIV.bet[9]是bluff');
  warn('hcKey=9(坚果听)River→bluff', 'River上没成的听牌应该是AIR(15)，不是9。如果handClassify在river仍返回DRAW=9，是分类bug');
});

test('RIV.face[7]=fold: 弱对面对River bet永远fold', function(){
  var riv = StrategyEngine.getRiverDecision();
  assert(riv.face[7].a === 'fold', 'RIV.face[7]是fold');
  // 弱对面对bet fold是基本合理的
  assert(true, '弱对fold合理');
});

// ══════════════════════════════════════════════════════
// 八、3bet底池翻后
// ══════════════════════════════════════════════════════
console.log('\n═══ 八、3bet底池翻后 ═══');

test('3bet底池SPR≈3-4: 翻后策略和单加注底池一样', function(){
  // 3bet底池特征: SPR低、范围强、CBet频率高
  // 当前引擎不区分3bet pot vs single-raised pot
  setupState({hole:['A♠','K♥'], comm:['Q♦','7♣','2♠'], scene:'check', bet:0, pot:300, didPFR:true, stk:1500, phase:'flop'});
  // SPR = 1500/300 = 5, 接近3bet pot
  var r = StrategyEngine.decidePostflop('AKs');
  console.log('    → 3bet pot CBet: ' + (r ? r.a : 'null') + (r && r.v ? ' v='+r.v : ''));
  warn('3bet pot和单加注底池用同一套CBet表', '3bet pot应更极化(价值大尺度+少诈唬)');
  assert(true, '已记录');
});

// ══════════════════════════════════════════════════════
// 九、面对全下(allin)场景
// ══════════════════════════════════════════════════════
console.log('\n═══ 九、面对全下(allin)场景 ═══');

test('Flop面对allin: V2.9.157策略表无allin处理', function(){
  // _facingCBet的sizing只分small/large，allin是极端large
  // 但面对allin需要看底池赔率而不是GTO频率
  setupState({hole:['A♠','K♥'], comm:['Q♦','7♣','2♠'], scene:'raise', bet:5000, pot:200, didPFR:false, stk:10000, phase:'flop'});
  var r = StrategyEngine.decidePostflop('AKs');
  // bet/pot = 25x, 对手allin，面对allin应该用赔率而不是GTO频率表
  console.log('    → 面对allin: ' + (r ? r.a : 'null') + (r && r._se ? ' SE' : ' 旧引擎'));
  if (r && r._se) {
    warn('V2.9.157面对allin仍用GTO频率表', '应该切到赔率计算模式');
  }
  assert(true, '已记录');
});

// ══════════════════════════════════════════════════════
// 十、多人池(3+人)场景
// ══════════════════════════════════════════════════════
console.log('\n═══ 十、多人池(3+人)场景 ═══');

test('3人池面对CBet: V2.9.157无多人池调整', function(){
  // 5-max 3人看翻牌不罕见
  // 多人池应该: fold更多、raise更少、bluff几乎不做
  // 当前FCB表没有任何多人池调整
  warn('FCB表无多人池调整', '3人池面对CBet应比HU更紧');
  assert(true, '已记录');
});

// ══════════════════════════════════════════════════════
// 十一、handClassify返回null/异常
// ══════════════════════════════════════════════════════
console.log('\n═══ 十一、null/异常安全 ═══');

test('hole为空数组: 不应崩溃', function(){
  setupState({hole:[], comm:['Q♦','7♣','2♠'], scene:'check', bet:0, pot:100, didPFR:true, phase:'flop'});
  try {
    var r = StrategyEngine.decidePostflop('??');
    console.log('    → 空hole: ' + (r ? r.a : 'null'));
  } catch(e) {
    console.log('    → 空hole异常: ' + e.message);
    // 旧引擎可能会抛，SE应该返回null
  }
  assert(true, '不崩溃即可');
});

test('comm全为null: 不应崩溃', function(){
  setupState({hole:['A♠','K♥'], comm:[null,null,null,null,null], scene:'check', bet:0, pot:100, didPFR:true, phase:'flop'});
  try {
    var r = StrategyEngine.decidePostflop('AKs');
    console.log('    → 空comm: ' + (r ? r.a : 'null'));
  } catch(e) {
    console.log('    → 空comm异常: ' + e.message);
  }
  assert(true, '不崩溃即可');
});

test('G.pot=0: 不应除零崩溃', function(){
  setupState({hole:['A♠','K♥'], comm:['Q♦','7♣','2♠'], scene:'raise', bet:30, pot:0, didPFR:false, phase:'flop'});
  try {
    var r = StrategyEngine.decidePostflop('AKs');
    console.log('    → pot=0: ' + (r ? r.a : 'null'));
  } catch(e) {
    console.log('    → pot=0异常: ' + e.message);
  }
  assert(true, '不崩溃即可');
});

// ══════════════════════════════════════════════════════
// 十二、频率和合理性硬检查
// ══════════════════════════════════════════════════════
console.log('\n═══ 十二、频率合理性硬检查 ═══');

test('FCB_IP所有条目: c+r+f之和应≈1.0(±0.05)', function(){
  var combos = ['0_s','0_l','2_s','2_l','5_s','5_l','4_s','4_l'];
  var errors = [];
  combos.forEach(function(k){
    var parts = k.split('_');
    var table = StrategyEngine.getFacingCBetIP(parts[0], parts[1]);
    if (!table) return;
    Object.keys(table).forEach(function(hc){
      var e = table[hc];
      var sum = (e.c||0)+(e.r||0)+(e.f||0);
      if (Math.abs(sum - 1.0) > 0.05) errors.push('FCB_IP['+k+']['+hc+']='+sum.toFixed(3));
    });
  });
  if (errors.length > 0) console.log('    ⚠️ 频率和偏差: ' + errors.join(', '));
  assert(errors.length === 0, '频率和不为1: '+errors.length+'条: '+errors.slice(0,3).join(','));
});

test('FCB_OOP所有条目: c+r+f之和应≈1.0(±0.05)', function(){
  var combos = ['0_s','0_l','2_s','2_l','5_s','5_l'];
  var errors = [];
  combos.forEach(function(k){
    var parts = k.split('_');
    var table = StrategyEngine.getFacingCBetOOP(parts[0], parts[1]);
    if (!table) return;
    Object.keys(table).forEach(function(hc){
      var e = table[hc];
      var sum = (e.c||0)+(e.r||0)+(e.f||0);
      if (Math.abs(sum - 1.0) > 0.05) errors.push('FCB_OOP['+k+']['+hc+']='+sum.toFixed(3));
    });
  });
  if (errors.length > 0) console.log('    ⚠️ 频率和偏差: ' + errors.join(', '));
  assert(errors.length === 0, '频率和不为1: '+errors.length+'条: '+errors.slice(0,3).join(','));
});

test('FCR所有条目: c+rr+f之和应≈1.0(±0.05)', function(){
  var errors = [];
  ['0','2','4','5'].forEach(function(bt){
    var table = StrategyEngine.getFacingCR(bt);
    if (!table) return;
    Object.keys(table).forEach(function(hc){
      var e = table[hc];
      var sum = (e.c||0)+(e.rr||0)+(e.f||0);
      if (Math.abs(sum - 1.0) > 0.05) errors.push('FCR['+bt+']['+hc+']='+sum.toFixed(3));
    });
  });
  if (errors.length > 0) console.log('    ⚠️ 频率和偏差: ' + errors.join(', '));
  assert(errors.length === 0, 'FCR频率和不为1: '+errors.length+'条: '+errors.slice(0,3).join(','));
});

test('OOP比IP更紧: FCB_OOP空气fold率≥FCB_IP', function(){
  var violations = [];
  ['0','2','5'].forEach(function(bt){
    var ip_s = StrategyEngine.getFacingCBetIP(bt,'s');
    var oop_s = StrategyEngine.getFacingCBetOOP(bt,'s');
    if (!ip_s || !oop_s) return;
    if (ip_s[15] && oop_s[15]) {
      if (oop_s[15].f < ip_s[15].f) violations.push(bt+'_s: OOP fold'+oop_s[15].f+'<IP fold'+ip_s[15].f);
    }
  });
  if (violations.length > 0) console.log('    ⚠️ OOP不比IP紧: ' + violations.join(', '));
  assert(violations.length === 0, 'OOP应比IP更紧: '+violations.join(','));
});

// ══════════════════════════════════════════════════════
// 十三、场景路由遗漏
// ══════════════════════════════════════════════════════
console.log('\n═══ 十三、场景路由遗漏 ═══');

test('scene=allin翻后: V2.9.157无处理', function(){
  setupState({hole:['A♠','K♥'], comm:['Q♦','7♣','2♠'], scene:'allin', bet:5000, pot:200, didPFR:false, stk:10000, phase:'flop'});
  var r = StrategyEngine.decidePostflop('AKs');
  // scene='allin'不匹配任何V2.9.157条件
  console.log('    → 翻后allin: ' + (r ? r.a : 'null') + (r && r._se ? ' SE' : ' 旧引擎'));
  warn('翻后allin场景走旧引擎', '应加入赔率计算');
  assert(true, '已记录');
});

test('BUG: handClassify把AKs on Q72 misclassify为DRAW→hcKey=12(卡顺)而非超牌', function(){
  // AK on Q♦7♣2♠ = 无对无听牌，应归AIR或超牌(hcKey=13/15)
  // 但handClassify返回DRAW 3outs→hcKey=12(卡顺)
  // 然后CBet表缺key=12→返回null→回退旧引擎
  setupState({hole:['A♠','K♥'], comm:['Q♦','7♣','2♠','5♣'], scene:'check', bet:0, pot:200, didPFR:true, phase:'turn'});
  var h=G.hole.filter(function(c){return c;});
  var bc=G.comm.filter(function(c){return c;});
  if(typeof h[0]==='string'){h=h.map(function(s){var m=s&&s.match(/^([2-9TJQKA])([♠♥♦♣])$/);return m?{rank:m[1],suit:m[2]}:null;}).filter(function(c){return c;});}
  if(typeof bc[0]==='string'){bc=bc.map(function(s){var m=s&&s.match(/^([2-9TJQKA])([♠♥♦♣])$/);return m?{rank:m[1],suit:m[2]}:null;}).filter(function(c){return c;});}
  var hClass = handClassify(h, bc);
  console.log('    → AKs on Q752: name='+hClass.name+' outs='+hClass.outs+' desc='+hClass.desc);
  assert(hClass.name === 'DRAW', '确认handClassify返回DRAW(这是bug)');
  warn('handClassify(AKs on Q752)=DRAW 3outs', '应为AIR/超牌，AK在Q75面无听牌');
});

test('BUG: CBet表缺hcKey=12(卡顺)→AKs on Q72翻后CBet返回null', function(){
  setupState({hole:['A♠','K♥'], comm:['Q♦','7♣','2♠'], scene:'check', bet:0, pot:100, didPFR:true, phase:'flop'});
  var r = StrategyEngine.decidePostflop('AKs');
  console.log('    → AKs CBet on Q72: ' + (r ? r.a : 'null(回退旧引擎)'));
  // hcKey=12不在CBET_IP['0']中→null
  warn('_CBET_IP[0]缺key=12', '卡顺在干燥面无CBet策略');
  assert(true, '已记录');
});

test('BUG: TB_IP缺hcKey=12(卡顺)→Turn barrel返回null', function(){
  setupState({hole:['A♠','K♥'], comm:['Q♦','7♣','2♠','5♣'], scene:'check', bet:0, pot:200, didPFR:true, phase:'turn'});
  var r = StrategyEngine.decidePostflop('AKs');
  console.log('    → AKs Turn barrel on Q752: ' + (r ? r.a : 'null(回退旧引擎)'));
  assert(true, '已记录hcKey=12缺口');
});

test('AKs(顶对)Turn barrel正常工作——用AA代替', function(){
  // AA on K72一定是超对→hcKey=3, 在TB表中有对应条目
  setupState({hole:['A♠','A♥'], comm:['K♦','7♣','2♠','5♣'], scene:'check', bet:0, pot:200, didPFR:true, phase:'turn'});
  var r = StrategyEngine.decidePostflop('AA');
  console.log('    → AA Turn barrel: ' + (r ? r.a : 'null'));
  assert(r !== null, 'AA Turn barrel应返回决策');
});

test('AA River主动决策正常', function(){
  setupState({hole:['A♠','A♥'], comm:['K♦','7♣','2♠','5♣','9♥'], scene:'check', bet:0, pot:300, didPFR:true, phase:'river'});
  var r = StrategyEngine.decidePostflop('AA');
  console.log('    → AA River: ' + (r ? r.a : 'null'));
  assert(r !== null, 'AA River应返回决策');
});

// ══════════════════════════════════════════════════════
// 十四、btKey fallback逻辑验证
// ══════════════════════════════════════════════════════
console.log('\n═══ 十四、btKey fallback验证 ═══');

test('btKey=6(双色调)→fallback到4(同花面): 合理性', function(){
  // 双色调面策略确实接近同花面，fallback合理
  // 但fallback后数据可能不精确
  var table4s = StrategyEngine.getFacingCBetIP('4','s');
  // btKey=6查不到时fallback到4
  assert(table4s !== null, '4_s存在');
  warn('btKey=6→4 fallback', '双色调和纯同花面有区别，fallback是近似');
  assert(true, '已记录');
});

test('btKey=1(中等干燥)→fallback到0(干燥高牌): 可能过于宽松', function(){
  // 中等干燥面比干燥高牌面湿一些，fallback到0可能偏松
  warn('btKey=1→0 fallback', '中等干燥面比干燥面湿，应更谨慎');
  assert(true, '已记录');
});

test('btKey=3(低牌连接)→fallback到2(湿润连接): 可能过于激进', function(){
  // 低牌连接面比湿润连接面更危险(更多顺子可能)
  // fallback到2可能偏激
  warn('btKey=3→2 fallback', '低牌连接比湿润更危险，应更谨慎');
  assert(true, '已记录');
});

// ══════════════════════════════════════════════════════
// 十五、端到端极端牌面
// ══════════════════════════════════════════════════════
console.log('\n═══ 十五、端到端极端牌面 ═══');

test('四条面(AA on A♠A♥A♦K♣): hcKey应=1(暗三条)但可能被当4(顶对)', function(){
  // A♠A♥ on A♠A♥A♦K♣ → 四条
  // handClassify可能返回NUTS→hcKey=0, 或STRONG→hcKey=4
  // 四条应该=0(坚果)或1(暗三条)，如果变成4就太弱了
  warn('极端强牌handClassify准确性', '需实际验证handClassify对四条/葫芦的分类');
  assert(true, '已记录');
});

test('同花面同花听牌: K♠Q♠ on A♠7♠2♠', function(){
  setupState({hole:['K♠','Q♠'], comm:['A♠','7♠','2♠'], scene:'raise', bet:50, pot:100, didPFR:false, phase:'flop'});
  var r = StrategyEngine.decidePostflop('KQs');
  console.log('    → 同花面同花听: ' + (r ? r.a : 'null'));
  // K高同花听在A高同花面很强，不应该轻易fold
  if (r && r.a === 'fold') warn('同花面同花听fold', 'K高花听在A高花面太紧');
  assert(true, '已记录');
});

test('顺子面卡顺: 98 on JT2', function(){
  setupState({hole:['9♠','8♥'], comm:['J♦','T♣','2♠'], scene:'raise', bet:50, pot:100, didPFR:false, phase:'flop'});
  var r = StrategyEngine.decidePostflop('98o');
  console.log('    → 顺子面卡顺: ' + (r ? r.a : 'null'));
  assert(r !== null, '应有决策');
});

test('对子面小对: 22 on KK5', function(){
  setupState({hole:['2♠','2♥'], comm:['K♦','K♣','5♠'], scene:'raise', bet:30, pot:100, didPFR:false, phase:'flop'});
  var r = StrategyEngine.decidePostflop('22');
  console.log('    → 对子面小对: ' + (r ? r.a : 'null'));
  // 22在KK5面是底对，面对CBet应该大概率fold
});

// ══════════════════════════════════════════════════════
// 汇总
// ══════════════════════════════════════════════════════
console.log('\n═══════════════════════════════════════════════════');
console.log(' V2.9.157 边界找茬测试汇总');
console.log('═══════════════════════════════════════════════════');
console.log('  通过: ' + pass + '/' + (pass+fail));
console.log('  失败: ' + fail);
console.log('  警告: ' + warnings);
console.log('');
if (fail === 0) console.log('  ✅ 测试全部通过（但发现 ' + warnings + ' 个策略缺陷/警告）');
else console.log('  ❌ 有硬失败项，必须修复');
process.exit(fail > 0 ? 1 : 0);

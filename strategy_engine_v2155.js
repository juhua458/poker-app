  // V2.9.155: StrategyEngine - GTO频率表 + 翻后框架 + 剥削调整
  // 独立IIFE，不修改旧代码，通过 G._seEnabled 开关切换
  var StrategyEngine=(function(){
  'use strict';

  // ====== 第一部分: GTO翻前频率表 (5-Max专用) ======
  // 频率: 1=必须, 0.8=高频, 0.5=混合, 0.3=低频, 0=不开
  // 数据来源: GTO Gecko + RiverOdds 交叉验证

  var _RFI={
    UTG1:{AA:1,KK:1,QQ:1,JJ:1,TT:1,"99":.9,"88":.8,"77":.7,"66":.6,"55":.5,"44":.4,"33":.3,"22":.3,AKs:1,AQs:1,AJs:1,ATs:.9,A9s:.7,A8s:.5,A7s:.4,A6s:.3,A5s:.6,A4s:.4,A3s:.3,A2s:.3,KQs:1,KJs:1,KTs:.7,K9s:.5,K8s:.3,K7s:.2,K6s:.1,K5s:.1,QJs:.9,QTs:.6,Q9s:.4,Q8s:.2,JTs:.9,J9s:.5,J8s:.2,T9s:.9,T8s:.3,"98s":.5,"87s":.5,"76s":.4,"65s":.3,"54s":.2,AKo:1,AQo:1,AJo:.7,ATo:.4,A9o:.2,KQo:.9,KJo:.6,KTo:.3,QJo:.5,JTo:.2},
    MP:{AA:1,KK:1,QQ:1,JJ:1,TT:1,"99":1,"88":.9,"77":.8,"66":.7,"55":.6,"44":.5,"33":.4,"22":.4,AKs:1,AQs:1,AJs:1,ATs:1,A9s:.9,A8s:.7,A7s:.5,A6s:.4,A5s:.8,A4s:.6,A3s:.4,A2s:.4,KQs:1,KJs:1,KTs:.9,K9s:.7,K8s:.5,K7s:.3,K6s:.2,K5s:.2,K4s:.1,QJs:1,QTs:.8,Q9s:.6,Q8s:.4,Q7s:.2,Q6s:.1,JTs:1,J9s:.7,J8s:.4,J7s:.2,T9s:1,T8s:.5,T7s:.3,"98s":.7,"97s":.3,"87s":.7,"86s":.3,"76s":.6,"75s":.3,"65s":.4,"54s":.4,"43s":.1,AKo:1,AQo:1,AJo:.9,ATo:.7,A9o:.5,A8o:.3,A7o:.1,KQo:1,KJo:.8,KTo:.6,K9o:.3,K8o:.1,QJo:.7,QTo:.5,Q9o:.2,JTo:.5,J9o:.2,T9o:.3},
    CO:{AA:1,KK:1,QQ:1,JJ:1,TT:1,"99":1,"88":1,"77":.9,"66":.8,"55":.7,"44":.6,"33":.5,"22":.5,AKs:1,AQs:1,AJs:1,ATs:1,A9s:1,A8s:.9,A7s:.8,A6s:.7,A5s:1,A4s:.8,A3s:.7,A2s:.7,KQs:1,KJs:1,KTs:1,K9s:.9,K8s:.7,K7s:.5,K6s:.4,K5s:.3,K4s:.2,K3s:.2,K2s:.1,QJs:1,QTs:.9,Q9s:.7,Q8s:.5,Q7s:.3,Q6s:.2,Q5s:.1,JTs:1,J9s:.8,J8s:.5,J7s:.3,J6s:.2,T9s:1,T8s:.7,T7s:.4,T6s:.2,"98s":.8,"97s":.5,"96s":.2,"87s":.8,"86s":.5,"76s":.7,"75s":.4,"65s":.6,"64s":.2,"54s":.5,"53s":.2,"43s":.3,AKo:1,AQo:1,AJo:1,ATo:.9,A9o:.7,A8o:.5,A7o:.3,A6o:.1,A3o:.1,A2o:.1,KQo:1,KJo:.9,KTo:.8,K9o:.5,K8o:.2,QJo:.8,QTo:.7,Q9o:.3,Q8o:.1,JTo:.7,J9o:.3,T9o:.4,"98o":.1},
    BTN:{AA:1,KK:1,QQ:1,JJ:1,TT:1,"99":1,"88":1,"77":1,"66":.9,"55":.8,"44":.7,"33":.6,"22":.6,AKs:1,AQs:1,AJs:1,ATs:1,A9s:1,A8s:1,A7s:1,A6s:.9,A5s:1,A4s:1,A3s:.9,A2s:.9,KQs:1,KJs:1,KTs:1,K9s:1,K8s:.9,K7s:.7,K6s:.5,K5s:.4,K4s:.3,K3s:.2,K2s:.2,QJs:1,QTs:1,Q9s:.9,Q8s:.6,Q7s:.4,Q6s:.3,Q5s:.2,Q4s:.1,Q3s:.1,JTs:1,J9s:.9,J8s:.7,J7s:.4,J6s:.3,J5s:.2,J4s:.1,T9s:1,T8s:.8,T7s:.5,T6s:.3,T5s:.2,T4s:.1,"98s":.9,"97s":.6,"96s":.3,"95s":.2,"87s":.9,"86s":.6,"85s":.3,"76s":.8,"75s":.5,"74s":.2,"65s":.7,"64s":.3,"54s":.6,"53s":.2,"43s":.3,AKo:1,AQo:1,AJo:1,ATo:1,A9o:.9,A8o:.7,A7o:.4,A6o:.2,A5o:.1,A3o:.2,A2o:.2,KQo:1,KJo:1,KTo:.9,K9o:.6,K8o:.3,K7o:.1,QJo:.9,QTo:.8,Q9o:.4,Q8o:.2,JTo:.8,J9o:.4,J8o:.2,T9o:.5,T8o:.2,"98o":.2},
    SB:{AA:1,KK:1,QQ:1,JJ:1,TT:1,"99":.9,"88":.8,"77":.7,"66":.5,"55":.4,"44":.3,"33":.2,"22":.2,AKs:1,AQs:1,AJs:1,ATs:1,A9s:.9,A8s:.7,A7s:.5,A6s:.4,A5s:1,A4s:.8,A3s:.6,A2s:.5,KQs:1,KJs:1,KTs:.9,K9s:.7,K8s:.5,K7s:.3,K6s:.2,K5s:.2,K4s:.1,QJs:.9,QTs:.7,Q9s:.5,Q8s:.3,JTs:.9,J9s:.6,J8s:.3,T9s:.8,T8s:.5,"98s":.7,"97s":.3,"87s":.6,"86s":.3,"76s":.5,"75s":.2,"65s":.4,"54s":.3,"43s":.1,AKo:1,AQo:1,AJo:.9,ATo:.7,A9o:.4,A8o:.2,KQo:.9,KJo:.7,KTo:.5,K9o:.2,QJo:.5,QTo:.3,JTo:.3}
  };

  // 3bet表: vs_X.from_Y = {hand: {a:'3b'|'c'|'f'|'4b', f:freq}}
  var _3B={
    vs_UTG1:{
      from_MP:{AA:{a:'3b',f:1},KK:{a:'3b',f:1},QQ:{a:'3b',f:.7},JJ:{a:'3b',f:.3},AKs:{a:'3b',f:.7},AKo:{a:'3b',f:.6},AQs:{a:'3b',f:.3},A5s:{a:'3b',f:.3},A4s:{a:'3b',f:.2},TT:{a:'c',f:.7},"99":{a:'c',f:.6},"88":{a:'c',f:.5},AQo:{a:'c',f:.5},AJs:{a:'c',f:.5},KQs:{a:'c',f:.4},QJs:{a:'c',f:.3},JTs:{a:'c',f:.3}},
      from_CO:{AA:{a:'3b',f:1},KK:{a:'3b',f:1},QQ:{a:'3b',f:.7},JJ:{a:'3b',f:.4},AKs:{a:'3b',f:.7},AKo:{a:'3b',f:.7},AQs:{a:'3b',f:.4},A5s:{a:'3b',f:.4},A4s:{a:'3b',f:.3},KQs:{a:'3b',f:.2},TT:{a:'c',f:.7},"99":{a:'c',f:.6},"88":{a:'c',f:.5},AQo:{a:'c',f:.5},AJs:{a:'c',f:.5},KQs:{a:'c',f:.5},QJs:{a:'c',f:.4},JTs:{a:'c',f:.4}},
      from_BTN:{AA:{a:'3b',f:1},KK:{a:'3b',f:1},QQ:{a:'3b',f:.7},JJ:{a:'3b',f:.4},AKs:{a:'3b',f:.7},AKo:{a:'3b',f:.6},AQs:{a:'3b',f:.4},A5s:{a:'3b',f:.4},A4s:{a:'3b',f:.3},KQs:{a:'3b',f:.2},TT:{a:'c',f:.8},"99":{a:'c',f:.7},"88":{a:'c',f:.6},"77":{a:'c',f:.5},AQo:{a:'c',f:.5},AJs:{a:'c',f:.5},ATs:{a:'c',f:.4},KQs:{a:'c',f:.5},KJs:{a:'c',f:.4},QJs:{a:'c',f:.4},JTs:{a:'c',f:.4},T9s:{a:'c',f:.3}},
      from_SB:{AA:{a:'3b',f:1},KK:{a:'3b',f:1},QQ:{a:'3b',f:.8},AKs:{a:'3b',f:.8},AKo:{a:'3b',f:.7},A5s:{a:'3b',f:.3}},
      from_BB:{AA:{a:'3b',f:1},KK:{a:'3b',f:1},QQ:{a:'3b',f:.8},AKs:{a:'3b',f:.7},AKo:{a:'3b',f:.6},A5s:{a:'3b',f:.3},JJ:{a:'c',f:.8},TT:{a:'c',f:.7},"99":{a:'c',f:.6},"88":{a:'c',f:.5},AQs:{a:'c',f:.6},AQo:{a:'c',f:.5},AJs:{a:'c',f:.4},KQs:{a:'c',f:.4},QJs:{a:'c',f:.3},JTs:{a:'c',f:.3}}
    },
    vs_CO:{
      from_BTN:{AA:{a:'3b',f:1},KK:{a:'3b',f:1},QQ:{a:'3b',f:.9},JJ:{a:'3b',f:.7},TT:{a:'3b',f:.5},AKs:{a:'3b',f:.8},AKo:{a:'3b',f:.8},AQs:{a:'3b',f:.6},AQo:{a:'3b',f:.5},AJs:{a:'3b',f:.3},A5s:{a:'3b',f:.5},A4s:{a:'3b',f:.5},A3s:{a:'3b',f:.3},A2s:{a:'3b',f:.3},KQs:{a:'3b',f:.2},QJs:{a:'3b',f:.1},"99":{a:'c',f:.9},"88":{a:'c',f:.8},"77":{a:'c',f:.7},"66":{a:'c',f:.5},"55":{a:'c',f:.4},"44":{a:'c',f:.3},"33":{a:'c',f:.2},"22":{a:'c',f:.2},ATs:{a:'c',f:.8},A9s:{a:'c',f:.7},A8s:{a:'c',f:.6},A7s:{a:'c',f:.5},A6s:{a:'c',f:.4},KJs:{a:'c',f:.7},KTs:{a:'c',f:.6},K9s:{a:'c',f:.5},JTs:{a:'c',f:.7},T9s:{a:'c',f:.7},"98s":{a:'c',f:.6},"87s":{a:'c',f:.5},"76s":{a:'c',f:.4},AJo:{a:'c',f:.5},KQo:{a:'c',f:.4}},
      from_SB:{AA:{a:'3b',f:1},KK:{a:'3b',f:1},QQ:{a:'3b',f:.9},JJ:{a:'3b',f:.8},TT:{a:'3b',f:.6},AKs:{a:'3b',f:.9},AKo:{a:'3b',f:.9},AQs:{a:'3b',f:.7},AQo:{a:'3b',f:.5},AJs:{a:'3b',f:.4},ATs:{a:'3b',f:.3},A5s:{a:'3b',f:.6},A4s:{a:'3b',f:.5},A3s:{a:'3b',f:.4},A2s:{a:'3b',f:.4},KQs:{a:'3b',f:.3},KJs:{a:'3b',f:.2},QJs:{a:'3b',f:.2},KQo:{a:'3b',f:.3},"99":{a:'c',f:.3},"88":{a:'c',f:.2},"77":{a:'c',f:.1}},
      from_BB:{AA:{a:'3b',f:1},KK:{a:'3b',f:1},QQ:{a:'3b',f:.9},JJ:{a:'3b',f:.7},TT:{a:'3b',f:.5},"99":{a:'3b',f:.3},AKs:{a:'3b',f:.8},AKo:{a:'3b',f:.7},AQs:{a:'3b',f:.5},AQo:{a:'3b',f:.3},A5s:{a:'3b',f:.5},A4s:{a:'3b',f:.5},A3s:{a:'3b',f:.4},A2s:{a:'3b',f:.3},KQs:{a:'3b',f:.2},"98s":{a:'3b',f:.2},"87s":{a:'3b',f:.2},"88":{a:'c',f:.8},"77":{a:'c',f:.7},"66":{a:'c',f:.6},"55":{a:'c',f:.5},"44":{a:'c',f:.4},"33":{a:'c',f:.3},"22":{a:'c',f:.3},AJs:{a:'c',f:.6},ATs:{a:'c',f:.7},A9s:{a:'c',f:.6},A8s:{a:'c',f:.5},A7s:{a:'c',f:.4},A6s:{a:'c',f:.3},KJs:{a:'c',f:.6},KTs:{a:'c',f:.5},K9s:{a:'c',f:.4},K8s:{a:'c',f:.3},QJs:{a:'c',f:.5},QTs:{a:'c',f:.4},Q9s:{a:'c',f:.3},JTs:{a:'c',f:.6},J9s:{a:'c',f:.4},T9s:{a:'c',f:.6},T8s:{a:'c',f:.4},"98s":{a:'c',f:.5},"87s":{a:'c',f:.4},"76s":{a:'c',f:.3},"65s":{a:'c',f:.2},"54s":{a:'c',f:.2},AJo:{a:'c',f:.3},KQo:{a:'c',f:.3},KJo:{a:'c',f:.2}}
    },
    vs_BTN:{
      from_SB:{AA:{a:'3b',f:1},KK:{a:'3b',f:1},QQ:{a:'3b',f:.9},JJ:{a:'3b',f:.8},TT:{a:'3b',f:.6},AKs:{a:'3b',f:.9},AKo:{a:'3b',f:.9},AQs:{a:'3b',f:.7},AQo:{a:'3b',f:.5},AJs:{a:'3b',f:.4},ATs:{a:'3b',f:.3},AJo:{a:'3b',f:.2},A5s:{a:'3b',f:.6},A4s:{a:'3b',f:.5},A3s:{a:'3b',f:.4},A2s:{a:'3b',f:.4},KQs:{a:'3b',f:.3},KJs:{a:'3b',f:.2},QJs:{a:'3b',f:.2},KQo:{a:'3b',f:.3},"99":{a:'c',f:.3},"88":{a:'c',f:.2},"77":{a:'c',f:.1}},
      from_BB:{AA:{a:'3b',f:1},KK:{a:'3b',f:1},QQ:{a:'3b',f:.9},JJ:{a:'3b',f:.7},TT:{a:'3b',f:.5},"99":{a:'3b',f:.3},AKs:{a:'3b',f:.8},AKo:{a:'3b',f:.7},AQs:{a:'3b',f:.5},AQo:{a:'3b',f:.3},AJs:{a:'3b',f:.2},A5s:{a:'3b',f:.5},A4s:{a:'3b',f:.5},A3s:{a:'3b',f:.4},A2s:{a:'3b',f:.3},KQs:{a:'3b',f:.2},K5s:{a:'3b',f:.2},K4s:{a:'3b',f:.2},"98s":{a:'3b',f:.3},"87s":{a:'3b',f:.2},"88":{a:'c',f:.7},"77":{a:'c',f:.7},"66":{a:'c',f:.6},"55":{a:'c',f:.5},"44":{a:'c',f:.4},"33":{a:'c',f:.3},"22":{a:'c',f:.3},ATs:{a:'c',f:.7},A9s:{a:'c',f:.6},A8s:{a:'c',f:.5},A7s:{a:'c',f:.4},A6s:{a:'c',f:.3},KJs:{a:'c',f:.6},KTs:{a:'c',f:.5},K9s:{a:'c',f:.4},K8s:{a:'c',f:.3},QJs:{a:'c',f:.5},QTs:{a:'c',f:.4},Q9s:{a:'c',f:.3},JTs:{a:'c',f:.6},J9s:{a:'c',f:.4},J8s:{a:'c',f:.3},T9s:{a:'c',f:.6},T8s:{a:'c',f:.4},"98s":{a:'c',f:.5},"87s":{a:'c',f:.4},"76s":{a:'c',f:.3},"65s":{a:'c',f:.3},"54s":{a:'c',f:.2},AJo:{a:'c',f:.3},KQo:{a:'c',f:.4},KJo:{a:'c',f:.3},QJo:{a:'c',f:.2}}
    }
  };

  // 面对3bet的4bet/call/fold表
  var _F3B={
    UTG1:{AA:{a:'4b',f:1},KK:{a:'4b',f:1},QQ:{a:'4b',f:.6,s:{a:'c',f:.4}},AKs:{a:'4b',f:.5,s:{a:'c',f:.5}},AKo:{a:'4b',f:.5,s:{a:'c',f:.5}},JJ:{a:'c',f:.8},TT:{a:'c',f:.7},"99":{a:'c',f:.5},AQs:{a:'c',f:.5},AJs:{a:'c',f:.4},KQs:{a:'c',f:.3},QJs:{a:'c',f:.2},JTs:{a:'c',f:.2},A5s:{a:'4b',f:.2,s:{a:'f',f:.8}}},
    CO:{AA:{a:'4b',f:1},KK:{a:'4b',f:1},QQ:{a:'4b',f:.7,s:{a:'c',f:.3}},AKs:{a:'4b',f:.5,s:{a:'c',f:.5}},AKo:{a:'4b',f:.5,s:{a:'c',f:.5}},JJ:{a:'c',f:.8},TT:{a:'c',f:.7},"99":{a:'c',f:.5},AQs:{a:'c',f:.6},AJs:{a:'c',f:.4},KQs:{a:'c',f:.3},QJs:{a:'c',f:.3},JTs:{a:'c',f:.3},A5s:{a:'4b',f:.3,s:{a:'f',f:.7}},A4s:{a:'4b',f:.2,s:{a:'f',f:.8}},A2s:{a:'4b',f:.15,s:{a:'f',f:.85}}},
    BTN:{AA:{a:'4b',f:1},KK:{a:'4b',f:1},QQ:{a:'4b',f:.8,s:{a:'c',f:.2}},JJ:{a:'4b',f:.4,s:{a:'c',f:.6}},AKs:{a:'4b',f:.6,s:{a:'c',f:.4}},AKo:{a:'4b',f:.5,s:{a:'c',f:.5}},TT:{a:'c',f:.7},"99":{a:'c',f:.5},AQs:{a:'c',f:.6},AJs:{a:'c',f:.5},ATs:{a:'c',f:.4},KQs:{a:'c',f:.4},KJs:{a:'c',f:.3},QJs:{a:'c',f:.3},JTs:{a:'c',f:.3},T9s:{a:'c',f:.2},A5s:{a:'4b',f:.35,s:{a:'f',f:.65}},A4s:{a:'4b',f:.25,s:{a:'f',f:.75}},A3s:{a:'4b',f:.15,s:{a:'f',f:.85}},A2s:{a:'4b',f:.15,s:{a:'f',f:.85}}},
    SB:{AA:{a:'4b',f:1},KK:{a:'4b',f:1},QQ:{a:'4b',f:.8,s:{a:'c',f:.2}},JJ:{a:'4b',f:.5,s:{a:'c',f:.5}},AKs:{a:'4b',f:.6,s:{a:'c',f:.4}},AKo:{a:'4b',f:.5,s:{a:'c',f:.5}},TT:{a:'c',f:.6},"99":{a:'c',f:.4},AQs:{a:'c',f:.5},AJs:{a:'c',f:.3},KQs:{a:'c',f:.3},A5s:{a:'4b',f:.3,s:{a:'f',f:.7}},A4s:{a:'4b',f:.2,s:{a:'f',f:.8}}}
  };

  // ====== 第二部分: 翻后策略表 ======
  // 纹理分类: 0=干燥高牌, 1=中等干燥, 2=湿润连接, 3=低牌连接, 4=同花面, 5=对子面, 6=双色调
  // 手牌类别: 0=坚果, 1=暗三条, 2=两对, 3=超对, 4=顶对好踢, 5=顶对弱踢, 6=第二对, 7=弱对, 8=坚果听牌, 9=同花听牌, 10=OESD, 11=组合听牌, 12=卡顺, 13=超牌, 14=后门, 15=空气

  // IP CBet: [freq, small%] 小尺度=33%底池, 大尺度=66%+
  var _CBET_IP={
    '0':{0:[1,.9],1:[1,.9],"2":[1,.9],"3":[1,.9],"4":[.9,.9],"5":[.7,.9],"6":[.5,.9],"8":[.8,.9],"9":[.6,.8],10:[.5,.8],11:[.9,.8],13:[.4,.9],14:[.3,.9],15:[.3,.9]}, // 干燥高牌: 高频小尺度
    '2':{0:[1,.2],1:[.85,.2],"2":[.8,.2],"3":[.7,.3],"4":[.7,.3],"5":[.4,.4],"8":[.8,.2],"9":[.6,.3],10:[.5,.3],11:[.9,.2],13:[.2,.3],15:[.1,.3]}, // 湿润连接: 低频大尺度
    '3':{0:[1,.1],1:[.9,.1],"2":[.9,.1],"3":[.5,.4],"4":[.5,.4],11:[.8,.1],"9":[.5,.3],13:[.15,.5],15:[.05,.5]}, // 低牌连接: 极低频大尺度
    '4':{0:[1,.3],1:[.85,.3],"4":[.6,.4],"8":[.9,.2],"9":[.5,.4],11:[.9,.2],13:[.2,.5],15:[.1,.5]}, // 同花面
    '5':{0:[1,.8],1:[.9,.8],"3":[.9,.8],"4":[.8,.8],"9":[.5,.7],13:[.4,.8],15:[.25,.8]}  // 对子面: 高频小尺度
  };

  // OOP CBet: [freq, small%]
  var _CBET_OOP={
    '0':{0:[1,.9],1:[.95,.9],"3":[.9,.9],"4":[.8,.9],"8":[.6,.9],13:[.25,.9],15:[.2,.9]},
    '2':{0:[.9,.2],1:[.7,.2],"2":[.6,.2],11:[.5,.2],"9":[.3,.3],13:[.1,.5],15:[.05,.5]},
    '3':{0:[.9,.1],1:[.5,.1],"2":[.5,.1],11:[.4,.1],15:[.03,.5]},
    '4':{0:[.9,.3],1:[.7,.3],"4":[.5,.4],"8":[.8,.2],"9":[.4,.4],15:[.08,.5]},
    '5':{0:[1,.8],1:[.85,.8],"3":[.85,.8],"4":[.7,.8],"9":[.4,.7],15:[.2,.8]}
  };

  // Check-Raise: [freq, sizing_mult] sizing_mult=倍对手下注
  var _CR={
    '2':{1:[.7,3],"2":[.6,3],11:[.5,3.5],"9":[.35,3.5],10:[.25,3.5],15:[.03,3]},
    '3':{1:[.7,3],"2":[.65,3],11:[.5,3.5],"9":[.3,3.5],10:[.25,3.5]},
    '0':{1:[.3,2.5],"2":[.2,2.5],"9":[.15,2.5],15:[.02,2.5]},
    '4':{1:[.5,3],"8":[.5,3.5],"9":[.3,3.5],11:[.5,3.5]}
  };

  // River诈唬比: {pot倍数: 诈唬频率}
  var _RIV_BLUFF={.25:.2,.33:.25,.5:.33,.66:.4,.75:.43,1:.5,1.5:.6,"2":.67};

  // ====== 第三部分: 核心引擎 ======

  // 5-max位置映射: G.pos → RFI/3B表的位置key
  function _pos5(p){
    var m={utg1:'UTG1',utg:'UTG1',mp:'MP',mp1:'MP',hj:'MP',co:'CO',btn:'BTN',sb:'SB',bb:'BB'};
    return m[p]||'BTN';
  }

  // 纹理分类 → 数字key
  function _bt2key(bt){
    if(!bt||bt.category==='preflop')return '0';
    var w=bt.wetness||0;
    var c=bt.category||'dry';
    if(c==='paired'||c==='static')return '5'; // 对子面/极干
    if(c==='wet')return bt.hasMonotone?'4':'2'; // 湿润=同花面或连接
    if(c==='semi-wet')return bt.hasPaired?'5':'6'; // 半湿=对子或双色调
    if(w<=1)return '0'; // 干燥高牌
    return '1'; // 中等干燥
  }

  // handClassify结果 → 手牌类别key
  function _hc2key(hc){
    if(!hc)return 15;
    var n=hc.name;
    if(n==='NUTS')return 0;if(n==='STRONG')return 4; // 简化: STRONG≈顶对好踢
    if(n==='MEDIUM')return 6; // 中等≈第二对
    if(n==='DRAW')return hc.outs>=12?11:hc.outs>=8?9:hc.outs>=6?10:12; // combo/fd/oesd/gut
    if(n==='AIR')return 15;
    return 6; // 默认中等
  }

  // 对手建模偏离度
  function _oppDev(stat,actual,gto,sd){
    if(actual<0)return 0; // 无数据
    return(actual-gto)/sd;
  }

  // GTO基线(5-max)
  var _GTO={vpip:.28,pfr:.22,threeBet:.09,foldTo3Bet:.5,cbetFlop:.55,cbetTurn:.42,foldToCBetFlop:.47,foldToCBetTurn:.45,checkRaiseFlop:.1,callRiver:.5};
  var _GTO_SD={vpip:.08,pfr:.06,threeBet:.04,foldTo3Bet:.12,cbetFlop:.1,cbetTurn:.12,foldToCBetFlop:.12,foldToCBetTurn:.15,checkRaiseFlop:.06,callRiver:.15};

  // ====== 翻前决策 ======
  function decidePreflop(k){
    var p=G.pos||'btn';
    var scene=G.scene||'check';
    var stk=G.stk||100000;
    var pot=G.pot||1;
    var bet=G.bet||0;
    var spr=calcSPR();
    var eq=eQ(k);
    var profile=DRTA.getProfile();
    var oppType=G.opp||'unknown';
    var p5=_pos5(p);

    // V2.9.141投机加成(复用)
    var _specBonus=0;
    if(k.length===2&&k[0]===k[1]&&'23456'.indexOf(k[0])>=0)_specBonus+=8;
    if(k.indexOf('s')>=0&&k.length===3){var _sr=k.slice(0,2),_r1=RV[_sr[0]],_r2=RV[_sr[1]];if(_r1!==undefined&&_r2!==undefined){var _gap=Math.abs(_r1-_r2);if(_gap===1)_specBonus+=5;else if(_gap===2)_specBonus+=4;else _specBonus+=2;if(_sr[0]==='A')_specBonus+=3;}}
    if(k.indexOf('o')>=0&&k.length===3){var _sr2=k.slice(0,2),_r1b=RV[_sr2[0]],_r2b=RV[_sr2[1]];if(_r1b!==undefined&&_r2b!==undefined){if(Math.abs(_r1b-_r2b)===1&&_r2b<=9)_specBonus+=2;if(_sr2[0]==='A'&&_r2b<=9)_specBonus+=3;}}
    var _specSPRMult={ultra_short:.1,short:.3,med_short:.7,standard:1,deep:1.3}[SPRZone.getZone(spr)]||1;
    var _specPosMult={utg:.6,utg1:.7,mp:.8,mp1:.85,hj:.95,co:1.1,btn:1.2,sb:.9,bb:1}[p]||1;
    _specBonus=Math.round(_specBonus*_specSPRMult*_specPosMult);
    if(_specBonus>0)eq+=_specBonus;

    // 位置调整
    var posMod={utg:-6,utg1:-4.5,mp:-3,mp1:-1.5,hj:1,co:2,btn:6,sb:0,bb:1};
    eq+=(posMod[p]!==undefined?posMod[p]:0);
    if(BvBStrategy&&BvBStrategy.eqAdj)eq+=BvBStrategy.eqAdj();

    // Ante
    if(G.ante>0){var _ac=G.ante*(G.tt||6);var _ar=_ac/Math.max(pot,1);var _ab=Math.min(Math.round(_ar*12),5);if(_ab>0)eq+=_ab;}

    // Tilt
    var tiltInfo=TiltDetector.detectTilt(profile);

    // ====== 场景1: 开池 ======
    if(scene==='check'||scene==='call'){
      var rfi=_RFI[p5];
      if(!rfi)return null;
      var freq=rfi[k];
      if(freq===undefined||freq===0){
        // 对手剥削: vs nit/tight 扩大开池
        if(oppType==='nit'||oppType==='tight')freq=0.05;
        else return _fold(eq,'不在'+p5+'RFI范围',spr);
      }
      // 混合频率: 随机决定
      if(Math.random()>freq){
        return _fold(eq,p5+' RFI '+Math.round(freq*100)+'%→弃牌',spr);
      }
      // 开池sizing
      var sz=_preflopSizing(p,pot,scene,spr);
      var exploitR=applyExploit(eq,'raise',oppType,{bet:0,pot:pot});
      var weights=DRTA.getWeights?DRTA.getWeights(profile):{};
      return{a:'raise',v:sz,r:p5+' GTO RFI('+Math.round(freq*100)+'%) eq'+Math.round(eq),eq:exploitR.eq,c:eq>=60?'h':eq>=50?'m':'l',sizing:sz,scene:'开池',spr:spr,sprAdvice:getSPRAdvice(spr),exploit:exploitR.exploit,drta:{type:profile.type,conf:profile.confidence,weights:weights},_se:true,_seFreq:freq};
    }

    // ====== 场景2: 面对开池 ======
    if(scene==='raise'){
      var raiserRole=G._raiserRole||'unknown';
      var rP5=_pos5(raiserRole);
      var vsKey='vs_'+rP5;
      var fromKey='from_'+p5;
      var tb=_3B[vsKey];
      if(!tb)return null;
      var posTable=tb[fromKey];
      if(!posTable)return null;
      var entry=posTable[k];
      if(!entry){
        // 不在3bet/call范围 → 弃牌
        return _fold(eq,'vs '+rP5+' open不在3B范围',spr);
      }
      // 混合频率决策
      var act=entry.a;
      var f=entry.f;
      // 对手剥削调整
      f=_adjFreq3bet(act,f,oppType,raiserRole,p);
      // 随机
      if(Math.random()>f){
        // 频率未命中 → 降级动作(c→fold, 3b→c, f→f)
        if(act==='3b'){act='c';f=_get3bAlt(k,posTable,'c')||0.3;}
        else if(act==='c'){return _fold(eq,'vs '+rP5+' call频率'+Math.round(f*100)+'%未命中',spr);}
        else return _fold(eq,'vs '+rP5+' fold',spr);
      }
      var actualBet=bet||pot*2.5;
      if(act==='3b'){
        var sz3=Math.round(actualBet*3);
        sz3=Math.min(sz3,stk);
        var exploitR2=applyExploit(eq,'raise',oppType,{bet:actualBet,pot:pot,facing3bet:true});
        var weights2=DRTA.getWeights?DRTA.getWeights(profile):{};
        return{a:'raise',v:sz3,r:'GTO 3bet vs '+rP5+'('+Math.round(f*100)+'%)',eq:exploitR2.eq,c:'h',sizing:sz3,scene:'面对加注',spr:spr,sprAdvice:getSPRAdvice(spr),exploit:exploitR2.exploit,drta:{type:profile.type,conf:profile.confidence,weights:weights2},_se:true,_seFreq:f};
      }
      if(act==='c'){
        var exploitR3=applyExploit(eq,'call',oppType,{bet:actualBet,pot:pot});
        var weights3=DRTA.getWeights?DRTA.getWeights(profile):{};
        return{a:'call',r:'GTO call vs '+rP5+'('+Math.round(f*100)+'%)',eq:exploitR3.eq,c:eq>=50?'m':'l',scene:'面对加注',spr:spr,sprAdvice:getSPRAdvice(spr),evs:compareEVs(eq,pot,actualBet,0),exploit:exploitR3.exploit,drta:{type:profile.type,conf:profile.confidence,weights:weights3},_se:true,_seFreq:f};
      }
      return _fold(eq,'vs '+rP5+' fold',spr);
    }

    // ====== 场景3: 面对3bet(reraise) ======
    if(scene==='reraise'){
      var f3b=_F3B[p5];
      if(!f3b)return null;
      var entry3=f3b[k];
      if(!entry3)return _fold(eq,k+' vs 3bet→fold',spr);
      var act3=entry3.a;
      var f3=entry3.f;
      // 混合: 如果4bet频率未命中,走secondary(s字段)
      if(act3==='4b'&&Math.random()>f3){
        if(entry3.s){
          act3=entry3.s.a;
          f3=entry3.s.f;
          if(Math.random()>f3)return _fold(eq,k+' vs 3bet 4b未中→'+act3+'未中→fold',spr);
        }else{
          return _fold(eq,k+' vs 3bet 4b'+Math.round(f3*100)+'%未中→fold',spr);
        }
      }
      var actualBet3=bet||pot*3;
      if(act3==='4b'){
        var sz4=Math.round(actualBet3*2.5);
        sz4=Math.min(sz4,stk);
        G._heroDid4bet=true;
        var exploitR4=applyExploit(eq,'raise',oppType,{bet:actualBet3,pot:pot,facing3bet:true});
        var weights4=DRTA.getWeights?DRTA.getWeights(profile):{};
        return{a:'raise',v:sz4,r:'GTO 4bet('+Math.round(f3*100)+'%)',eq:exploitR4.eq,c:'h',sizing:sz4,scene:'面对3bet',spr:spr,sprAdvice:getSPRAdvice(spr),evs:compareEVs(eq,pot,actualBet3,sz4),exploit:exploitR4.exploit,drta:{type:profile.type,conf:profile.confidence,weights:weights4},_se:true,_seFreq:f3};
      }
      if(act3==='c'){
        var exploitR5=applyExploit(eq,'call',oppType,{bet:actualBet3,pot:pot});
        var weights5=DRTA.getWeights?DRTA.getWeights(profile):{};
        return{a:'call',r:'GTO call 3bet('+Math.round(f3*100)+'%)',eq:exploitR5.eq,c:eq>=50?'m':'l',scene:'面对3bet',spr:spr,sprAdvice:getSPRAdvice(spr),evs:compareEVs(eq,pot,actualBet3,0),exploit:exploitR5.exploit,drta:{type:profile.type,conf:profile.confidence,weights:weights5},_se:true,_seFreq:f3};
      }
      return _fold(eq,'vs 3bet fold',spr);
    }

    // ====== 场景4: 面对allin ======
    if(scene==='allin'){
      var allinBet=bet||pot;
      var allinEq=eq+(posMod[p]!==undefined?posMod[p]:0);
      var callThresh=allinBet>0?Math.round(allinBet/(pot+allinBet*2)*100):50;
      callThresh=Math.max(callThresh,45);
      if(allinEq>=callThresh){
        var exploitA=applyExploit(allinEq,'call',G.opp,{bet:allinBet,pot:pot});
        return{a:'call',r:'GTO vs allin eq'+Math.round(allinEq)+'%>='+callThresh+'%',eq:exploitA.eq,c:allinEq>=65?'h':'m',scene:'面对allin',spr:spr,exploit:exploitA.exploit,_se:true};
      }
      return _fold(allinEq,'vs allin eq'+Math.round(allinEq)+'%<'+callThresh+'%',spr);
    }

    // 其他场景: 回退到旧引擎
    return null;
  }

  // ====== 翻后决策 ======
  function decidePostflop(k){
    var h=G.hole||[];
    var hole=h.filter(function(c){return c;});
    var comm=G.comm||[];
    var bc=comm.filter(function(c){return c;});
    var pot=G.pot||1,bet=G.bet||0,stk=G.stk||100000;
    var spr=calcSPR();
    var ip=pA(G.pos)>=0.5;
    var scene=G.scene||'check';
    var street=getCurrentStreet();
    var bTexture=boardTexture(bc);
    var hClass=handClassify(hole,comm);
    var oppType=G.opp||'unknown';
    var profile=DRTA.getProfile();
    var eq;

    // equity计算
    if(bc.length===5){var rs=riverExactEquity(hole,comm,getOppRange('postflop','cbet',bTexture.wetness>=2?'wet':'dry'));eq=rs.eq;}
    else{var mcI=bc.length===4?1200:1000;if(bet/(pot+bet)>=.66)mcI=Math.round(mcI*2);else if(bet/(pot+bet)>=.33)mcI=Math.round(mcI*1.3);var ms=mcVsRange(hole,comm,getOppRange('postflop','cbet',bTexture.wetness>=2?'wet':'dry'),Math.min(mcI,4000));eq=ms.eq;}
    _mcSimCache=null;

    var btKey=_bt2key(bTexture);
    var hcKey=_hc2key(hClass);
    var didPFR=ActionLine.didPreflopRaise();
    var weights=DRTA.getWeights?DRTA.getWeights(profile):{};

    // ====== CBet场景: PFR, 面对check ======
    if(didPFR&&scene==='check'&&street!=='river'){
      var cbTable=ip?_CBET_IP[btKey]:_CBET_OOP[btKey];
      if(cbTable&&cbTable[hcKey]){
        var cb=cbTable[hcKey];
        var cbFreq=cb[0];
        var cbSmall=cb[1];

        // 对手剥削: 过度弃牌→CBet更多
        if(OppProfiler&&OppProfiler._profiles){
          var ftCB=OppProfiler.getStat?OppProfiler.getStat('foldToCBetFlop'):0;
          if(ftCB>.55)cbFreq=Math.min(.95,cbFreq*1.3); // 对手过度弃牌
          if(oppType==='calling_station'||oppType==='fish'){
            if(hcKey>=13)cbFreq=Math.round(cbFreq*0.3); // vs鱼不诈唬CBet
          }
        }

        if(Math.random()<cbFreq){
          // 决定sizing
          var cbPct=cbSmall>.5?.33:.66;
          // 湿面用大尺度
          if(bTexture.wetness>=2)cbPct=.66;
          var cbSz=Math.round(pot*cbPct);
          cbSz=Math.min(cbSz,stk);
          var exploitR=applyExploit(eq,ip?'bet':'bet',oppType,{bet:bet,pot:pot});
          return{a:'raise',v:cbSz,r:'GTO CBet '+btKey+'/'+hcKey+'('+Math.round(cbFreq*100)+'%) '+(cbPct<.5?'小':'大')+'尺度',eq:exploitR.eq,c:eq>=55?'h':eq>=45?'m':'l',sizing:cbSz,scene:'CBet',spr:spr,sprAdvice:getSPRAdvice(spr),hClass:hClass,bTexture:bTexture,exploit:exploitR.exploit,drta:{type:profile.type,conf:profile.confidence,weights:weights},_se:true,_seFreq:cbFreq};
        }else{
          // 不CBet → check
          var exploitR2=applyExploit(eq,'check',oppType,{bet:0,pot:pot});
          return{a:'check',r:'GTO check('+Math.round((1-cbFreq)*100)+'%频率)',eq:exploitR2.eq,c:'m',scene:'check',spr:spr,sprAdvice:getSPRAdvice(spr),hClass:hClass,bTexture:bTexture,exploit:exploitR2.exploit,drta:{type:profile.type,conf:profile.confidence,weights:weights},_se:true};
        }
      }
    }

    // ====== Check-Raise场景: OOP面对CBet ======
    if(!ip&&scene==='raise'&&!didPFR&&street==='flop'){
      var crTable=_CR[btKey];
      if(crTable&&crTable[hcKey]){
        var cr=crTable[hcKey];
        var crFreq=cr[0];
        var crSzMult=cr[1];

        // 对手过度CBet→更多CR
        if(OppProfiler&&OppProfiler.getStat){
          var oppCB=OppProfiler.getStat('cbetFlop');
          if(oppCB>.7)crFreq=Math.min(.3,crFreq*1.5);
        }

        if(Math.random()<crFreq){
          var crSz=Math.round(bet*crSzMult);
          crSz=Math.min(crSz,stk);
          var exploitCR=applyExploit(eq,'raise',oppType,{bet:bet,pot:pot});
          return{a:'raise',v:crSz,r:'GTO CR '+btKey+'/'+hcKey+'('+Math.round(crFreq*100)+'%)',eq:exploitCR.eq,c:eq>=50?'h':'m',sizing:crSz,scene:'Check-Raise',spr:spr,hClass:hClass,bTexture:bTexture,exploit:exploitCR.exploit,drta:{type:profile.type,conf:profile.confidence,weights:weights},_se:true,_seFreq:crFreq};
        }
      }
    }

    // ====== River诈唬/价值公式 ======
    if(street==='river'&&scene==='check'){
      // 如果我们有主动权(是PFR)且到了River
      if(didPFR&&hClass&&hClass.name==='AIR'){
        // River诈唬决策
        var bluffPct=.5; // 默认半池
        var bluffRatio=_RIV_BLUFF[bluffPct]||.33;
        // 对手剥削
        if(oppType==='calling_station'||oppType==='fish')bluffRatio*=.2; // vs鱼不诈唬
        if(oppType==='nit'||oppType==='tight')bluffRatio*=1.4; // vs紧多诈唬
        if(Math.random()<bluffRatio){
          var blSz=Math.round(pot*bluffPct);
          blSz=Math.min(blSz,stk);
          var exploitBL=applyExploit(eq,'raise',oppType,{bet:0,pot:pot});
          return{a:'raise',v:blSz,r:'GTO River诈唬('+Math.round(bluffRatio*100)+'%)',eq:exploitBL.eq,c:'l',sizing:blSz,scene:'River诈唬',spr:spr,hClass:hClass,bTexture:bTexture,exploit:exploitBL.exploit,drta:{type:profile.type,conf:profile.confidence,weights:weights},_se:true,_seFreq:bluffRatio};
        }
      }
    }

    // ====== 未匹配GTO策略表 → 回退旧引擎 ======
    return null;
  }

  // ====== 辅助函数 ======
  function _fold(eq,reason,spr){
    return{a:'fold',r:'[SE]'+reason,eq:eq,c:'l',scene:'',spr:spr||0,sprAdvice:spr?getSPRAdvice(spr):'',_se:true};
  }

  function _preflopSizing(pos,pot,scene,spr){
    var base=Math.round(pot*2.5);
    if(pos==='btn'||pos==='sb')base=Math.round(pot*3); // BTN/SB用3x
    if(spr<4)base=Math.round(pot*2.2); // 短码减小
    return Math.min(base,G.stk||100000);
  }

  function _adjFreq3bet(act,freq,oppType,raiserRole,heroPos){
    // vs鱼/calling_station: 不bluff 3bet
    if(act==='3b'&&(oppType==='calling_station'||oppType==='fish')){
      // 检查是否是价值3bet(对子+AK)
      // 简化: 对鱼bluff 3bet频率×0.3
      return freq*0.3;
    }
    // vs nit/tight: 3bet诈唬效率高
    if(act==='3b'&&(oppType==='nit'||oppType==='tight')){
      return Math.min(1,freq*1.2);
    }
    return freq;
  }

  function _get3bAlt(k,table,action){
    // 查找同一手牌的替代动作频率
    var e=table[k];
    if(!e)return 0;
    // 如果主动作是3b但频率未命中,看是否有call选项
    if(action==='c'){
      // 简化: 如果手牌不在call范围,给30%默认
      return 0.3;
    }
    return 0;
  }

  // ====== 公开接口 ======
  return{
    decidePreflop:decidePreflop,
    decidePostflop:decidePostflop,
    isEnabled:function(){return G._seEnabled!==false&&G.tt<=5;},
    getVersion:function(){return'2.9.155';},
    getRFI:function(pos){return _RFI[pos]||null;},
    get3B:function(vs,from){return _3B[vs]?_3B[vs][from]:null;},
    getF3B:function(pos){return _F3B[pos]||null;},
    getCBetIP:function(bt){return _CBET_IP[bt]||null;},
    getCBetOOP:function(bt){return _CBET_OOP[bt]||null;}
  };
  })();

  if(typeof global!=="undefined")global.StrategyEngine=StrategyEngine;

#!/bin/bash
cd "$(dirname "$0")"
node -e "
var fakeEl = { textContent: '', innerHTML: '', style: {}, classList: { add: function(){}, remove: function(){}, contains: function(){return false;} }, appendChild: function(){}, addEventListener: function(){} };
global.document = {
  getElementById: function() { return fakeEl; },
  querySelector: function() { return fakeEl; },
  querySelectorAll: function() { return []; },
  addEventListener: function() {},
  body: { style: {}, appendChild: function(){} },
  createElement: function() { return fakeEl; }
};
global.window = global; global.navigator = { userAgent: 'node' };
global.XMLHttpRequest = function() { this.open=function(){}; this.send=function(){}; this.setRequestHeader=function(){}; };
global.fetch = function() { return Promise.resolve({ json: function() { return {}; } }); };

// 先加载SDK
var sdk = require('./index.js');
sdk.initEngine();

// 然后加载test.js（它会require index.js但引擎已缓存）
var path = require('path');
var testCode = require('fs').readFileSync(path.join(__dirname, 'test.js'), 'utf8');
eval(testCode);
"

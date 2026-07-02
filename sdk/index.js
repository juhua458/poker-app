'use strict';
var fs = require('fs');
var path = require('path');

var HTML_PATH = '/tmp/poker_helper_v161_mod.html';
var TMP_PATH = '/tmp/_engine_tmp_v161.js';

function extractScript(html) {
  var lines = html.split('\n');
  var sc = 0, sl = -1, el = -1;
  for (var i = 0; i < lines.length; i++) {
    if (lines[i].indexOf('<script>') >= 0) {
      sc++;
      if (sc === 2) sl = i + 1;
    }
    if (sc === 2 && lines[i].indexOf('</script>') >= 0 && i > sl) {
      el = i; break;
    }
  }
  return lines.slice(sl, el).join('\n');
}

function processJs(jsCode) {
  var re = /^var\s+(\w+)\s*=/gm;
  var processed = jsCode.replace(re, 'global.$1=');
  var fnRe = /^function\s+(\w+)\s*\(/gm;
  var m, exports = [];
  while ((m = fnRe.exec(jsCode)) !== null) exports.push(m[1]);
  processed += '\n// SDK exports (容错: IIFE内function跳过)\n';
  exports.forEach(function(n) { processed += 'try{if(typeof ' + n + '!=="undefined")global.' + n + '=' + n + ';}catch(e){}\n'; });
  return 'var API_BASE="http://127.0.0.1:8666";\n' + processed;
}

function initEngine() {
  var tmpFile = TMP_PATH;
  if (!fs.existsSync(tmpFile)) {
    var html = '';
    var paths = [HTML_PATH, '/tmp/poker_helper_v161_mod.html'];
    for (var i = 0; i < paths.length; i++) {
      if (fs.existsSync(paths[i])) { html = fs.readFileSync(paths[i], 'utf8'); break; }
    }
    if (!html) throw new Error('No HTML found');
    var jsCode = extractScript(html);
    var processed = processJs(jsCode);
    fs.writeFileSync(tmpFile, processed);
  }
  if (typeof global.localStorage === 'undefined') {
    var store = {};
    global.localStorage = {
      getItem: function(k) { return store[k] || null; },
      setItem: function(k, v) { store[k] = String(v); },
      removeItem: function(k) { delete store[k]; }
    };
  }
  require(tmpFile);
}

module.exports = { initEngine: initEngine };

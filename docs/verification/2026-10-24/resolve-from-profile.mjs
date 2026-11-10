import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import path from 'node:path';

// 模拟 dsh 的解析基准：profiles/web 目录
const base = pathToFileURL(path.resolve('C:/tmp/v64-repro/profiles/web/') + '/');
const r = createRequire(base);
const specs = [
  'dsh-memory',
  '@deepseek-ai/dsh-tool-voice',
  '@deepseek-ai/dsh-tool-accessibility',
  '@deepseek-ai/dsh-base',
  '@deepseek-ai/dsh-web-app',
];
for (const s of specs) {
  try { console.log('OK  ', s, '->', path.basename(path.dirname(r.resolve(s)))); }
  catch (e) { console.log('FAIL', s, '->', e.code); }
}

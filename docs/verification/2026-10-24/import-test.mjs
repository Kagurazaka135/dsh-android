import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import path from 'node:path';

const dshBin = path.resolve('devhome/payload/dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js');
const r = createRequire(pathToFileURL(dshBin));
const target = r.resolve('dsh-memory');
console.log('target =', target);
try {
  const m = await import(pathToFileURL(target).href);
  console.log('IMPORT OK, exports =', Object.keys(m));
  console.log('name =', m.name, '| inject =', JSON.stringify(m.inject));
} catch (e) {
  console.log('IMPORT FAIL:', e.code || e.name);
  console.log('message:', (e.message || '').split('\n').slice(0, 6).join('\n'));
}

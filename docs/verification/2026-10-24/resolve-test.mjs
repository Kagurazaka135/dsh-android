import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import path from 'node:path';

const dshBin = path.resolve('devhome/payload/dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js');
const r = createRequire(pathToFileURL(dshBin));
for (const spec of ['dsh-memory', '@deepseek-ai/dsh-tools', '@deepseek-ai/dsh-tool-voice']) {
  try {
    console.log('OK  ', spec, '->', r.resolve(spec));
  } catch (e) {
    console.log('FAIL', spec, '->', e.code, '|', (e.message || '').split('\n')[0]);
  }
}

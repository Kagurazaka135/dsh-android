import fs from 'node:fs';

// The web-only preview notice has process-local acknowledgement and repeats on engine restarts.
// Keep it for browser clients; the Android shell opens directly into a conversation.
const file = process.argv[2];
if (!file) throw new Error('Expected the Models client bundle path');
const before = 'if (!("dshDesktop" in globalThis)) ctx.slots.inject("settings.onboarding",';
const after = 'if (!("dshDesktop" in globalThis) && !("dshshell" in globalThis)) ctx.slots.inject("settings.onboarding",';
const source = fs.readFileSync(file, 'utf8');
if (source.includes(after)) {
  console.log('  Android preview-notice adaptation already present');
} else {
  if (source.split(before).length !== 2) {
    throw new Error('Models client bootstrap changed; review the Android preview-notice adaptation');
  }
  fs.writeFileSync(file, source.replace(before, after));
  console.log('  Android preview-notice adaptation applied');
}

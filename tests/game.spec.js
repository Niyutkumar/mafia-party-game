// End-to-end tests for the Mafia game (index.html), run in a real Chromium browser.
// The page is opened the way the Android app opens it, with a fake window.AndroidBridge
// standing in for the Java side (voice, vibration).
const { test, expect } = require('@playwright/test');
const path = require('path');
const fs = require('fs');

const GAME = 'file://' + path.resolve(__dirname, '..', 'index.html');
const N = ['Asha', 'Bilal', 'Chirag', 'Dev', 'Esha', 'Farah', 'Gopal'];

// Open the game as the Android app sees it. Returns the list of JavaScript errors on the page.
async function openApp(page) {
  await page.addInitScript(() => {
    Object.defineProperty(window, 'speechSynthesis', { value: undefined }); // Android's WebView has no Web Speech
    window.__bridge = [];
    window.AndroidBridge = {
      isAndroidApp: () => true,
      speak: (text) => window.__bridge.push('speak'),
      stopSpeaking: () => window.__bridge.push('stop'),
      vibrate: (ms) => window.__bridge.push('vibrate'),
    };
    window.__csp = [];
    document.addEventListener('securitypolicyviolation', (e) => window.__csp.push(e.violatedDirective));
  });
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.goto(GAME);
  return errors;
}

// Deal fixed roles, then play one night where each player taps the names in `picks`.
// Returns who died and what each Detective was told.
async function playNight(page, { names, roles, picks }) {
  await page.evaluate(({ names, roles }) => {
    document.getElementById('player-input').value = names.join(', ');
    handlePlayerInputChange();
    Object.keys(customRoles).forEach((k) => (customRoles[k] = roles.filter((r) => r === k).length));
    startGame();
    players.forEach((p, i) => (p.roleKey = roles[i]));
    for (let i = 0; i < players.length; i++) { revealRole(); nextPlayer(); }
  }, { names, roles });

  const detective = {};
  for (let i = 0; i < names.length; i++) {
    await page.locator('#night-pass-prompt .btn').click();
    const who = (await page.textContent('#night-role-title')).trim();
    for (const name of [].concat(picks[who] ?? [])) {
      await page.locator('#night-targets-grid .target-btn', { hasText: new RegExp('^' + name) }).first().click();
    }
    if (roles[names.indexOf(who)] === 'detective') {
      detective[who] = (await page.textContent('#detective-feedback')).replace(/\s+/g, ' ').trim();
    }
    await page.locator('#night-submit-btn').click();
  }
  const dead = await page.evaluate(() => players.filter((p) => !p.isAlive).map((p) => p.name).sort());
  return { dead, detective };
}

test.describe('Night rules', () => {
  test('one Mafia kills their target', async ({ page }) => {
    await openApp(page);
    const { dead } = await playNight(page, { names: N.slice(0, 5),
      roles: ['mafia', 'villager', 'villager', 'villager', 'doctor'],
      picks: { Asha: 'Dev', Esha: 'Esha' } });
    expect(dead).toEqual(['Dev']);
  });

  test('two Mafia who agree kill their target', async ({ page }) => {
    await openApp(page);
    const { dead } = await playNight(page, { names: N.slice(0, 6),
      roles: ['mafia', 'mafia', 'villager', 'villager', 'villager', 'doctor'],
      picks: { Asha: 'Dev', Bilal: 'Dev', Farah: 'Farah' } });
    expect(dead).toEqual(['Dev']);
  });

  test('the majority wins a split Mafia vote (2 to 1)', async ({ page }) => {
    await openApp(page);
    const { dead } = await playNight(page, { names: N,
      roles: ['mafia', 'mafia', 'mafia', 'villager', 'villager', 'villager', 'doctor'],
      picks: { Asha: 'Esha', Bilal: 'Dev', Chirag: 'Dev', Gopal: 'Gopal' } });
    expect(dead).toEqual(['Dev']);
  });

  test('a tied Mafia vote means nobody dies, every time', async ({ page }) => {
    await openApp(page);
    for (let round = 0; round < 3; round++) {
      await page.reload();
      const { dead } = await playNight(page, { names: N.slice(0, 6),
        roles: ['mafia', 'mafia', 'villager', 'villager', 'villager', 'doctor'],
        picks: { Asha: 'Dev', Bilal: 'Esha', Farah: 'Farah' } });
      expect(dead).toEqual([]);
    }
  });

  test('a three-way Mafia split (1-1-1) means nobody dies', async ({ page }) => {
    await openApp(page);
    const { dead } = await playNight(page, { names: N,
      roles: ['mafia', 'mafia', 'mafia', 'villager', 'villager', 'villager', 'doctor'],
      picks: { Asha: 'Dev', Bilal: 'Esha', Chirag: 'Farah', Gopal: 'Gopal' } });
    expect(dead).toEqual([]);
  });

  test('a Mafia player can change their mind before tapping Done', async ({ page }) => {
    await openApp(page);
    const { dead } = await playNight(page, { names: N.slice(0, 5),
      roles: ['mafia', 'villager', 'villager', 'villager', 'doctor'],
      picks: { Asha: ['Esha', 'Dev'], Esha: 'Bilal' } });
    expect(dead).toEqual(['Dev']);
  });

  test('the Doctor saves the Mafia target', async ({ page }) => {
    await openApp(page);
    const { dead } = await playNight(page, { names: N.slice(0, 6),
      roles: ['doctor', 'doctor', 'mafia', 'villager', 'villager', 'villager'],
      picks: { Asha: 'Dev', Bilal: 'Esha', Chirag: 'Dev' } });
    expect(dead).toEqual([]);
  });

  test('every Vigilante shot counts', async ({ page }) => {
    await openApp(page);
    const { dead } = await playNight(page, { names: N,
      roles: ['vigilante', 'vigilante', 'mafia', 'villager', 'villager', 'villager', 'doctor'],
      picks: { Asha: 'Dev', Bilal: 'Esha', Chirag: 'Farah', Gopal: 'Gopal' } });
    expect(dead).toEqual(['Dev', 'Esha', 'Farah']);
  });

  test('a Mafia tie still lets the Vigilante shot land', async ({ page }) => {
    await openApp(page);
    const { dead } = await playNight(page, { names: N,
      roles: ['mafia', 'mafia', 'vigilante', 'villager', 'villager', 'villager', 'doctor'],
      picks: { Asha: 'Dev', Bilal: 'Esha', Chirag: 'Farah', Gopal: 'Gopal' } });
    expect(dead).toEqual(['Farah']);
  });

  test('Detectives learn the truth, and two Detectives never freeze the night', async ({ page }) => {
    await openApp(page);
    const { detective } = await playNight(page, { names: N.slice(0, 6),
      roles: ['detective', 'detective', 'mafia', 'villager', 'villager', 'doctor'],
      picks: { Asha: 'Chirag', Bilal: 'Dev', Chirag: 'Esha', Farah: 'Asha' } });
    expect(detective.Asha).toMatch(/Chirag is Mafia/);
    expect(detective.Bilal).toMatch(/Dev is not Mafia/);
  });
});

test.describe('Secret role cards', () => {
  test('Mafia see their partners; other roles see no one', async ({ page }) => {
    await openApp(page);
    const cards = await page.evaluate((names) => {
      document.getElementById('player-input').value = names.join(', ');
      handlePlayerInputChange();
      Object.keys(customRoles).forEach((k) => (customRoles[k] = 0));
      customRoles.mafia = 2; customRoles.villager = 3; customRoles.doctor = 1;
      startGame();
      ['mafia', 'mafia', 'villager', 'villager', 'villager', 'doctor'].forEach((r, i) => (players[i].roleKey = r));
      const text = {};
      for (let i = 0; i < players.length; i++) {
        revealRole();
        text[players[i].name] = document.getElementById('role-team').textContent;
        nextPlayer();
      }
      return text;
    }, N.slice(0, 6));
    expect(cards.Asha).toMatch(/partner is Bilal/);
    expect(cards.Bilal).toMatch(/partner is Asha/);
    expect(cards.Chirag).not.toMatch(/partner/i);
  });
});

test.describe('Whole games', () => {
  test('Vincent hosts: deal, night, morning report, vote, town wins', async ({ page }) => {
    const errors = await openApp(page);
    const button = (name) => page.getByRole('button', { name }).locator('visible=true').first();
    await page.fill('#player-input', 'Asha, Bilal, Chirag, Dev, Esha');
    await button('Auto-balance').click();
    await button('Deal the roles').click();
    for (let i = 0; i < 5; i++) {
      await button('Show my role').click();
      await page.locator('#next-btn').click();
    }
    const roles = await page.evaluate(() => Object.fromEntries(players.map((p) => [p.name, p.roleKey])));
    const mafia = Object.keys(roles).find((n) => roles[n] === 'mafia');
    const victim = Object.keys(roles).find((n) => roles[n] === 'villager');
    for (let i = 0; i < 5; i++) {
      await button('I have the phone').click();
      const role = roles[(await page.textContent('#night-role-title')).trim()];
      const target = role === 'mafia' ? victim : mafia;
      if (role !== 'villager') await page.locator('#night-targets-grid .target-btn', { hasText: target }).click();
      await page.locator('#night-submit-btn').click();
    }
    await expect(page.locator('#dawn-story-text')).toContainText(victim);
    await page.locator('#day-vote-grid .vote-row', { hasText: mafia }).click();
    await page.locator('#modal-actions .btn', { hasText: 'Vote out' }).click();
    await page.locator('#modal-actions .btn', { hasText: 'Continue' }).click();
    await expect(page.locator('#victory-title')).toContainText(/town/i);
    await expect(page.locator('#endgame-roles-body .roles-row')).toHaveCount(5);
    expect(await page.evaluate(() => window.__bridge.filter((c) => c === 'speak').length)).toBeGreaterThan(0); // Vincent narrated
    expect(errors).toEqual([]);
  });

  test('A friend hosts: the app only deals roles, then shows the host every role', async ({ page }) => {
    const errors = await openApp(page);
    const button = (name) => page.getByRole('button', { name }).locator('visible=true').first();
    await page.locator('#tab-host').click();
    await page.fill('#player-input', 'Asha, Bilal, Chirag, Dev, Esha');
    await button('Auto-balance').click();
    await button('Deal the roles').click();
    for (let i = 0; i < 5; i++) {
      await button('Show my role').click();
      await page.locator('#next-btn').click();
    }
    await button('Show all roles').click();
    await expect(page.locator('#host-roles-body .roles-row')).toHaveCount(5);
    expect(await page.evaluate(() => window.__bridge.filter((c) => c === 'speak').length)).toBe(0); // Vincent stays quiet
    expect(errors).toEqual([]);
  });
});

test.describe('Android app behaviour', () => {
  test('Back closes an open sheet with its safe button and never skips a game step', async ({ page }) => {
    await openApp(page);
    const back = () => page.evaluate(() => window.mafiaHandleBack());
    expect(await back()).toBe(false); // nothing open: the app asks "Leave Mafia Game?"

    await playNight(page, { names: N.slice(0, 5),
      roles: ['mafia', 'villager', 'villager', 'villager', 'doctor'],
      picks: { Asha: 'Dev', Esha: 'Esha' } });
    await page.locator('#day-vote-grid .vote-row', { hasText: 'Bilal' }).click();
    await expect(page.locator('#modal-title')).toContainText('Bilal');
    expect(await back()).toBe(true); // "Vote out Bilal?" -> Back = Cancel
    expect(await page.evaluate(() => players.find((p) => p.name === 'Bilal').isAlive)).toBe(true);
    await expect(page.locator('#custom-modal')).not.toHaveClass(/active/);
  });

  test('the Content-Security-Policy is never violated and the page has no errors', async ({ page }) => {
    const errors = await openApp(page);
    await playNight(page, { names: N.slice(0, 6),
      roles: ['mafia', 'detective', 'doctor', 'vigilante', 'villager', 'villager'],
      picks: { Asha: 'Esha', Bilal: 'Asha', Chirag: 'Esha', Dev: 'Farah' } });
    expect(await page.evaluate(() => window.__csp)).toEqual([]);
    expect(errors).toEqual([]);
  });

  test('no accessibility failures on the setup screen (axe-core, WCAG 2.1 AA)', async ({ page }) => {
    await openApp(page);
    // let the screen finish fading in, so colours are measured as players see them
    await page.evaluate(() => Promise.all(document.getAnimations()
      .filter((a) => a.effect.getComputedTiming().iterations !== Infinity).map((a) => a.finished)));
    await page.evaluate(fs.readFileSync(require.resolve('axe-core/axe.min.js'), 'utf8'));
    const violations = await page.evaluate(async () => {
      const r = await axe.run(document, { runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] } });
      return r.violations.map((v) => `${v.id}: ${v.nodes.length} element(s)`);
    });
    expect(violations).toEqual([]);
  });
});

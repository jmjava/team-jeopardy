import { test, expect } from '@playwright/test'

/**
 * Browser e2e covering moderator content pick, hints, admit gate,
 * host preview, buzz, incorrect reopen, and correct judge.
 * Uses the Maven sample so CI does not depend on GitHub rate limits.
 */

async function joinAs(page, roomCode, name, team) {
  await page.goto('/')
  await page.locator('form').filter({ hasText: 'Player' }).locator('input').nth(0).fill(roomCode)
  await page.locator('form').filter({ hasText: 'Player' }).locator('input').nth(1).fill(name)
  await page.locator('form').filter({ hasText: 'Player' }).locator('input').nth(2).fill(team)
  await page.getByRole('button', { name: /Join lobby/i }).click()
  await expect(page.getByText(/Waiting for the moderator/i)).toBeVisible({ timeout: 20_000 })
}

test('moderator picks sample, sets hints, admits, full clue loop', async ({ browser }) => {
  const hostCtx = await browser.newContext()
  const p1Ctx = await browser.newContext()
  const p2Ctx = await browser.newContext()
  const host = await hostCtx.newPage()
  const p1 = await p1Ctx.newPage()
  const p2 = await p2Ctx.newPage()

  await host.goto('/')
  await host.getByRole('button', { name: /Create room/i }).click()
  await expect(host.getByRole('heading', { name: 'Game control' })).toBeVisible({
    timeout: 20_000
  })

  const roomCode = (await host.locator('.room-chip strong').textContent())?.trim()
  expect(roomCode).toBeTruthy()

  // Question hints UI
  await host.getByRole('button', { name: 'Design patterns' }).click()
  await host.getByRole('button', { name: 'QA / risk' }).click()
  await host.getByRole('button', { name: 'Architecture' }).click()
  await host.locator('textarea').first().fill('Emphasize patterns, architecture, and QA risk for this match')

  await host.getByRole('button', { name: 'Maven' }).click()
  await expect(host.getByText(/Board ready/i)).toBeVisible({ timeout: 60_000 })
  await expect(host.getByText(/Last used:/i)).toBeVisible()

  await joinAs(p1, roomCode, 'Alex', 'Blue Owls')
  await joinAs(p2, roomCode, 'Sam', 'Red Foxes')

  const waiting = host.locator('.panel').filter({ hasText: 'Waiting' })
  await expect(waiting.getByText('Alex', { exact: true })).toBeVisible()
  await expect(waiting.getByText('Sam', { exact: true })).toBeVisible()

  // Single admit first — Start needs at least one admitted teamed player
  await waiting.getByRole('button', { name: /^Admit$/i }).first().click()
  const inGame = host.locator('.panel').filter({ hasText: 'In game' })
  await expect(inGame.getByText('Alex', { exact: true })).toBeVisible({ timeout: 15_000 })

  await host.getByRole('button', { name: /Admit all/i }).click()
  await expect(p1.getByRole('heading', { name: 'Waiting for kickoff' })).toBeVisible({
    timeout: 20_000
  })
  await expect(p2.getByRole('heading', { name: 'Waiting for kickoff' })).toBeVisible({
    timeout: 20_000
  })

  await host.getByRole('button', { name: /Start game/i }).click()
  await expect(host.locator('.board')).toBeVisible({ timeout: 20_000 })
  await expect(p1.locator('.board')).toBeVisible({ timeout: 20_000 })

  const cell = host.locator('button.cell:not(.answered)').first()
  await cell.click()
  await expect(host.getByText('Clue — read this first')).toBeVisible({ timeout: 15_000 })
  await expect(
    host.getByRole('button', { name: 'Show clue & open buzzers' })
  ).toBeVisible()

  // Players should not see the full prompt yet
  await expect(p1.getByRole('heading', { name: 'Host is reading the clue' })).toBeVisible({
    timeout: 15_000
  })

  await host.getByRole('button', { name: 'Show clue & open buzzers' }).click()
  await expect(p1.getByRole('button', { name: 'Buzz' })).toBeVisible({ timeout: 15_000 })
  await expect(p2.getByRole('button', { name: 'Buzz' })).toBeVisible({ timeout: 15_000 })

  // Pulse animation keeps the buzz button "unstable" for Playwright's actionability checks.
  await p1.getByRole('button', { name: 'Buzz' }).click({ force: true })
  await expect(host.getByText('First buzz', { exact: true })).toBeVisible({ timeout: 15_000 })
  await expect(host.locator('.buzz-banner h2')).toHaveText('Alex')

  // Incorrect → buzzers reopen for the other player
  await host.getByRole('button', { name: 'Incorrect', exact: true }).click()
  await expect(p2.getByRole('button', { name: 'Buzz' })).toBeVisible({ timeout: 15_000 })
  await p2.getByRole('button', { name: 'Buzz' }).click({ force: true })
  await expect(host.locator('.buzz-banner h2')).toHaveText('Sam')

  await host.getByRole('button', { name: 'Correct', exact: true }).click()
  await expect(host.getByRole('button', { name: 'Back to board', exact: true })).toBeVisible({
    timeout: 15_000
  })
  await host.getByRole('button', { name: 'Back to board', exact: true }).click()
  await expect(host.locator('.board')).toBeVisible()

  await hostCtx.close()
  await p1Ctx.close()
  await p2Ctx.close()
})

test('moderator can switch sample content types after hints', async ({ page }) => {
  await page.goto('/')
  await page.getByRole('button', { name: /Create room/i }).click()
  await expect(page.getByRole('heading', { name: 'Game control' })).toBeVisible({
    timeout: 20_000
  })

  await page.getByRole('button', { name: 'Components' }).click()
  await page.getByRole('button', { name: 'Pull requests' }).click()
  await page.locator('textarea').first().fill('Prefer component and PR-flavored clues')

  for (const label of ['Gradle', 'Vue', 'Python']) {
    await page.getByRole('button', { name: label, exact: true }).click()
    await expect(page.getByText(/Board ready/i)).toBeVisible({ timeout: 90_000 })
  }

  // GitHub controls visible for content pick (may not call network in this test)
  await expect(page.getByRole('button', { name: /Build from GitHub/i })).toBeVisible()
  await expect(page.getByRole('button', { name: /PRs only/i })).toBeVisible()
  await expect(page.getByRole('button', { name: /Browse folders/i })).toBeVisible()
})

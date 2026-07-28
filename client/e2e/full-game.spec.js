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
  await expect(host.getByText(/Game control|Moderator/i)).toBeVisible({ timeout: 20_000 })

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

  await expect(host.getByText('Alex')).toBeVisible()
  await expect(host.getByText('Sam')).toBeVisible()

  // Single admit first — Start needs at least one admitted teamed player
  await host.getByRole('button', { name: /^Admit$/i }).first().click()
  await expect(host.locator('.panel').filter({ hasText: 'In game' }).getByText('Alex')).toBeVisible({
    timeout: 15_000
  })

  await host.getByRole('button', { name: /Admit all/i }).click()
  await expect(p1.getByText(/Waiting for kickoff|You're in/i)).toBeVisible({ timeout: 20_000 })
  await expect(p2.getByText(/Waiting for kickoff|You're in/i)).toBeVisible({ timeout: 20_000 })

  await host.getByRole('button', { name: /Start game/i }).click()
  await expect(host.locator('.board')).toBeVisible({ timeout: 20_000 })
  await expect(p1.locator('.board')).toBeVisible({ timeout: 20_000 })

  const cell = host.locator('button.cell:not(.answered)').first()
  await cell.click()
  await expect(host.getByText(/read this first|Show clue|Host preview/i)).toBeVisible({
    timeout: 15_000
  })

  // Players should not see the full prompt yet
  await expect(p1.getByText(/Host is reading the clue|Get ready/i)).toBeVisible({ timeout: 15_000 })

  await host.getByRole('button', { name: /Show clue & open buzzers/i }).click()
  await expect(p1.getByRole('button', { name: /^Buzz/i })).toBeVisible({ timeout: 15_000 })
  await expect(p2.getByRole('button', { name: /^Buzz/i })).toBeVisible({ timeout: 15_000 })

  await p1.getByRole('button', { name: /^Buzz/i }).click()
  await expect(host.getByText(/First buzz|Buzzed/i)).toBeVisible({ timeout: 15_000 })

  // Incorrect → buzzers reopen for the other player
  await host.getByRole('button', { name: 'Incorrect' }).click()
  await expect(p2.getByRole('button', { name: /^Buzz/i })).toBeVisible({ timeout: 15_000 })
  await p2.getByRole('button', { name: /^Buzz/i }).click()
  await expect(host.getByText('Sam')).toBeVisible({ timeout: 15_000 })

  await host.getByRole('button', { name: 'Correct' }).click()
  await expect(host.getByRole('button', { name: /Back to board/i })).toBeVisible({ timeout: 15_000 })
  await host.getByRole('button', { name: /Back to board/i }).click()
  await expect(host.locator('.board')).toBeVisible()

  await hostCtx.close()
  await p1Ctx.close()
  await p2Ctx.close()
})

test('moderator can switch sample content types after hints', async ({ page }) => {
  await page.goto('/')
  await page.getByRole('button', { name: /Create room/i }).click()
  await expect(page.getByText(/Game control|Moderator/i)).toBeVisible({ timeout: 20_000 })

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

import { test, expect } from '@playwright/test'

/**
 * Host + two players play typed Final Jeopardy (one right, one wrong).
 * Shared display must not show the other team's text until reveal.
 */

async function joinAs(page, roomCode, name, team) {
  await page.goto('/')
  await page.locator('form').filter({ hasText: 'Player' }).locator('input').nth(0).fill(roomCode)
  await page.locator('form').filter({ hasText: 'Player' }).locator('input').nth(1).fill(name)
  await page.locator('form').filter({ hasText: 'Player' }).locator('input').nth(2).fill(team)
  await page.getByRole('button', { name: /Join lobby/i }).click()
  await expect(page.getByText(/Waiting for the moderator/i)).toBeVisible({ timeout: 20_000 })
}

test('typed Final Jeopardy: host plus two players, one right one wrong', async ({ browser }) => {
  const hostCtx = await browser.newContext()
  const p1Ctx = await browser.newContext()
  const p2Ctx = await browser.newContext()
  const displayCtx = await browser.newContext()
  const host = await hostCtx.newPage()
  const p1 = await p1Ctx.newPage()
  const p2 = await p2Ctx.newPage()
  const display = await displayCtx.newPage()

  await host.goto('/')
  await host.getByRole('button', { name: /Create room/i }).click()
  await expect(host.getByRole('heading', { name: 'Game control' })).toBeVisible({
    timeout: 20_000
  })

  const roomCode = (await host.locator('.room-chip strong').textContent())?.trim()
  expect(roomCode).toBeTruthy()

  await host.getByRole('button', { name: 'Maven' }).click()
  await expect(host.getByText(/Board ready/i)).toBeVisible({ timeout: 60_000 })

  await joinAs(p1, roomCode, 'Alex', 'Blue Owls')
  await joinAs(p2, roomCode, 'Sam', 'Red Foxes')
  await host.getByRole('button', { name: /Admit all/i }).click()
  await host.getByRole('button', { name: /Start game/i }).click()
  await expect(host.locator('.board')).toBeVisible({ timeout: 20_000 })

  const displayUrl = await host.evaluate((code) => {
    const url = new URL(window.location.href)
    url.searchParams.set('view', 'display')
    url.searchParams.set('code', code)
    return url.toString()
  }, roomCode)
  await display.goto(displayUrl)

  await host.getByTestId('start-final').click()
  await expect(host.getByTestId('final-category')).toBeVisible({ timeout: 15_000 })
  await expect(p1.getByTestId('final-category')).toBeVisible({ timeout: 15_000 })
  await expect(display.getByTestId('final-category')).toBeVisible({ timeout: 15_000 })
  await expect(p1.getByTestId('final-prompt')).toHaveCount(0)

  await host.getByTestId('open-final-wagers').click()
  await expect(p1.getByTestId('wager-input')).toBeVisible({ timeout: 15_000 })
  await p1.getByTestId('wager-input').fill('200')
  await p1.getByTestId('lock-wager').click()
  await p2.getByTestId('wager-input').fill('50')
  await p2.getByTestId('lock-wager').click()

  await host.getByTestId('start-final-writing').click()
  await expect(p1.getByTestId('final-prompt')).toBeVisible({ timeout: 15_000 })
  await p1.getByTestId('final-answer-input').fill('What is the Maven module?')
  await p1.getByTestId('lock-answer').click()
  await p2.getByTestId('final-answer-input').fill('What is a red herring?')
  await p2.getByTestId('lock-answer').click()

  await expect(display.getByText('What is a red herring?')).toHaveCount(0)
  await expect(display.getByText('What is the Maven module?')).toHaveCount(0)

  await host.getByTestId('end-writing').click()
  await expect(display.getByTestId('answers-locked')).toBeVisible({ timeout: 15_000 })
  await expect(display.getByText('What is a red herring?')).toHaveCount(0)

  await host.getByTestId('reveal-final').click()
  await expect(host.getByTestId('final-official')).toBeVisible({ timeout: 15_000 })

  await host.locator('li', { hasText: 'Blue Owls' }).getByRole('button', { name: 'Correct', exact: true }).click()
  await host.locator('li', { hasText: 'Red Foxes' }).getByRole('button', { name: 'Incorrect', exact: true }).click()

  await expect(host.getByText('Correct').first()).toBeVisible()
  await expect(host.getByText('Incorrect')).toBeVisible()
  await expect(host.locator('.scorebar')).toContainText('$200')
  await expect(host.locator('.scorebar')).toContainText('$-50')
  await expect(display.locator('.scorebar')).toContainText('$200')

  await hostCtx.close()
  await p1Ctx.close()
  await p2Ctx.close()
  await displayCtx.close()
})

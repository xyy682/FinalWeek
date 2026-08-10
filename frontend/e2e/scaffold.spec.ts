import { expect, test } from '@playwright/test'

test('landing page exposes the primary journey', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: /把分散的课程资料/ })).toBeVisible()
  await expect(page.getByRole('link', { name: '开始使用' })).toHaveAttribute('href', '/login')
})


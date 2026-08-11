import { expect, test, type APIRequestContext } from '@playwright/test'

async function mailCode(request: APIRequestContext, email: string): Promise<string | null> {
  const list = await request.get('http://localhost:8025/api/v1/messages')
  if (!list.ok()) return null
  const body = await list.json() as { messages?: Array<{ ID: string; To?: Array<{ Address?: string }> }> }
  const message = body.messages?.find(value => value.To?.some(recipient => recipient.Address === email))
  if (!message) return null
  const detail = await request.get(`http://localhost:8025/api/v1/message/${message.ID}`)
  if (!detail.ok()) return null
  const text = (await detail.json() as { Text?: string }).Text ?? ''
  return text.match(/验证码是：?(\d{6})/)?.[1] ?? null
}

test('login → course → upload → parse → outline → plan → chat', async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'chromium', 'The expensive real-AI journey runs once in desktop Chromium.')
  test.setTimeout(8 * 60 * 1000)
  const identity = Date.now()
  const email = `playwright-${identity}@example.com`
  const courseName = `E2E 课程 ${identity}`

  await page.route('**/api/v1/tasks/events', route => route.abort('connectionrefused'))
  await page.goto('/login')
  await page.getByRole('textbox', { name: '邮箱' }).fill(email)
  await page.getByRole('button', { name: '发送验证码' }).click()
  let code: string | null = null
  await expect.poll(async () => { code = await mailCode(request, email); return code }, { timeout: 20_000 }).toMatch(/^\d{6}$/)
  await page.getByRole('textbox', { name: '验证码' }).fill(code!)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page.getByRole('heading', { name: '我的课程' })).toBeVisible()

  await page.getByRole('button', { name: '创建课程' }).click()
  await page.getByPlaceholder('例如：计算机网络').fill(courseName)
  await page.getByRole('dialog').getByRole('button', { name: '创建' }).click()
  await page.getByRole('link', { name: courseName }).click()
  await expect(page.getByRole('heading', { name: courseName })).toBeVisible()

  const material = [
    '牛顿第二定律说明物体的加速度与合外力成正比，与质量成反比。',
    '公式为 F=ma。教师强调这是考试重点，需要掌握力、质量和加速度的关系。',
    '等容过程体积不变，边界功为零。',
  ].join('\n\n')
  await page.locator('input[type=file]').setInputFiles({ name: `e2e-${identity}.txt`, mimeType: 'text/plain', buffer: Buffer.from(material) })
  await page.getByRole('button', { name: '开始上传' }).click()
  await expect(page.getByText(`e2e-${identity}.txt`).first()).toBeVisible()

  const courseId = new URL(page.url()).pathname.split('/')[2]
  await expect.poll(async () => {
    const response = await page.request.get(`/api/v1/courses/${courseId}/materials`)
    const values = await response.json() as Array<{ status: string }>
    return values[0]?.status
  }, { timeout: 180_000, intervals: [1_000, 2_000, 5_000] }).toBe('SUCCEEDED')
  await page.reload()
  await expect(page.getByText('解析成功').first()).toBeVisible()
  await expect(page.getByText('正在重新连接').first()).toBeVisible()

  await page.getByRole('link', { name: '知识提纲' }).click()
  await page.getByRole('button', { name: '生成提纲' }).first().click()
  await expect(page.getByText(/第 \d+ 版/)).toBeVisible({ timeout: 240_000 })
  await expect(page.getByRole('button', { name: /来源/ }).first()).toBeVisible()

  await page.getByRole('link', { name: '复习计划' }).click()
  await page.getByRole('button', { name: '生成计划' }).click()
  await expect(page.getByText(/版本 \d+ · 考试/)).toBeVisible({ timeout: 120_000 })
  await expect(page.locator('.plan-task').first()).toBeVisible()

  await page.getByRole('link', { name: '课程问答' }).click()
  await page.getByPlaceholder('输入课程相关问题（仅文字）').fill('牛顿第二定律的公式是什么？')
  await page.getByRole('button', { name: '发送' }).click()
  await expect(page.locator('.message.assistant')).toContainText('F', { timeout: 120_000 })
  await expect(page.locator('.message.assistant').getByText('课程资料来源', { exact: true })).toBeVisible()
  await page.locator('.message.assistant .sources button').first().click()
  await expect(page.getByRole('heading', { name: `e2e-${identity}.txt` })).toBeVisible()
  await page.screenshot({ path: '../docs/images/finalweek-course-chat.png', fullPage: true })
})

test('mobile layout keeps the primary journey usable', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'mobile-chromium', 'Mobile-only layout check')
  await page.goto('/login')
  await expect(page.getByRole('heading', { name: '邮箱验证码登录' })).toBeVisible()
  await expect(page.getByRole('button', { name: '发送验证码' })).toBeVisible()
  const bodyWidth = await page.locator('body').evaluate(element => element.scrollWidth)
  const viewport = page.viewportSize()
  expect(bodyWidth).toBeLessThanOrEqual(viewport?.width ?? bodyWidth)
  await page.screenshot({ path: '../docs/images/finalweek-mobile-login.png', fullPage: true })
})

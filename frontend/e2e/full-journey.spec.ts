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

test('login → knowledge version → async plan/chat → mock exam PDFs', async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'chromium', 'The expensive real-AI journey runs once in desktop Chromium.')
  test.setTimeout(12 * 60 * 1000)
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
  const materials = [
    { name: `e2e-courseware-${identity}.txt`, content: material },
    { name: `e2e-notes-${identity}.md`, content: '课堂例题：质量为 2 kg 的物体受到 6 N 合力时，加速度为 3 m/s²。\n\n热力学第一定律是 ΔU=Q-W。' },
  ]
  const courseId = new URL(page.url()).pathname.split('/')[2]
  for (const value of materials) {
    await page.locator('input[type=file]').setInputFiles({ name: value.name, mimeType: 'text/plain', buffer: Buffer.from(value.content) })
    await page.getByRole('button', { name: '开始上传' }).click()
    await expect(page.getByText(value.name).first()).toBeVisible()
    await expect.poll(async () => {
      const response = await page.request.get(`/api/v1/courses/${courseId}/materials`)
      const values = await response.json() as Array<{ originalFilename: string; status: string }>
      return values.find(material => material.originalFilename === value.name)?.status
    }, { timeout: 180_000, intervals: [1_000, 2_000, 5_000] }).toBe('SUCCEEDED')
  }

  await expect.poll(async () => {
    const response = await page.request.get(`/api/v1/courses/${courseId}/materials`)
    const values = await response.json() as Array<{ status: string }>
    return values.length === materials.length && values.every(value => value.status === 'SUCCEEDED')
  }, { timeout: 180_000, intervals: [1_000, 2_000, 5_000] }).toBe(true)
  await page.reload()
  await expect(page.locator('.desktop-table').getByText('解析成功')).toHaveCount(materials.length)
  await expect(page.getByText('正在重新连接').first()).toBeVisible()
  await page.getByRole('button', { name: '资料已上传完毕' }).click()

  await page.getByRole('link', { name: '知识提纲' }).click()
  await expect.poll(async () => {
    const response = await page.request.get(`/api/v1/courses/${courseId}/knowledge-version`)
    return (await response.json() as { current?: { status?: string } }).current?.status
  }, { timeout: 240_000, intervals: [2_000, 5_000] }).toBe('PUBLISHED')
  await page.reload()
  await expect(page.getByRole('button', { name: /来源/ }).first()).toBeVisible()

  await page.getByRole('link', { name: '复习计划' }).click()
  await page.getByRole('button', { name: '生成计划' }).click()
  await expect.poll(async () => {
    const response = await page.request.get(`/api/v1/courses/${courseId}/plan`)
    return (await response.json() as { plan?: { version?: number } }).plan?.version
  }, { timeout: 150_000, intervals: [2_000, 5_000] }).toBeGreaterThan(0)
  await page.reload()
  await expect(page.locator('.plan-task').first()).toBeVisible()

  await page.getByRole('link', { name: '课程问答' }).click()
  await page.getByPlaceholder('输入课程相关问题（仅文字）').fill('牛顿第二定律的公式是什么？')
  await page.getByRole('button', { name: '发送' }).click()
  await page.getByRole('link', { name: '资料', exact: true }).click()
  await page.getByRole('link', { name: '课程问答' }).click()
  await expect.poll(async () => {
    const response = await page.request.get(`/api/v1/courses/${courseId}/messages`)
    const values = (await response.json() as { messages: Array<{ role: string; status: string }> }).messages
    return values.some(value => value.role === 'ASSISTANT' && value.status === 'SUCCEEDED')
  }, { timeout: 150_000, intervals: [2_000, 5_000] }).toBe(true)
  await page.reload()
  await expect(page.locator('.message.assistant')).toContainText('F')
  await expect(page.locator('.message.assistant').getByText('课程资料来源', { exact: true })).toBeVisible()
  await page.locator('.message.assistant .sources button').first().click()
  await expect(page.getByRole('heading', { name: materials[0].name })).toBeVisible()
  await page.keyboard.press('Escape')

  await page.getByRole('link', { name: '模拟卷' }).click()
  const singleChoice = page.locator('.type-grid article').filter({ hasText: '单选题' })
  await expect(singleChoice.getByRole('checkbox', { name: '单选题' })).toBeChecked({ timeout: 30_000 })
  for (let count = 10; count > 2; count -= 1) await singleChoice.getByRole('button', { name: 'decrease number' }).click()
  await expect(singleChoice.getByRole('spinbutton')).toHaveValue('2')
  await page.getByRole('button', { name: '生成模拟卷' }).click()
  await expect.poll(async () => {
    const response = await page.request.get(`/api/v1/courses/${courseId}/mock-exams?page=0&size=10`)
    const values = (await response.json() as { items: Array<{ id: string; status: string }> }).items
    return values[0]?.status
  }, { timeout: 300_000, intervals: [2_000, 5_000] }).toBe('SUCCEEDED')
  await page.reload()
  await expect(page.getByRole('button', { name: '预览试卷' }).first()).toBeVisible()
  await expect(page.getByRole('button', { name: '下载答案' }).first()).toBeVisible()
  const history = await (await page.request.get(`/api/v1/courses/${courseId}/mock-exams?page=0&size=10`)).json() as { items: Array<{ id: string }> }
  const examId = history.items[0].id
  for (const kind of ['paper', 'answer'] as const) {
    const preview = await page.request.get(`/api/v1/mock-exams/${examId}/files/${kind}/preview`)
    expect(preview.ok()).toBeTruthy(); expect((await preview.json() as { url: string }).url).toMatch(/^https?:/)
    const download = await page.request.get(`/api/v1/mock-exams/${examId}/files/${kind}/download`)
    expect(download.ok()).toBeTruthy(); expect(download.headers()['content-type']).toContain('application/pdf')
    expect((await download.body()).length).toBeGreaterThan(1000)
  }
  await page.screenshot({ path: '../docs/images/finalweek-mock-exams.png', fullPage: true })
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

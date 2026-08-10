import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import LandingPage from './LandingPage.vue'

afterEach(() => vi.unstubAllGlobals())

describe('LandingPage', () => {
  it('shows that the backend is available after the health request succeeds', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ status: 'ok' }) }))
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: LandingPage }] })
    await router.push('/')
    await router.isReady()

    const wrapper = mount(LandingPage, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.get('[role="status"]').text()).toContain('可用')
  })
})


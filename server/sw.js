const CACHE_NAME = 'marstek-shell-v8';
const SHELL = [
  '/',
  '/app.js',
  '/style.css',
  '/favicon.svg',
  '/manifest.webmanifest',
  '/icons/marstek-192.png',
  '/icons/marstek-512.png',
  '/icons/marstek-maskable-512.png'
];

self.addEventListener('install', event => {
  event.waitUntil((async () => {
    const cache = await caches.open(CACHE_NAME);
    await cache.addAll(SHELL);
    await self.skipWaiting();
  })());
});

self.addEventListener('activate', event => {
  event.waitUntil((async () => {
    const names = await caches.keys();
    await Promise.all(names.filter(name => name.startsWith('marstek-shell-') && name !== CACHE_NAME).map(name => caches.delete(name)));
    await self.clients.claim();
  })());
});

self.addEventListener('fetch', event => {
  const request = event.request;
  const url = new URL(request.url);
  if (request.method !== 'GET' || url.origin !== self.location.origin || url.pathname.startsWith('/api/')) return;

  if (request.mode === 'navigate') {
    event.respondWith(fetch(request).then(async response => {
      if (response.ok) {
        const cache = await caches.open(CACHE_NAME);
        await cache.put('/', response.clone());
      }
      return response;
    }).catch(async () => (await caches.match('/')) || Response.error()));
    return;
  }

  if (SHELL.includes(url.pathname)) {
    event.respondWith(caches.match(request, {ignoreSearch: true}).then(cached => cached || fetch(request).then(async response => {
      if (response.ok) (await caches.open(CACHE_NAME)).put(request, response.clone());
      return response;
    })));
  }
});

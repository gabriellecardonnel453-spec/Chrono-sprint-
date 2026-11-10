// Application légère : une fois installée, les pages sont lues dans le cache.
const CACHE='chrono-sprint-v3';
const SHELL=['./','./index.html','./icon-512.png','./manifest.webmanifest'];
self.addEventListener('install',event=>{
  event.waitUntil(caches.open(CACHE).then(cache=>cache.addAll(SHELL)).then(()=>self.skipWaiting()));
});
self.addEventListener('activate',event=>{
  event.waitUntil(caches.keys().then(keys=>Promise.all(
    keys.filter(k=>k.startsWith('chrono-sprint-')&&k!==CACHE).map(k=>caches.delete(k))
  )).then(()=>self.clients.claim()));
});
self.addEventListener('fetch',event=>{
  const request=event.request;
  if(request.method!=='GET'||new URL(request.url).origin!==self.location.origin)return;
  event.respondWith((async()=>{
    const cached=await caches.match(request.mode==='navigate'?'./index.html':request);
    if(cached)return cached;
    try{
      const response=await fetch(request);
      if(response.ok){
        const copy=response.clone();
        event.waitUntil(caches.open(CACHE).then(cache=>cache.put(request,copy)));
      }
      return response;
    }catch(e){
      return (await caches.match('./index.html'))||Response.error();
    }
  })());
});

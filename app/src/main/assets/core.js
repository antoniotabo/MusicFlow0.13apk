(function(root){
 'use strict';
 function videoId(value){
  const raw=String(value).trim(); if(/^[\w-]{11}$/.test(raw))return raw;
  try{const u=new URL(raw);if(u.protocol!=='https:'&&u.protocol!=='http:')return null;
   const host=u.hostname.toLowerCase();let id=null;
   if(host==='youtu.be')id=u.pathname.split('/')[1];
   if(['youtube.com','www.youtube.com','m.youtube.com','music.youtube.com','www.youtube-nocookie.com'].includes(host))id=u.searchParams.get('v')||(/^\/(shorts|embed|live)\//.test(u.pathname)?u.pathname.split('/')[2]:null);
   return /^[\w-]{11}$/.test(id||'')?id:null;
  }catch{return null;}
 }
 function track(t){
  if(!t||typeof t.title!=='string')return null;
  const audio=t.kind==='audio';
  if(!(audio?/^audio-[a-f0-9-]{36}$/:/^[\w-]{11}$/).test(t.id))return null;
  const out={id:t.id,title:t.title.slice(0,300),channel:String(t.channel||'Enlace guardado').slice(0,200)};
  if(audio)out.kind='audio';return out;
 }
 function library(input){
  if(!input||!Array.isArray(input.favorites)||!Array.isArray(input.playlists))throw Error('Formato de biblioteca inválido');
  const unique=xs=>Array.from(new Map(xs.slice(0,5000).map(track).filter(Boolean).map(t=>[t.id,t])).values());
  return {favorites:unique(input.favorites),playlists:input.playlists.slice(0,100).filter(p=>p&&typeof p.name==='string'&&Array.isArray(p.tracks)).map((p,i)=>({id:'p'+i,name:p.name.slice(0,80),tracks:unique(p.tracks)}))};
 }
 function history(values){const seen=new Set();return (Array.isArray(values)?values:[]).filter(v=>typeof v==='string').map(v=>v.trim().slice(0,500)).filter(v=>{const key=v.toLocaleLowerCase();if(!v||seen.has(key))return false;seen.add(key);return true;}).slice(0,20);}
 const api={videoId,track,library,history};root.MusicFlowCore=api;if(typeof module!=='undefined')module.exports=api;
})(typeof window==='undefined'?globalThis:window);

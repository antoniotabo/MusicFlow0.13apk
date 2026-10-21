'use strict';
const $=id=>document.getElementById(id),core=MusicFlowCore;
let db={favorites:[],playlists:[]},results=[],tab='results',selected=null,current=null;
let queue=[],queueIndex=-1,viewReady=false,restoredScroll=0;
let heardIds=new Set(),autoSignature="";
function saveView(){if(!viewReady)return;try{localStorage.setItem('mf.view',JSON.stringify({results:results.slice(0,500),tab,selected,selectedIndex:db.playlists.findIndex(p=>p.id===selected),query:$('query').value,lastQuery,scroll:window.scrollY,filter:$('audioFilter').value,queue:queue.slice(0,5000)}));}catch{}}
function restoreView(){try{const v=JSON.parse(localStorage.getItem('mf.view')||'{}');results=Array.isArray(v.results)?v.results.map(core.track).filter(Boolean).slice(0,500):[];queue=Array.isArray(v.queue)?v.queue.map(core.track).filter(Boolean).slice(0,5000):[];if(['results','audio','downloads','favorites','playlists'].includes(v.tab))tab=v.tab;selected=Number.isInteger(v.selectedIndex)&&v.selectedIndex>=0&&db.playlists[v.selectedIndex]?db.playlists[v.selectedIndex].id:typeof v.selected==='string'?v.selected:null;$('query').value=typeof v.query==='string'?v.query.slice(0,500):'';lastQuery=typeof v.lastQuery==='string'?v.lastQuery:'';$('audioFilter').value=typeof v.filter==='string'?v.filter:'';restoredScroll=Math.max(0,Number(v.scroll)||0);}catch{}viewReady=true;}
function restoreScroll(){if(restoredScroll>0)requestAnimationFrame(()=>{window.scrollTo(0,restoredScroll);if(document.documentElement.scrollHeight>restoredScroll+window.innerHeight-2)restoredScroll=0;});}
window.addEventListener('pagehide',saveView);
document.addEventListener('visibilitychange',()=>{if(document.hidden)saveView();});
let scrollSaveTimer;window.addEventListener('scroll',()=>{clearTimeout(scrollSaveTimer);scrollSaveTimer=setTimeout(saveView,200);},{passive:true});
let shuffle=false;try{shuffle=localStorage.getItem('mf.shuffle')==='1';}catch{}
function showShuffle(){ $('shuffleToggle').setAttribute('aria-pressed',String(shuffle));$('shuffleToggle').setAttribute('aria-label',shuffle?'Desactivar reproducción aleatoria':'Activar reproducción aleatoria'); }
function setShuffle(value){shuffle=!!value;try{localStorage.setItem('mf.shuffle',shuffle?'1':'0');}catch{}showShuffle();controlAudio('shuffle',shuffle?1:0);}
function savedSearch(query){try{const entries=JSON.parse(localStorage.getItem('mf.searchCache')||'[]');if(!Array.isArray(entries))return [];const hit=entries.find(e=>e.key===query.toLowerCase()&&Date.now()-e.at<86400000);return hit&&Array.isArray(hit.items)?hit.items.map(core.track).filter(Boolean).slice(0,60):[];}catch{return [];}}
function saveSearch(query,items){try{let entries=JSON.parse(localStorage.getItem('mf.searchCache')||'[]');if(!Array.isArray(entries))entries=[];entries=entries.filter(e=>e.key!==query.toLowerCase()&&Date.now()-e.at<86400000).slice(0,9);entries.unshift({key:query.toLowerCase(),at:Date.now(),items:items.slice(0,60)});localStorage.setItem('mf.searchCache',JSON.stringify(entries));}catch{}}

let audioTracks=[],audioState={active:false},downloads=[];
let searchHistory=[];try{searchHistory=core.history(JSON.parse(localStorage.getItem('mf.history')||'[]'));}catch{}
function searchFrom(text){$('query').value=text;$('clearQuery').hidden=!text;$('searchForm').requestSubmit();}
function renderHistory(){
 $('searchHistory').replaceChildren();$('clearHistory').hidden=!searchHistory.length;
 searchHistory.forEach(q=>$('searchHistory').append(button(q,'Buscar '+q,()=>searchFrom(q))));
 if(!searchHistory.length)$('searchHistory').append(element('small','Tus búsquedas aparecerán aquí.'));
}
function rememberSearch(query){searchHistory=core.history([query,...searchHistory]);try{localStorage.setItem('mf.history',JSON.stringify(searchHistory));}catch{message('No se pudo guardar el historial.');}renderHistory();}
function downloadTrack(t){
 if(!t||t.kind==='audio')return;
 if(!window.MusicFlowAndroid||typeof MusicFlowAndroid.downloadTrack!=='function'){message('Actualiza la app Android para descargar.');return;}
 const known=downloads.find(d=>d.id===t.id);
 if(known&&known.status!=='error'){tab='downloads';render();return;}
 MusicFlowAndroid.downloadTrack(t.id,t.title);message('Preparando descarga. Sigue su progreso en Descargas.');
}
window.receiveDownloads=items=>{const next=Array.isArray(items)?items.filter(t=>core.track(t)):[];if(JSON.stringify(next)===JSON.stringify(downloads))return;downloads=next;render();restoreScroll();};
function downloadStatus(t){return t.status==='done'?'Disponible sin conexión':t.status==='error'?(t.error||'Descarga fallida'):t.status==='resolving'?'Preparando descarga…':t.status==='waiting'?'Esperando conexión…':t.status==='queued'?'En cola…':'Descargando '+(t.progress>=0?t.progress+' %':Math.round((t.bytes||0)/1024)+' KB');}
function nativeAudio(){return window.MusicFlowAndroid&&typeof MusicFlowAndroid.playAudio==='function';}
function controlAudio(command,value=0){if(nativeAudio())MusicFlowAndroid.audioControl(command,value);}
function collection(){return tab==='downloads'?downloads.filter(t=>t.status==='done').map(core.track):tab==='audio'?audioTracks:tab==='favorites'?db.favorites:tab==='playlists'?(db.playlists.find(p=>p.id===selected)||{tracks:[]}).tracks:results;}
function audioTime(ms){const s=Math.max(0,Math.floor(ms/1000));return Math.floor(s/60)+':'+String(s%60).padStart(2,'0');}
function session(t){
 current=t;const audio=true;$('miniPlayer').hidden=false;$('miniTitle').textContent=t.title;$('nowDownload').hidden=t.kind==='audio';document.body.classList.toggle('hasSession',true);
 $('playerPanel').hidden=false;$('videoStage').hidden=audio;$('audioStage').hidden=!audio;$('audioProgress').hidden=!audio;$('youtubeLink').hidden=audio;
 $('miniCover').hidden=t.kind==='audio';if(t.kind!=='audio')$('miniCover').src='https://i.ytimg.com/vi/'+t.id+'/mqdefault.jpg';
 $('nowCover').hidden=t.kind==='audio';if(t.kind!=='audio')$('nowCover').src='https://i.ytimg.com/vi/'+t.id+'/hqdefault.jpg';
 $('nowTitle').textContent=t.title;$('nowChannel').textContent=t.channel+(audio?' · Audio':' · YouTube');$('togglePlayback').textContent='▶';
 $('previousTrack').setAttribute('aria-label','Pista anterior');$('nextTrack').setAttribute('aria-label','Pista siguiente');
}
window.receiveAudioLibrary=items=>{audioTracks=Array.isArray(items)?items.map(core.track).filter(t=>t&&t.kind==='audio'):[];if(tab==='audio'){render();restoreScroll();}};
window.receiveAudioMessage=text=>{message(String(text));if(current)$('playerStatus').textContent=String(text);};
window.receiveAudioState=state=>{
 audioState=state||{active:false};
 if(Array.isArray(audioState.heard))heardIds=new Set(audioState.heard);
 const autoItems=Array.isArray(audioState.autoTracks)?audioState.autoTracks.map(core.track).filter(Boolean):[];
 $('autoStatus').textContent=audioState.autoMessage||'';
 $('autoSuggestions').hidden=!autoItems.length;
 const signature=JSON.stringify(autoItems);
 if(signature!==autoSignature){autoSignature=signature;$('autoList').replaceChildren();autoItems.slice(-100).forEach(item=>{$('autoList').append(button('▶ '+item.title,'Reproducir '+item.title,()=>playNative(item,true)));});}
 const t=audioState.active?core.track(audioState.track):null;
 const seconds=ms=>(Math.max(0,ms)/1000).toFixed(1)+' s';
 const waiting=audioState.awaitingMore?'Buscando más canciones…':(audioState.phase||'Cargando…')+' · '+seconds(audioState.elapsedMs||0);
 if(audioState.active)$('loadDetails').textContent='Fuente: '+(audioState.loadSource||'online')+' · Conseguir enlace: '+(audioState.resolutionMs>=0?seconds(audioState.resolutionMs):'en curso')+' · Preparar audio: '+(audioState.preparationMs>=0?seconds(audioState.preparationMs):'pendiente')+' · Total: '+seconds(audioState.elapsedMs||0)+(audioState.retry?' · Segundo intento':'');
 if(t){
  shuffle=!!audioState.shuffle;showShuffle();queueIndex=audioState.index;
  if(!current||current.id!==t.id){pauseMusic();session(t);render();}
  $('miniTitle').textContent=t.title;$('miniStatus').textContent=(audioState.loading||audioState.awaitingMore)?waiting:audioState.error||(audioState.buffering?'Recibiendo audio…':audioState.playing?(audioState.offline?'Sin conexión · reproduciendo':'Online · reproduciendo'):'En pausa');$('miniToggle').textContent=(audioState.loading||audioState.awaitingMore)?'×':audioState.playing?'Ⅱ':'▶';$('miniToggle').setAttribute('aria-label',(audioState.loading||audioState.awaitingMore)?'Cancelar carga':audioState.playing?'Pausar':'Reproducir');$('miniNext').disabled=audioState.index>=audioState.size-1;
  if(!audioState.loading){current=t;$('nowTitle').textContent=t.title;$('nowChannel').textContent=t.channel;}
  $('togglePlayback').disabled=false;$('togglePlayback').textContent=(audioState.loading||audioState.awaitingMore)?'×':audioState.playing?'Ⅱ':'▶';$('togglePlayback').setAttribute('aria-label',(audioState.loading||audioState.awaitingMore)?'Cancelar carga':audioState.playing?'Pausar':'Reproducir');document.body.classList.toggle('audioLoading',!!audioState.loading);
  $('playerStatus').textContent=audioState.error||((audioState.loading||audioState.awaitingMore)?waiting:audioState.buffering?'Recibiendo audio…':audioState.playing?'La música continúa al minimizar y bloquear el teléfono.':'En pausa · pulsa reproducir para continuar.');
  $('previousTrack').disabled=audioState.index<=0;$('nextTrack').disabled=audioState.index>=audioState.size-1;$('queuePosition').textContent=(audioState.index+1)+' / '+audioState.size;
  $('audioSeek').disabled=!audioState.duration||audioState.loading;$('audioSeek').max=audioState.duration||1;$('audioSeek').value=audioState.position||0;
  $('miniProgress').firstElementChild.style.width=(audioState.duration?Math.min(100,Math.max(0,(audioState.position||0)/audioState.duration*100)):0)+'%';
  $('audioElapsed').textContent=audioTime(audioState.position||0);$('audioDuration').textContent=audioState.duration?audioTime(audioState.duration):'EN VIVO';
 }else if(current&&!audioState.active){$('togglePlayback').disabled=false;$('togglePlayback').textContent='▶';$('playerStatus').textContent=audioState.error||'Sesión detenida. Pulsa reproducir para volver a iniciar.';}
 document.body.classList.toggle('audioPlaying',!!audioState.playing&&!!current);
};
function playNative(t,keepQueue){
 if(current&&current.id===t.id&&document.body.classList.contains('audioLoading'))return;
 if(!nativeAudio()){message('La reproducción de audio está disponible en la app Android.');return;}
 if(t.kind==='audio'&&!audioTracks.some(item=>item.id===t.id)){message('Vuelve a importar este audio desde Mi música. Los respaldos no incluyen los archivos del teléfono.');return;}
 pauseMusic();if(!keepQueue)queue=collection().slice();if(!queue.some(item=>item.id===t.id))queue=[t];queueIndex=queue.findIndex(item=>item.id===t.id);
 session(t);$('playerStatus').textContent='Preparando audio para segundo plano…';$('togglePlayback').disabled=true;
 controlAudio('shuffle',shuffle?1:0);
 const ids=keepQueue&&Array.isArray(audioState.queueIds)&&audioState.queueIds.includes(t.id)?audioState.queueIds:queue.map(item=>item.id);
 if(typeof MusicFlowAndroid.playAudioQueue==='function')MusicFlowAndroid.playAudioQueue(t.id,JSON.stringify(ids),keepQueue&&audioState.autoQuery?audioState.autoQuery:tab==='results'?lastQuery:t.channel,keepQueue&&typeof audioState.automatic==='boolean'?audioState.automatic:t.kind!=='audio'&&tab!=='downloads'&&tab!=='audio');
 else MusicFlowAndroid.playAudio(t.id,JSON.stringify(ids));render();
}
try{const saved=localStorage.getItem('mf.library');if(saved)db=core.library(JSON.parse(saved));}catch{$('status').textContent='No se pudo leer la biblioteca. Puedes importar un respaldo.';}
function persist(){try{localStorage.setItem('mf.library',JSON.stringify(db));return true;}catch{message('No se pudo guardar: almacenamiento lleno o no disponible. Exporta tu biblioteca.');return false;}}
function message(text){$('status').textContent=text;$('status').hidden=!text;}
function accepted(){return localStorage.getItem('mf.consent')==='1';}
let afterConsent=null;
function consent(continuation){if(accepted())return true;afterConsent=continuation||null;$('privacy').showModal();return false;}
function element(tag,text,cls){const e=document.createElement(tag);if(text)e.textContent=text;if(cls)e.className=cls;return e;}
function button(text,label,click){const e=element('button',text);e.setAttribute('aria-label',label);e.onclick=click;return e;}
function favorite(t){const i=db.favorites.findIndex(x=>x.id===t.id);if(i<0)db.favorites.push(t);else db.favorites.splice(i,1);persist();render();}
function render(){
 saveView();
 document.body.dataset.section=tab;
 $('searchArea').hidden=tab!=='results';
 $('collectionArt').textContent=({results:'♫',downloads:'↓',favorites:'♥',playlists:'≡',audio:'♫'})[tab];
 document.querySelectorAll('[data-tab]').forEach(b=>{b.classList.toggle('active',b.dataset.tab===tab);if(b.dataset.tab===tab)b.setAttribute('aria-current','page');else b.removeAttribute('aria-current');});
 $('moreResults').hidden=tab!=='results'||!nextPage; $('moreResults').disabled=searchBusy;
 $('newPlaylist').hidden=tab!=='playlists';$('playlistNames').replaceChildren();
 $('audioTools').hidden=tab!=='audio';
 let tracks=results;$('listTitle').textContent=tab==='results'?'Tus resultados':tab==='favorites'?'Tus favoritos':'Tus playlists';
 if(tab==='downloads'){tracks=downloads;$('listTitle').textContent='Tus descargas';}
 if(tab==='favorites')tracks=db.favorites;
 if(tab==='audio'){const filter=$('audioFilter').value.trim().toLowerCase();tracks=audioTracks.filter(t=>t.title.toLowerCase().includes(filter));$('listTitle').textContent='Mi música';}
 if(tab==='playlists'){
  db.playlists.forEach(p=>{const b=button(p.name,p.name,()=>{selected=p.id;render();});b.classList.toggle('selected',p.id===selected);$('playlistNames').append(b);});
  const p=db.playlists.find(p=>p.id===selected);tracks=p?p.tracks:[];
  if(p){$('listTitle').textContent=p.name;$('playlistNames').append(button('Renombrar','Renombrar playlist',()=>{const n=prompt('Nuevo nombre',p.name);if(n&&n.trim()){p.name=n.trim().slice(0,80);persist();render();}}),button('Eliminar','Eliminar playlist',()=>{if(confirm('¿Eliminar esta playlist local?')){db.playlists=db.playlists.filter(x=>x!==p);selected=null;persist();render();}}));}
 }
 const readyCount=tab==='downloads'?downloads.filter(t=>t.status==='done').length:tracks.length;
 $('collectionCount').textContent=tab==='playlists'&&!selected?db.playlists.length+' playlists':readyCount+' '+(readyCount===1?'canción':'canciones')+(tab==='downloads'?' · sin conexión':'');
 $('playAll').disabled=!collection().length;$('shuffleAll').disabled=collection().length<2;
 $('list').replaceChildren();
 if(!tracks.length&&searchBusy&&tab==='results')for(let i=0;i<4;i++){const placeholder=element('div',null,'skeleton');placeholder.setAttribute('aria-hidden','true');$('list').append(placeholder);}
 if(!tracks.length&&!(searchBusy&&tab==='results'))$('list').append(element('div',tab==='audio'?'Añade canciones desde tu teléfono o un enlace de radio. Se reproducirán también con la pantalla apagada.':tab==='downloads'?'Pulsa ↓ en una canción para guardarla y escucharla sin conexión.':tab==='results'?'Busca un artista o canción en YouTube.':tab==='favorites'?'Guarda tus canciones con el corazón.': 'Crea una playlist y añade pistas con el botón ＋.','empty'));
 tracks.forEach(t=>{
  const audio=t.kind==='audio',row=element('article',null,'row'),img=element(audio?'div':'img',audio?'♫':null,audio?'cover audioCover':'cover');row.classList.toggle('currentTrack',!!current&&current.id===t.id);if(!audio){img.src='https://i.ytimg.com/vi/'+t.id+'/mqdefault.jpg';img.alt='';img.loading='lazy';}
  const play=element('button',null,'track');play.append(element('strong',t.title),element('small',t.channel+(audio?' · Segundo plano':' · YouTube')));play.onclick=()=>playTrack(t);if(tab==='downloads'&&t.status!=='done')play.disabled=true;
  if(tab==='downloads')play.append(element('small',downloadStatus(t),'downloadStatus'));
  const actions=element('div',null,'actions');const quickPlay=button('▶','Reproducir '+t.title,()=>playTrack(t));quickPlay.className='rowPlay';quickPlay.disabled=tab==='downloads'&&t.status!=='done';actions.append(quickPlay);actions.append(button(db.favorites.some(x=>x.id===t.id)?'♥':'♡','Alternar favorito',()=>favorite(t)),button('＋','Añadir a playlist',()=>addToPlaylist(t)));
  if(!audio&&tab!=='downloads')actions.append(button(downloads.some(d=>d.id===t.id&&d.status==='done')?'✓':'↓','Descargar canción',()=>downloadTrack(t)));
  if(tab==='downloads'){if(t.status==='error')actions.append(button('↻','Reintentar descarga',()=>downloadTrack(t)));actions.append(button('×',t.status==='done'?'Eliminar descarga':'Cancelar descarga',()=>{if(confirm(t.status==='done'?'¿Eliminar el archivo descargado? Tus favoritos se conservan.':'¿Cancelar esta descarga?'))MusicFlowAndroid.removeDownload(t.id);}));}
  if(tab==='playlists')actions.append(button('×','Quitar de playlist',()=>{const p=db.playlists.find(p=>p.id===selected);p.tracks=p.tracks.filter(x=>x.id!==t.id);persist();render();}));
  if(tab==='audio')actions.append(button('×','Quitar audio de biblioteca',()=>{if(confirm('¿Quitar de MusicFlow? El archivo original no se borra.')){MusicFlowAndroid.removeAudio(t.id);db.favorites=db.favorites.filter(x=>x.id!==t.id);db.playlists.forEach(p=>p.tracks=p.tracks.filter(x=>x.id!==t.id));persist();}}));
  const menu=document.createElement('details');menu.className='trackMenu';
  const summary=element('summary','⋮');summary.setAttribute('aria-label','Opciones de '+t.title);menu.append(summary,actions);
  Array.from(actions.children).forEach(b=>{if(b!==quickPlay){const label=b.getAttribute('aria-label');b.textContent=label==='Alternar favorito'?(db.favorites.some(x=>x.id===t.id)?'♥ Quitar favorito':'♡ Guardar favorito'):label;b.addEventListener('click',()=>menu.open=false);}});
  menu.addEventListener('toggle',()=>{if(menu.open)document.querySelectorAll('.trackMenu[open]').forEach(other=>{if(other!==menu)other.open=false;});});
  row.append(img,play,quickPlay,menu);$('list').append(row);
 });
 $('nowFavorite').textContent=current&&db.favorites.some(t=>t.id===current.id)?'♥':'♡';
 if(audioState.active){$('previousTrack').disabled=audioState.index<=0;$('nextTrack').disabled=audioState.index>=audioState.size-1;$('queuePosition').textContent=(audioState.index+1)+' / '+audioState.size;}
 showShuffle();
}
function addToPlaylist(t){
 if(!db.playlists.length){message('Crea primero una playlist en la pestaña Playlists.');return;}
 const choice=prompt('Elige el número de playlist:\n'+db.playlists.map((p,i)=>(i+1)+'. '+p.name).join('\n'));if(choice===null)return;
 const p=db.playlists[Number(choice)-1];if(!p){message('Elige un número de la lista.');return;}if(!p.tracks.some(x=>x.id===t.id))p.tracks.push(t);persist();message('Añadido a '+p.name);render();
}
function playTrack(t,keepQueue=false){
 if(t.kind!=='audio'&&!consent(()=>playTrack(t,keepQueue)))return;
 playNative(t,keepQueue);
}
// Android's service owns playback; hiding this page must never pause it.
window.pauseMusic=()=>{};
$('closePlayer').onclick=()=>{saveView();if(audioState.active&&!audioState.restored)controlAudio('stop');document.body.classList.remove('playerExpanded');};
$('shuffleToggle').onclick=()=>setShuffle(!shuffle);
$('playAll').onclick=()=>{const tracks=collection();if(tracks.length){setShuffle(false);playTrack(tracks[0]);}};
$('shuffleAll').onclick=()=>{const tracks=collection().filter(t=>!heardIds.has(t.id));if(tracks.length){setShuffle(true);playTrack(tracks[Math.floor(Math.random()*tracks.length)]);}else message('Ya escuchaste todas estas canciones. Puedes elegir otra colección o reiniciar el aleatorio desde Configuración.');};
$('nowDownload').onclick=()=>downloadTrack(current);
$('miniToggle').onclick=()=>{$('togglePlayback').onclick();};
$('miniNext').onclick=()=>controlAudio('next');
$('expandPlayer').onclick=()=>{document.body.classList.toggle('playerExpanded',true);$('playerPanel').scrollTop=0;$('collapsePlayer').focus();};
$('collapsePlayer').onclick=()=>{document.body.classList.toggle('playerExpanded',false);$('expandPlayer').focus();};
$('nowFavorite').onclick=()=>{if(current)favorite(current);};
$('nowPlaylist').onclick=()=>{if(current)addToPlaylist(current);};
$('togglePlayback').onclick=()=>{if(!current)return;if(audioState.active&&!audioState.restored)controlAudio('toggle');else playNative(current,true);};
$('previousTrack').onclick=()=>controlAudio('previous');
$('nextTrack').onclick=()=>controlAudio('next');
$('audioSeek').onchange=()=>{if(current)controlAudio('seek',Number($('audioSeek').value));};
$('audioFilter').oninput=()=>render();
$('importAudio').onclick=()=>{if(nativeAudio())MusicFlowAndroid.importAudio();else message('Abre MusicFlow en Android para importar música.');};
$('addStream').onclick=()=>{if(!nativeAudio()){message('Disponible en Android.');return;}const url=prompt('Enlace HTTPS de audio directo o radio (MP3, AAC, etc.). No sirve una página de YouTube.');if(!url||!url.trim())return;const title=prompt('Nombre de la emisora o audio','Mi audio');if(title&&title.trim())MusicFlowAndroid.addAudioStream(title.trim(),url.trim());};
document.querySelectorAll('[data-tab]').forEach(b=>b.onclick=()=>{restoredScroll=0;tab=b.dataset.tab;message('');render();window.scrollTo({top:0});});
$('newPlaylist').onclick=()=>{const name=prompt('Nombre de tu playlist');if(name&&name.trim()){const p={id:'p'+Date.now(),name:name.trim().slice(0,80),tracks:[]};db.playlists.push(p);selected=p.id;persist();render();}};
let searchSequence=0,searchTimer=null,nextPage='',searchBusy=false,appendSearch=false,lastQuery='',lastMode='songs';
window.receiveSearchResults=(sequence,items,error,meta={})=>{
 if(sequence!==searchSequence)return;
 clearTimeout(searchTimer);searchBusy=false;
 if(!error){const incoming=(Array.isArray(items)?items:[]).map(core.track).filter(Boolean);results=Array.from(new Map((appendSearch?[...results,...incoming]:incoming).map(t=>[t.id,t])).values()).slice(0,500);nextPage=results.length>=500?'':meta.next||'';if(!appendSearch)saveSearch(lastQuery,results);}
 render();message((error?(results.length?'No se pudo actualizar. Puedes usar los resultados guardados. '+error:error):'')|| (results.length?results.length+' resultados'+(meta.cached?' · búsqueda reciente':'')+' · toca una canción':'No encontramos canciones. Prueba con otro artista o título.'));
};
function submitSearch(more=false){
 if(!consent(()=>submitSearch(more)))return;
 const query=more?lastQuery:$('query').value.trim();if(!query||searchBusy&&more)return;
 restoredScroll=0;const mode='songs';
 const sequence=++searchSequence;clearTimeout(searchTimer);tab='results';appendSearch=more;
 if(!more){results=savedSearch(query);nextPage='';lastQuery=query;lastMode=mode;rememberSearch(query);}
 const id=core.videoId(query);
 if(id){searchBusy=false;results=[{id,title:'Video '+id,channel:'YouTube'}];render();message('Toca la canción para escuchar.');return;}
 if(!window.MusicFlowAndroid){message('Abre la app Android para buscar en el catálogo.');return;}
 searchBusy=true;render();message(more?'Buscando más canciones…':results.length?'Resultados guardados · actualizando…':'Buscando “'+query+'”…');
 if(typeof MusicFlowAndroid.searchCatalog==='function')MusicFlowAndroid.searchCatalog(query,mode,more?nextPage:'',sequence);
 else MusicFlowAndroid.searchYouTube(query,sequence);
 if($('query').blur)$('query').blur();
 searchTimer=setTimeout(()=>{if(sequence===searchSequence){searchSequence++;searchBusy=false;render();message('La búsqueda tardó demasiado. Vuelve a intentarlo.');}},60000);
}
$('query').addEventListener('input',()=>{$('clearQuery').hidden=!$('query').value;saveView();});
$('clearQuery').onclick=()=>{$('query').value='';$('clearQuery').hidden=true;$('query').focus();saveView();};
$('resetShuffle').onclick=()=>{if(confirm('¿Permitir que el modo aleatorio vuelva a elegir las canciones ya escuchadas?')){if(window.MusicFlowAndroid)MusicFlowAndroid.resetShuffleHistory();heardIds.clear();message('Aleatorio reiniciado.');}};
$('searchForm').onsubmit=event=>{event.preventDefault();submitSearch();};
$('moreResults').onclick=()=>submitSearch(true);
$('clearHistory').onclick=()=>{searchHistory=[];try{localStorage.removeItem('mf.history');localStorage.removeItem('mf.searchCache');}catch{}renderHistory();};
renderHistory();
['Salsa','Reggaetón','Rock en español','Cumbia peruana','Pop','Lo-fi'].forEach(q=>$('discovery').append(button(q,'Explorar '+q,()=>searchFrom(q))));
document.addEventListener('click',event=>{document.querySelectorAll('.trackMenu[open]').forEach(menu=>{if(!menu.contains(event.target))menu.open=false;});});
document.addEventListener('keydown',event=>{if(event.key==='Escape'&&document.body.classList.contains('playerExpanded'))$('collapsePlayer').click();});
// Place progress before transport, matching the reading order of a music player.
$('playerPanel').insertBefore($('audioProgress'),document.querySelector('.transport'));
const navIcons={results:'<circle cx="10" cy="10" r="6"/><path d="m15 15 5 5"/>',audio:'<path d="M9 18V5l11-2v13M9 7l11-2"/><circle cx="6" cy="18" r="3"/><circle cx="17" cy="16" r="3"/>',downloads:'<path d="M12 3v12m-5-5 5 5 5-5M4 17v4h16v-4"/>',favorites:'<path d="M20 5c-3-3-7-1-8 1-1-2-5-4-8-1-5 5 3 11 8 15 5-4 13-10 8-15Z"/>',playlists:'<path d="M4 4v16M9 4v16m5-15 5-1 3 15-5 1Z"/>'};
document.querySelectorAll('[data-tab]').forEach(b=>{const label=b.textContent;b.replaceChildren();const icon=document.createElementNS('http://www.w3.org/2000/svg','svg');icon.setAttribute('viewBox','0 0 24 24');icon.setAttribute('aria-hidden','true');icon.innerHTML=navIcons[b.dataset.tab];b.append(icon,element('span',label));});
$('settingsButton').onclick=()=>{$('settings').showModal();};
$('privacyButton').onclick=()=>{$('privacy').showModal();};
$('acceptPrivacy').onclick=()=>{localStorage.setItem('mf.consent','1');$('privacy').close();message('Listo. Busca por artista o canción.');const continuation=afterConsent;afterConsent=null;if(continuation)continuation();};
document.querySelectorAll('[data-close]').forEach(b=>b.onclick=()=>$(b.dataset.close).close());
$('eraseData').onclick=()=>{if(confirm('¿Borrar todos los favoritos y playlists de este dispositivo?')){db={favorites:[],playlists:[]};persist();selected=null;render();}};
$('exportData').onclick=()=>{if(window.MusicFlowAndroid){MusicFlowAndroid.exportLibrary(JSON.stringify(db,null,2));return;}const blob=new Blob([JSON.stringify(db,null,2)],{type:'application/json'}),url=URL.createObjectURL(blob),a=element('a');a.href=url;a.download='MusicFlow-biblioteca.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);};
$('importData').onchange=async event=>{const file=event.target.files[0];if(!file)return;try{if(file.size>5000000)throw Error('El archivo supera 5 MB');const imported=core.library(JSON.parse(await file.text()));if(confirm('¿Reemplazar la biblioteca con este respaldo?')){db=imported;persist();selected=null;render();message('Biblioteca importada.');}}catch(error){message('No se pudo importar: '+error.message);}event.target.value='';};
restoreView();$('clearQuery').hidden=!$('query').value;render();restoreScroll();
